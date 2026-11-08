package opentrap.ui;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.LogEntry;
import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.proxy.ProxyListener;
import opentrap.proxy.ProxyServer;
import opentrap.proxy.ProxyTool;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.datatransfer.StringSelection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared request/response actions used by the various right-click menus. */
public final class MessageActions {
    private MessageActions() {}

    /** Put {@code s} on the system clipboard. */
    public static void clip(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null);
    }

    /** A bash curl command that reproduces {@code request} to {@code service}. */
    public static String curl(HttpService service, HttpMessage request) {
        StringBuilder b = new StringBuilder("curl -i -s -k ");
        b.append("-X ").append(request.method()).append(' ');
        for (String line : request.headerLines()) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String n = line.substring(0, c).trim();
            if (n.equalsIgnoreCase("content-length")) continue;
            b.append("-H '").append(line.replace("'", "'\\''")).append("' ");
        }
        if (request.body().length > 0) {
            b.append("--data-raw '").append(request.bodyText().replace("'", "'\\''")).append("' ");
        }
        b.append('\'').append(service == null ? "" : service.url()).append(request.requestTarget()).append('\'');
        return b.toString();
    }

    private static final String SHOW_COPY_ONLY = "proxy.showInBrowser.copyOnly";

    /** The proxy server, if one is registered, else null. */
    private static ProxyServer proxyServer(SuiteContext ctx) {
        if (ctx == null) return null;
        for (ToolModule t : ctx.tools()) if (t instanceof ProxyTool pt) return pt.server();
        return null;
    }

    /** The port of a running proxy listener, or -1. */
    private static int runningPort(ProxyServer server) {
        if (server == null) return -1;
        for (ProxyListener l : server.listeners()) if (l.running) return l.port;
        return -1;
    }

    /**
     * Show a captured response in a proxied browser the way the reference tools
     * do: the stored bytes are parked behind a sentinel URL on the proxy, which
     * redirects the browser to the genuine address and serves the response for
     * that hit. The browser therefore shows the real URL while rendering exactly
     * what was captured, and the view is confined to a browser that is proxied
     * through OpenTRAP. The user is given the sentinel URL to paste into the
     * OpenTRAP browser, because the response can only be shown through the proxy.
     */
    public static void openResponseInBrowser(Component parent, SuiteContext ctx,
                                             HttpService svc, HttpMessage request, HttpMessage response) {
        if (response == null) return;
        ProxyServer server = proxyServer(ctx);
        if (runningPort(server) < 0) {
            JOptionPane.showMessageDialog(parent,
                    "Start a proxy listener first, then open the OpenTRAP browser; a response\n"
                    + "can only be shown through the proxy so the real URL and page are preserved.",
                    "Open response in browser", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String realUrl = (svc == null || request == null) ? null
                : svc.url() + request.requestTarget();
        String sentinel = server.stageShow(realUrl, response.raw());

        if (ctx.settings().getBool(SHOW_COPY_ONLY, false)) {
            clip(sentinel);
            ctx.alert("Open response in browser",
                    "URL copied; paste it into the OpenTRAP browser.");
            return;
        }
        showSentinelDialog(parent, ctx, sentinel);
    }

    /** The reference-style "paste this URL into your proxied browser" dialog. */
    private static void showSentinelDialog(Component parent, SuiteContext ctx, String sentinel) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        JDialog d = new JDialog(owner, "Show response in browser", JDialog.ModalityType.MODELESS);
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));

        JLabel msg = new JLabel("<html>To show this response in your browser, copy the URL below and"
                + " paste it into a browser that is proxied through OpenTRAP (the OpenTRAP browser,"
                + " opened from the Proxy tab).</html>");
        msg.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(msg);
        body.add(Box.createVerticalStrut(10));

        JTextField url = new JTextField(sentinel);
        url.setEditable(false);
        url.setCaretPosition(0);
        url.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(url);
        body.add(Box.createVerticalStrut(8));

        JCheckBox copyOnly = new JCheckBox("In future, just copy the URL and don't show this dialog");
        copyOnly.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(copyOnly);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
        JButton copy = new JButton("Copy");
        JButton close = new JButton("Close");
        Runnable remember = () -> { if (copyOnly.isSelected()) ctx.settings().setBool(SHOW_COPY_ONLY, true); };
        // Copying hands over the URL, so the dialog has done its job: take the
        // preference and close.
        copy.addActionListener(e -> { clip(sentinel); remember.run(); d.dispose(); });
        close.addActionListener(e -> { remember.run(); d.dispose(); });
        buttons.add(copy);
        buttons.add(close);

        d.add(body, BorderLayout.CENTER);
        d.add(buttons, BorderLayout.SOUTH);
        d.getRootPane().setDefaultButton(copy);
        d.pack();
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
    }

    private static final Pattern LINK = Pattern.compile(
            "(?:href|src|action)\\s*=\\s*[\"']([^\"'#>\\s]+)", Pattern.CASE_INSENSITIVE);

    /**
     * The links a response body points at, each resolved to an absolute URL
     * against {@code base} (the request's absolute URL). Fragments, javascript:
     * and data: targets are skipped, and duplicates removed, keeping order.
     */
    public static String linksIn(HttpMessage response, String base) {
        Set<String> out = new LinkedHashSet<>();
        Matcher m = LINK.matcher(response.bodyText());
        while (m.find()) {
            String raw = m.group(1).trim();
            if (raw.isEmpty()) continue;
            String low = raw.toLowerCase();
            if (low.startsWith("javascript:") || low.startsWith("data:") || low.startsWith("mailto:") || low.startsWith("tel:")) continue;
            out.add(resolve(base, raw));
        }
        return String.join("\n", out);
    }

    /** Resolve {@code ref} against {@code base} into an absolute URL, best effort. */
    private static String resolve(String base, String ref) {
        try { return java.net.URI.create(base).resolve(ref).toString(); }
        catch (Exception e) { return ref; }
    }

    /** Open a GET request's URL in the system browser; other methods cannot be replayed this way. */
    public static void openRequestInBrowser(Component parent, HttpService svc, HttpMessage req) {
        if (req == null) return;
        if (!"GET".equalsIgnoreCase(req.method())) {
            JOptionPane.showMessageDialog(parent,
                    "Only GET requests can be opened in a browser; this one is " + req.method() + ".",
                    "request in browser", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            String url = (svc == null ? "" : svc.url()) + req.requestTarget();
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(url));
            }
        } catch (Exception ignored) {}
    }

    /** Record a request (and its response) so it appears in the site map and history. */
    public static void addToSiteMap(SuiteContext ctx, HttpService svc, HttpMessage req, HttpMessage resp) {
        if (ctx == null || svc == null || req == null) return;
        LogEntry e = new LogEntry("proxy", svc, req.copy());
        if (resp != null) e.setResponse(resp.copy());
        ctx.store().add(e);
        ctx.siteMapChanged(e);
    }
}
