package opentrap.repeater;

import opentrap.core.*;
import opentrap.ui.*;

import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Manual request editing and reissue, one numbered tab per request. */
public class RepeaterTool implements ToolModule {

    private SuiteContext ctx;
    private final JTabbedPane tabs = new JTabbedPane();
    private final AtomicInteger counter = new AtomicInteger(0);
    private boolean followRedirects = false;
    private boolean updateContentLength = true;

    public RepeaterTool() {}

    @Override public String name() { return "repeater"; }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        ctx.registerSink("repeater", (svc, req, resp, cap) ->
            SwingUtilities.invokeLater(() -> newTab(svc, req, cap)));
        SettingsDialog.register("Repeater", this::settingsPane);
    }

    @Override public Tab[] tabs() {
        tabs.setFont(Ui.PLAIN);
        if (tabs.getTabCount() == 0) newTab(new HttpService("127.0.0.1", 80, false),
                new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")), null);
        return new Tab[]{ new Tab("repeater", tabs) };
    }

    @Override public java.io.Serializable saveState() {
        java.util.ArrayList<TabState> out = new java.util.ArrayList<>();
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getComponentAt(i) instanceof RepeaterTab rt) {
                TabState s = new TabState();
                s.title = tabs.getTitleAt(i);
                s.host = rt.hostField.getText();
                try { s.port = Integer.parseInt(rt.portField.getText().trim()); } catch (Exception e) { s.port = 80; }
                s.secure = rt.sslBox.isSelected();
                s.req = rt.reqEditor.message().raw();
                s.resp = rt.respEditor.message().raw();
                out.add(s);
            }
        }
        return out.isEmpty() ? null : out;
    }

    @Override public void restoreState(java.io.Serializable state) {
        if (!(state instanceof java.util.List<?> list) || list.isEmpty()) return;
        tabs.removeAll();
        for (Object o : list) {
            if (!(o instanceof TabState s)) continue;
            HttpService svc = new HttpService(s.host == null ? "127.0.0.1" : s.host, s.port, s.secure);
            newTab(svc, new HttpMessage(s.req == null ? new byte[0] : s.req), s.title);
            if (tabs.getComponentAt(tabs.getTabCount() - 1) instanceof RepeaterTab rt
                    && s.resp != null && s.resp.length > 0) {
                rt.respEditor.setMessage(svc, new HttpMessage(s.resp), rt.reqEditor.message());
            }
        }
    }

    private static final class TabState implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        String title; String host; int port; boolean secure; byte[] req; byte[] resp;
    }

    @Override public boolean alwaysTabbed() { return false; }

    @Override public JMenu[] menus() {
        JMenu menu = new JMenu("repeater");
        JMenuItem nt = new JMenuItem("new tab");
        nt.addActionListener(e -> newTab(new HttpService("127.0.0.1", 80, false),
                new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n")), null));
        JMenuItem dt = new JMenuItem("delete tab");
        dt.addActionListener(e -> { if (tabs.getTabCount() > 1) tabs.remove(tabs.getSelectedIndex()); });
        JMenuItem rt = new JMenuItem("rename tab");
        rt.addActionListener(e -> {
            int i = tabs.getSelectedIndex();
            if (i < 0) return;
            String name = JOptionPane.showInputDialog(ctx.frame(), "enter the new tab name", tabs.getTitleAt(i));
            if (name != null && !name.isBlank()) tabs.setTitleAt(i, name);
        });
        JMenuItem st = new JMenuItem("repeater settings");
        st.addActionListener(e -> SettingsDialog.open(
                ctx.frame() instanceof Window w ? w : null, ctx, "Repeater"));
        for (JMenuItem m : new JMenuItem[]{nt, dt, rt, st}) m.setFont(Ui.PLAIN);
        menu.add(nt); menu.add(dt); menu.add(rt);
        menu.addSeparator();
        menu.add(st);
        return new JMenu[]{ menu };
    }

    /** Settings > Repeater: how a reissued request is prepared and followed. */
    private JComponent settingsPane() {
        OptionsPage page = new OptionsPage();
        JCheckBox ucl = Ui.check("update Content-Length", updateContentLength);
        ucl.addActionListener(e -> updateContentLength = ucl.isSelected());
        JCheckBox fr = Ui.check("follow redirects", followRedirects);
        fr.addActionListener(e -> followRedirects = fr.isSelected());
        page.section("Request handling",
                Ui.note("Applies to every Repeater tab. Content-Length is recomputed from the "
                        + "edited body before the request is sent; redirects are followed to "
                        + "their final response when enabled."),
                ucl, fr);
        return page;
    }

    private void newTab(HttpService svc, HttpMessage req, String caption) {
        if (svc == null) svc = new HttpService("127.0.0.1", 80, false);
        if (req == null) req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: " + svc.host() + "\r\n\r\n"));
        RepeaterTab tab = new RepeaterTab(svc, req);
        String title = caption != null && !caption.isBlank() ? caption : String.valueOf(counter.incrementAndGet());
        tabs.addTab(title, tab);
        tabs.setSelectedComponent(tab);
    }

    private class RepeaterTab extends JPanel {
        private final MessageEditor reqEditor;
        private final MessageEditor respEditor;
        private final JTextField hostField;
        private final JTextField portField;
        private final JCheckBox sslBox;
        private final JComboBox<HttpMode> modeCombo = Ui.combo(HttpMode.values());
        private final JLabel status = Ui.label("ready");
        private final JLabel lengthLabel = Ui.label("length: 0");
        private final JButton go = Ui.primaryButton("Send", "send the request");
        private final JButton cancel = Ui.button("Cancel");
        private final JButton prev = Ui.button("<", "previous request/response in this tab");
        private final JButton next = Ui.button(">", "next request/response in this tab");
        private final JLabel histLabel = Ui.label("");
        private JLabel targetLabel;
        private boolean overrideSni;
        private volatile Thread worker;

        private void refreshTarget() {
            String host = hostField.getText().trim();
            String port = portField.getText().trim();
            boolean tls = sslBox.isSelected();
            boolean defPort = (tls && "443".equals(port)) || (!tls && "80".equals(port));
            targetLabel.setText("Target: " + (tls ? "https://" : "http://") + host + (defPort ? "" : ":" + port));
        }

        /** "Configure target details": host, SNI override, port, HTTPS. */
        private void configureTarget() {
            JTextField host = Ui.field(hostField.getText(), 30);
            JCheckBox sni = Ui.check("Override SNI", overrideSni);
            JTextField port = Ui.field(portField.getText(), 8);
            JCheckBox https = Ui.check("Use HTTPS", sslBox.isSelected());
            https.addActionListener(e -> {
                String p = port.getText().trim();
                if (p.equals("80") && https.isSelected()) port.setText("443");
                else if (p.equals("443") && !https.isSelected()) port.setText("80");
            });
            JPanel form = Ui.vbox(Ui.note("Specify the details of the server to which the request will be sent."),
                    Ui.form("Host:", host), sni, Ui.form("Port:", port), https);
            form.setBorder(Ui.pad(10, 10, 10, 10));
            int ok = JOptionPane.showConfirmDialog(this, form, "Configure target details", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return;
            hostField.setText(host.getText().trim());
            portField.setText(port.getText().trim());
            sslBox.setSelected(https.isSelected());
            overrideSni = sni.isSelected();
            refreshTarget();
        }

        /** The per-tab settings menu: the project defaults can be overridden here for this tab only. */
        private JPopupMenu tabSettingsMenu() {
            JPopupMenu m = new JPopupMenu();
            JPanel head = new JPanel(new BorderLayout(10, 0));
            head.setOpaque(false);
            head.setBorder(Ui.pad(4, 10, 4, 10));
            head.add(Ui.bold("Repeater settings for this tab"), BorderLayout.WEST);
            JButton restore = Ui.button("Restore global defaults");
            restore.addActionListener(e -> { tabUpdateCl = null; tabFollow = null; m.setVisible(false); });
            head.add(restore, BorderLayout.EAST);
            m.add(head);
            m.addSeparator();
            JCheckBoxMenuItem ucl = new JCheckBoxMenuItem("Update Content-Length", tabUpdateCl == null ? updateContentLength : tabUpdateCl);
            ucl.addActionListener(e -> tabUpdateCl = ucl.isSelected());
            JCheckBoxMenuItem unpack = new JCheckBoxMenuItem("Unpack compressed responses", true);
            JMenu follow = new JMenu("Follow redirections");
            ButtonGroup fg = new ButtonGroup();
            for (String o : new String[]{"Never", "On-site only", "In-scope only", "Always"}) {
                JRadioButtonMenuItem r = new JRadioButtonMenuItem(o, o.equals(tabFollow == null ? (followRedirects ? "Always" : "Never") : tabFollow));
                r.addActionListener(e -> tabFollow = o);
                fg.add(r); follow.add(r);
            }
            JCheckBoxMenuItem cookies = new JCheckBoxMenuItem("Process cookies in redirections", false);
            JCheckBoxMenuItem enforce = new JCheckBoxMenuItem("Enforce protocol choice on cross-domain redirections", false);
            JCheckBoxMenuItem normalize = new JCheckBoxMenuItem("Normalize HTTP/1 line endings", true);
            JCheckBoxMenuItem h1reuse = new JCheckBoxMenuItem("Enable HTTP/1 connection reuse", false);
            JCheckBoxMenuItem h2reuse = new JCheckBoxMenuItem("Enable HTTP/2 connection reuse", true);
            JCheckBoxMenuItem strip = new JCheckBoxMenuItem("Strip Connection header over HTTP/2", true);
            JCheckBoxMenuItem alpn = new JCheckBoxMenuItem("Allow HTTP/2 ALPN override", false);
            JMenu timeout = new JMenu("Streaming response timeout");
            ButtonGroup tg = new ButtonGroup();
            for (String o : new String[]{"30 seconds", "1 minute", "5 minutes", "10 minutes", "Never", "Custom..."}) {
                JRadioButtonMenuItem r = new JRadioButtonMenuItem(o, "10 minutes".equals(o));
                tg.add(r); timeout.add(r);
            }
            for (JMenuItem it : new JMenuItem[]{ucl, unpack, follow, cookies, enforce, normalize, h1reuse, h2reuse, strip, alpn, timeout}) { it.setFont(Ui.PLAIN); m.add(it); }
            return m;
        }

        /** A dropdown of this tab's history around the current position. */
        private void historyMenu(Component anchor, int dir) {
            JPopupMenu m = new JPopupMenu();
            int from = dir < 0 ? historyPos - 1 : historyPos + 1;
            for (int i = from; i >= 0 && i < history.size(); i += dir) {
                final int idx = i;
                Snapshot sn = history.get(i);
                String first = new String(sn.request(), java.nio.charset.StandardCharsets.ISO_8859_1).split("\\r?\\n", 2)[0];
                JMenuItem mi = new JMenuItem((i + 1) + ". " + Ui.truncate(first, 60));
                mi.setFont(Ui.PLAIN);
                mi.addActionListener(e -> navigate(idx - historyPos));
                m.add(mi);
            }
            if (m.getComponentCount() == 0) { JMenuItem none = new JMenuItem("No " + (dir < 0 ? "earlier" : "later") + " requests"); none.setEnabled(false); m.add(none); }
            m.show(anchor, 0, anchor.getHeight());
        }

        private Boolean tabUpdateCl;
        private String tabFollow;

        // Per-tab history of sends, with a browser-style position for back/forward.
        private record Snapshot(HttpService svc, byte[] request, HttpMessage response, String status, int length) {}
        private final java.util.List<Snapshot> history = new java.util.ArrayList<>();
        private int historyPos = -1;

        RepeaterTab(HttpService svc, HttpMessage req) {
            super(new BorderLayout());
            reqEditor = new MessageEditor(ctx, MessageEditor.REQUEST, true);
            respEditor = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
            reqEditor.setMessage(svc, req);

            // Layout, as in the reference: Send, gear, Cancel, back / forward with
            // history menus and the AI-free toolbar on the left; the target with
            // its edit pencil, the HTTP version chip and help on the right.
            cancel.setEnabled(false);
            hostField = Ui.field(svc.host(), 16);
            portField = Ui.field(String.valueOf(svc.port()), 5);
            sslBox = Ui.check("SSL", svc.secure());
            prev.setEnabled(false);
            next.setEnabled(false);
            prev.setIcon(opentrap.ui.Glyphs.chevron(14, opentrap.ui.Theme.ICON, 3));
            next.setIcon(opentrap.ui.Glyphs.chevron(14, opentrap.ui.Theme.ICON, 0));
            prev.setText(""); next.setText("");
            prev.addActionListener(e -> navigate(-1));
            next.addActionListener(e -> navigate(1));
            JButton prevMenu = opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.chevron(12, opentrap.ui.Theme.ICON, 1), "Earlier requests in this tab", null);
            JButton nextMenu = opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.chevron(12, opentrap.ui.Theme.ICON, 1), "Later requests in this tab", null);
            prevMenu.addActionListener(e -> historyMenu(prevMenu, -1));
            nextMenu.addActionListener(e -> historyMenu(nextMenu, 1));
            JButton gear = opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.gear(), "Repeater settings for this tab", null);
            gear.addActionListener(e -> tabSettingsMenu().show(gear, 0, gear.getHeight()));
            modeCombo.setToolTipText("HTTP/1 is raw and byte-exact; HTTP/2 and auto use the JDK transport");

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
            left.add(go);
            left.add(gear);
            left.add(cancel);
            left.add(prev); left.add(prevMenu);
            left.add(next); left.add(nextMenu);
            left.add(histLabel);

            targetLabel = Ui.bold("");
            refreshTarget();
            JButton pencil = opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.pencil(), "Configure target details", this::configureTarget);
            JButton help = opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.help(), "About Repeater", () -> JOptionPane.showMessageDialog(this,
                    "Edit the request on the left and press Send; the response arrives on the right.\n"
                  + "The arrows step through this tab's history; the pencil changes the target.", "Repeater", JOptionPane.INFORMATION_MESSAGE));
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
            right.add(targetLabel);
            right.add(pencil);
            right.add(modeCombo);
            right.add(help);

            JPanel north = new JPanel(new BorderLayout());
            north.add(left, BorderLayout.WEST);
            north.add(right, BorderLayout.EAST);
            add(north, BorderLayout.NORTH);

            JPanel reqPanel = new JPanel(new BorderLayout());
            reqPanel.add(bold(" Request"), BorderLayout.NORTH);
            reqPanel.add(reqEditor, BorderLayout.CENTER);
            JPanel respPanel = new JPanel(new BorderLayout());
            respPanel.add(bold(" Response"), BorderLayout.NORTH);
            respPanel.add(respEditor, BorderLayout.CENTER);
            // Request and response side by side.
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqPanel, respPanel);
            split.setResizeWeight(0.5);
            split.addComponentListener(new java.awt.event.ComponentAdapter() {
                private boolean set = false;
                @Override public void componentResized(java.awt.event.ComponentEvent e) {
                    if (!set && split.getWidth() > 0) { set = true; split.setDividerLocation(0.5); }
                }
            });
            split.setDividerSize(8);
            add(split, BorderLayout.CENTER);

            JPanel south = new JPanel(new BorderLayout());
            south.setBorder(Ui.pad(2, 6, 2, 6));
            south.add(status, BorderLayout.CENTER);
            south.add(lengthLabel, BorderLayout.EAST);
            add(south, BorderLayout.SOUTH);

            go.addActionListener(e -> send());
            cancel.addActionListener(e -> { if (worker != null) worker.interrupt(); });
        }

        private JLabel bold(String s) {
            JLabel l = Ui.bold(s);
            l.setBorder(Ui.pad(3, 2, 3, 2));
            return l;
        }

        private void send() {
            HttpService svc;
            try {
                svc = new HttpService(hostField.getText().trim(),
                        Integer.parseInt(portField.getText().trim()), sslBox.isSelected());
            } catch (NumberFormatException ex) {
                status.setText("bad port number");
                return;
            }
            HttpMessage req = new HttpMessage(reqEditor.bytes());
            if ((tabUpdateCl != null ? tabUpdateCl : updateContentLength) && req.body().length > 0) req.updateContentLength();

            status.setText("waiting");
            go.setEnabled(false);
            cancel.setEnabled(true);

            worker = new Thread(() -> {
                HttpClient.Options opt = new HttpClient.Options();
                opt.updateContentLength = tabUpdateCl != null ? tabUpdateCl : updateContentLength;
                opt.followRedirects = tabFollow != null ? !"Never".equals(tabFollow) : followRedirects;
                opt.unpackGzip = true;
                opt.session = ctx.sessions();
                opt.sessionTool = "repeater";
                opt.mode = (HttpMode) modeCombo.getSelectedItem();
                long t0 = System.currentTimeMillis();
                HttpClient.Result r = new HttpClient().send(svc, req, opt);
                long ms = System.currentTimeMillis() - t0;
                SwingUtilities.invokeLater(() -> {
                    go.setEnabled(true);
                    cancel.setEnabled(false);
                    if (!r.ok()) {
                        status.setText(r.error);
                        return;
                    }
                    respEditor.setMessage(svc, r.response, req);
                    int redirs = r.redirectsFollowed;
                    String statusText = "done" + (redirs > 0 ? " (followed " + redirs + " redirect"
                            + (redirs == 1 ? "" : "s") + ")" : "") + "  " + ms + " ms";
                    status.setText(statusText);
                    lengthLabel.setText("length: " + r.response.length());
                    pushHistory(new Snapshot(svc, req.raw().clone(), r.response, statusText, r.response.length()));

                    LogEntry entry = new LogEntry("repeater", svc, req);
                    entry.setResponse(r.response);
                    entry.setRemoteIp(r.remoteIp);
                    entry.setDurationMillis(ms);
                    ctx.store().add(entry);
                    ctx.siteMapChanged(entry);
                });
            }, "repeater-send");
            worker.setDaemon(true);
            worker.start();
        }

        /** Append a send to this tab's history, truncating any forward entries. */
        private void pushHistory(Snapshot s) {
            while (history.size() > historyPos + 1) history.remove(history.size() - 1);
            history.add(s);
            historyPos = history.size() - 1;
            updateNav();
        }

        private void navigate(int delta) {
            int target = historyPos + delta;
            if (target < 0 || target >= history.size()) return;
            historyPos = target;
            Snapshot s = history.get(historyPos);
            hostField.setText(s.svc().host());
            portField.setText(String.valueOf(s.svc().port()));
            sslBox.setSelected(s.svc().secure());
            refreshTarget();
            reqEditor.setMessage(s.svc(), new HttpMessage(s.request().clone()));
            if (s.response() != null) {
                respEditor.setMessage(s.svc(), s.response(), new HttpMessage(s.request().clone()));
                lengthLabel.setText("length: " + s.length());
            } else {
                respEditor.setMessage(s.svc(), new HttpMessage(new byte[0]));
            }
            status.setText(s.status());
            updateNav();
        }

        private void updateNav() {
            prev.setEnabled(historyPos > 0);
            next.setEnabled(historyPos >= 0 && historyPos < history.size() - 1);
            histLabel.setText(history.isEmpty() ? "" : (historyPos + 1) + "/" + history.size());
        }
    }
}
