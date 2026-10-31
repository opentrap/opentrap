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
        private volatile Thread worker;

        // Per-tab history of sends, with a browser-style position for back/forward.
        private record Snapshot(HttpService svc, byte[] request, HttpMessage response, String status, int length) {}
        private final java.util.List<Snapshot> history = new java.util.ArrayList<>();
        private int historyPos = -1;

        RepeaterTab(HttpService svc, HttpMessage req) {
            super(new BorderLayout());
            reqEditor = new MessageEditor(ctx, MessageEditor.REQUEST, true);
            respEditor = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
            reqEditor.setMessage(svc, req);

            // Layout: send controls on the left, the target on the right.
            cancel.setEnabled(false);
            hostField = Ui.field(svc.host(), 16);
            portField = Ui.field(String.valueOf(svc.port()), 5);
            sslBox = Ui.check("SSL", svc.secure());
            prev.setEnabled(false);
            next.setEnabled(false);
            prev.addActionListener(e -> navigate(-1));
            next.addActionListener(e -> navigate(1));
            modeCombo.setToolTipText("HTTP/1 is raw and byte-exact; HTTP/2 and auto use the JDK transport");

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
            left.add(go);
            left.add(cancel);
            left.add(prev);
            left.add(next);
            left.add(histLabel);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
            right.add(Ui.bold("Target:"));
            right.add(hostField);
            right.add(Ui.label("port"));
            right.add(portField);
            right.add(sslBox);
            right.add(modeCombo);

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

            sslBox.addActionListener(e -> {
                String p = portField.getText().trim();
                if (p.equals("80") && sslBox.isSelected()) portField.setText("443");
                else if (p.equals("443") && !sslBox.isSelected()) portField.setText("80");
            });
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
            if (updateContentLength && req.body().length > 0) req.updateContentLength();

            status.setText("waiting");
            go.setEnabled(false);
            cancel.setEnabled(true);

            worker = new Thread(() -> {
                try {
                    HttpClient.Options opt = new HttpClient.Options();
                    opt.updateContentLength = updateContentLength;
                    opt.followRedirects = followRedirects;
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
                } catch (Exception e) {
                    // Without this the Go button stays disabled and the tab
                    // reads "waiting" forever; nothing else would report it.
                    Debug.error("repeater request failed", e);
                    SwingUtilities.invokeLater(() -> {
                        go.setEnabled(true);
                        cancel.setEnabled(false);
                        status.setText("failed: " + e.getMessage());
                    });
                }
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
