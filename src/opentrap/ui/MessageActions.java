package opentrap.ui;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.LogEntry;
import opentrap.core.SuiteContext;

import javax.swing.JOptionPane;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
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

    /** Write the response body to a temporary file and open it in the system browser. */
    public static void openResponseInBrowser(HttpMessage response) {
        try {
            File f = File.createTempFile("opentrap-resp", ".html");
            f.deleteOnExit();
            try (PrintWriter w = new PrintWriter(f, StandardCharsets.ISO_8859_1)) {
                w.print(response.bodyText());
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(f.toURI());
            }
        } catch (Exception ignored) {}
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
