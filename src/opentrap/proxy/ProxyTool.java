package opentrap.proxy;

import opentrap.core.*;
import opentrap.ui.*;

import javax.swing.*;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.nio.file.Path;
import java.util.List;

/** The proxy tool: intercept, HTTP history, and options. */
public class ProxyTool implements ToolModule {

    private ProxyServer server;
    private SuiteContext ctx;

    private InterceptPanel interceptPanel;
    private HistoryPanel historyPanel;

    public ProxyTool() {}

    public ProxyServer server() { return server; }

    @Override public String name() { return "proxy"; }
    @Override public boolean alwaysTabbed() { return true; }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        try {
            Path home = Path.of(System.getProperty("user.home"), ".opentrap");
            this.server = new ProxyServer(ctx, home.resolve("ca"));
        } catch (Exception e) {
            ctx.alert("proxy", "failed to initialise proxy: " + e.getMessage());
            return;
        }
        server.unpackGzip = ctx.settings().getBool("http.unpackGzip", true);
        // Out-of-scope logging is a project setting, so the banner, the settings
        // pane and a restored project all drive the one flag on the server.
        // The flag is a preference as well, so it can arrive from an earlier
        // session; with no scope defined it has nothing to apply to, and would
        // otherwise read as "record nothing", so it is cleared.
        if (ctx.scope().isEmpty() && !ProjectOptions.logOutOfScope(ctx.settings())) {
            ctx.settings().setBool(ProjectOptions.LOG_OUT_OF_SCOPE, true);
        }
        server.logOutOfScope = ProjectOptions.logOutOfScope(ctx.settings());
        ctx.settings().addListener(key -> {
            if (ProjectOptions.LOG_OUT_OF_SCOPE.equals(key)) server.logOutOfScope = ProjectOptions.logOutOfScope(ctx.settings());
        });
        lastIncludeCount = ctx.scope().include().size();
        ctx.addScopeListener(this::scopeEdited);
        server.startAll();
        // Publish routing info for the browser crawler.
        try {
            for (ProxyListener l : server.listeners()) if (l.running) { ctx.setProxyPort(l.port); break; }
            ctx.setCaSpki(server.ca().spkiFingerprint());
            ctx.setBrowserBinary(opentrap.proxy.EmbeddedBrowser.findBinary());
        } catch (Exception e) { Debug.log("could not publish proxy routing info: " + e); }
    }

    @Override public void shutdown() { server.stopAll(); }

    private volatile int lastIncludeCount;

    /**
     * When an item is added to the scope while out-of-scope traffic is still
     * being recorded, offer to stop recording it, as the reference does. The
     * answer can be remembered ("always take the same action in future"), and
     * is then applied without asking; the choice lives under Settings >
     * Project. A scope arriving from an opened project never asks.
     */
    private void scopeEdited() {
        int now = ctx.scope().include().size();
        boolean grew = now > lastIncludeCount;
        lastIncludeCount = now;
        // The last include rule gone: there is no scope to restrict logging to.
        if (now == 0 && !ProjectOptions.logOutOfScope(ctx.settings())) {
            ctx.settings().setBool(ProjectOptions.LOG_OUT_OF_SCOPE, true);
        }
        if (!grew || ctx.restoring() || server == null || !server.logOutOfScope) return;
        String choice = ProjectOptions.outOfScopeChoice(ctx.settings());
        if ("keep".equals(choice)) return;
        if ("stop".equals(choice)) { ctx.settings().setBool(ProjectOptions.LOG_OUT_OF_SCOPE, false); return; }
        SwingUtilities.invokeLater(this::askOutOfScope);
    }

    private void askOutOfScope() {
        if (!server.logOutOfScope) return;
        JTextArea text = Ui.note(
                "You have added an item to the target scope. Do you want the Proxy to stop sending "
                + "out-of-scope items to the history and the other tools?\n\n"
                + "Answering \"yes\" avoids accumulating project data for out-of-scope items.");
        Ui.sizeNote(text, 440);
        JCheckBox always = Ui.check("Always take the same action in future", false);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.add(text, BorderLayout.CENTER);
        body.add(always, BorderLayout.SOUTH);
        int r = JOptionPane.showOptionDialog(ctx.frame(), body, "Proxy history logging",
                JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null,
                new Object[]{"Yes", "No"}, "Yes");
        if (r == JOptionPane.CLOSED_OPTION) return;
        boolean stop = r == JOptionPane.YES_OPTION;
        if (always.isSelected()) ctx.settings().set(ProjectOptions.OUT_OF_SCOPE_CHOICE, stop ? "stop" : "keep");
        if (stop) {
            ctx.settings().setBool(ProjectOptions.LOG_OUT_OF_SCOPE, false);
            ctx.alert("proxy", "no longer recording out-of-scope traffic");
        }
    }

    /** The history filter travels with the project. */
    @Override public java.io.Serializable saveState() {
        return historyPanel == null ? null : historyPanel.filter.copy();
    }

    @Override public void restoreState(java.io.Serializable state) {
        if (historyPanel == null || !(state instanceof HistoryFilter f)) return;
        f.scope = ctx.scope();
        historyPanel.filter.copyFrom(f);
        SwingUtilities.invokeLater(historyPanel::applyFilter);
    }

    @Override public Tab[] tabs() {
        if (server == null) {
            try {
                Path home = Path.of(System.getProperty("user.home"), ".opentrap");
                server = new ProxyServer(ctx, home.resolve("ca"));
            } catch (Exception e) {
                // init() already reported this once and left the proxy for a
                // retry; if it still fails, degrade to an explanatory tab
                // rather than killing the whole suite at startup.
                ctx.alert("proxy", "failed to initialise proxy: " + e.getMessage());
                return new Tab[] { new Tab("proxy", Ui.note(
                        "The proxy could not start: " + e.getMessage()
                        + ". Fix the cause and restart OpenTRAP.")) };
            }
        }
        interceptPanel = new InterceptPanel(ctx, server);
        historyPanel = new HistoryPanel(ctx, server);
        // The proxy and browser settings live in the suite Settings dialog, so
        // each setting has one home; the tab here opens the dialog at Proxy.
        OptionsPanel options = new OptionsPanel(ctx, server);
        SettingsDialog.register("Proxy", () -> options);
        SettingsDialog.register("Browser", () -> new BrowserSettingsPanel(ctx));
        return new Tab[]{
            new Tab("Intercept", interceptPanel),
            new Tab("HTTP history", historyPanel),
            new Tab("WebSockets history", new opentrap.websocket.WebSocketsPanel(ctx)),
            new Tab("Match and replace", topAligned(new MatchReplacePanel(server))),
            new Tab("Proxy settings", SettingsDialog.launcher(ctx, "Proxy", "Proxy settings",
                    "Listeners, interception, response modification and the upstream proxy "
                    + "are set under Settings > Proxy. The testing browser has its own "
                    + "Settings > Browser pane."))
        };
    }

    /** Hug a settings page to the top of its tab instead of stretching it to fill. */
    private static JComponent topAligned(JComponent page) {
        JPanel holder = new JPanel(new BorderLayout());
        holder.add(page, BorderLayout.NORTH);
        return Ui.scroll(holder);
    }

    // ---------------- intercept tab ----------------

    /**
     * The Intercept tab, laid out like the reference: a toolbar with the
     * intercept toggle, Forward, Drop and Action on the left and Open browser on
     * the right; below it either a centred idle card (intercept state, what it
     * does, the listener status and an Open browser button) or, while a message
     * is held, the message editor.
     */
    static class InterceptPanel extends JPanel implements InterceptQueue.Presenter {
        private final ProxyServer server;
        private final MessageEditor editor;
        private final JToggleButton toggle;
        private final JButton forward, drop, action;
        private final JLabel status = Ui.label(" ");
        private final CardLayout cards = new CardLayout();
        private final JPanel body = new JPanel(cards);
        private final JLabel idleTitle = Ui.heading("Intercept is off");
        private final JTextArea idleText = Ui.note("");
        private final JLabel idleListener = Ui.label(" ");
        private InterceptQueue.Pending current;

        InterceptPanel(SuiteContext ctx, ProxyServer server) {
            super(new BorderLayout());
            this.server = server;
            this.editor = new MessageEditor(ctx, MessageEditor.REQUEST | MessageEditor.RESPONSE, true);

            Runnable launchBrowser = () -> {
                boolean relax = ctx.settings().getBool(BrowserFlags.RELAX_KEY, true);
                String err = EmbeddedBrowser.launch(server, "http://opentrap/", relax);
                if (err != null) {
                    JOptionPane.showMessageDialog(this, err, "Open browser",
                            JOptionPane.WARNING_MESSAGE);
                } else {
                    ctx.alert("proxy", "launched browser through the proxy, "
                            + BrowserFlags.describe(relax));
                }
            };

            // Toolbar.
            toggle = new JToggleButton("Intercept off", false);
            toggle.setFont(Ui.PLAIN);
            toggle.setToolTipText("toggle message interception");
            forward = Ui.button("Forward", "forward the displayed message");
            drop = Ui.button("Drop", "drop the displayed message");
            action = Ui.button("Action", "perform additional actions");
            forward.setEnabled(false); drop.setEnabled(false); action.setEnabled(false);
            status.setForeground(Theme.MUTED);

            JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            left.setOpaque(false);
            left.add(toggle); left.add(forward); left.add(drop); left.add(action);
            left.add(Box.createHorizontalStrut(8));
            left.add(status);
            JButton openBrowser = Ui.button("Open browser",
                    "launch a browser routed through the proxy, with the CA already trusted");
            openBrowser.addActionListener(ev -> launchBrowser.run());
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            right.setOpaque(false);
            right.add(openBrowser);
            JPanel bar = new JPanel(new BorderLayout());
            bar.setOpaque(false);
            bar.setBorder(Ui.pad(6, 8, 6, 8));
            bar.add(left, BorderLayout.WEST);
            bar.add(right, BorderLayout.EAST);

            // Idle card: centred column.
            JPanel column = new JPanel();
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
            column.setOpaque(false);
            idleTitle.setFont(new Font("Dialog", Font.BOLD, 15));
            idleTitle.setAlignmentX(Component.CENTER_ALIGNMENT);
            idleText.setAlignmentX(Component.CENTER_ALIGNMENT);
            idleText.setMaximumSize(new Dimension(520, Integer.MAX_VALUE));
            idleListener.setForeground(Theme.MUTED);
            idleListener.setAlignmentX(Component.CENTER_ALIGNMENT);
            JButton idleBrowser = Ui.primaryButton("Open browser",
                    "launch a browser routed through the proxy, with the CA already trusted");
            idleBrowser.addActionListener(ev -> launchBrowser.run());
            idleBrowser.setAlignmentX(Component.CENTER_ALIGNMENT);
            column.add(idleTitle);
            column.add(Box.createVerticalStrut(12));
            column.add(idleText);
            column.add(Box.createVerticalStrut(8));
            column.add(idleListener);
            column.add(Box.createVerticalStrut(16));
            column.add(idleBrowser);
            JPanel idle = new JPanel(new GridBagLayout());
            idle.setOpaque(false);
            idle.add(column, new GridBagConstraints());

            body.setOpaque(false);
            body.add(idle, "idle");
            body.add(editor, "message");

            add(bar, BorderLayout.NORTH);
            add(body, BorderLayout.CENTER);

            forward.addActionListener(e -> resolve(InterceptQueue.Action.FORWARD));
            drop.addActionListener(e -> resolve(InterceptQueue.Action.DROP));
            toggle.addActionListener(e -> {
                boolean on = toggle.isSelected();
                toggle.setText(on ? "Intercept on" : "Intercept off");
                server.interceptingEnabled = on;
                if (!on) server.interceptQueue().releaseAll();
                if (!on || current == null) showIdle();
            });
            action.addActionListener(e -> editor.buildMenu().show(action, 0, action.getHeight()));

            server.interceptQueue().setPresenter(this);
            server.setObserver(new ProxyServer.Observer() {
                public void exchangeLogged(LogEntry entry) {}
                public void statusChanged(String text) {}
            });
            // Keep the port on screen: refresh the idle card whenever a listener
            // starts or stops, unless an intercepted message is being shown.
            server.onListenersChanged(() -> { if (current == null) showIdle(); });
            showIdle();
        }

        private void showIdle() {
            SwingUtilities.invokeLater(() -> {
                current = null;
                boolean on = toggle.isSelected();
                idleTitle.setText(on ? "Intercept is on" : "Intercept is off");
                idleText.setText(on
                        ? "Waiting for a message. Requests and responses passing through the "
                          + "proxy will be held here for you to review, edit, forward or drop."
                        : "If you turn Intercept on, messages between your browser and your "
                          + "target servers are held here. This lets you analyse and modify "
                          + "them before you forward them.");
                idleListener.setText(server.listeningSummary());
                status.setText(" ");
                editor.setMessage(null, new HttpMessage(new byte[0]));
                forward.setEnabled(false); drop.setEnabled(false); action.setEnabled(false);
                cards.show(body, "idle");
            });
        }

        @Override public void present(InterceptQueue.Pending p) {
            SwingUtilities.invokeLater(() -> {
                current = p;
                status.setText((p.isRequest ? "Request to " : "Response from ") + p.service.url());
                editor.setMessage(p.service, p.message, p.relatedRequest);
                forward.setEnabled(true); drop.setEnabled(true); action.setEnabled(true);
                cards.show(body, "message");
            });
        }

        @Override public void cleared(InterceptQueue.Pending p) {
            showIdle();
        }

        private void resolve(InterceptQueue.Action a) {
            InterceptQueue.Pending p = current;
            if (p == null) return;
            byte[] edited = a == InterceptQueue.Action.FORWARD ? editor.bytes() : null;
            // Release this message. completed() pumps the next queued item (if any)
            // and drives the button state via cleared()/present(); do not force idle
            // here or it would wipe the next request that was just presented.
            p.resolve(a, edited);
            server.interceptQueue().completed(p);
        }
    }

    // ---------------- history tab ----------------

    static class HistoryPanel extends JPanel {
        private final SuiteContext ctx;
        private final HistoryModel model;
        private final JTable table;
        private final MessageEditor reqView, respView;
        final HistoryFilter filter = HistoryFilter.proxyDefaults();
        private final JButton filterButton = Ui.filterButton();

        HistoryPanel(SuiteContext ctx, ProxyServer server) {
            super(new BorderLayout());
            this.ctx = ctx;
            this.model = new HistoryModel("proxy");
            this.filter.scope = ctx.scope();
            this.table = new JTable(model);
            this.reqView = new MessageEditor(ctx, MessageEditor.REQUEST, false);
            this.respView = new MessageEditor(ctx, MessageEditor.RESPONSE, false);

            Ui.styleTable(table);
            // #, host, method, URL, params, edited, status, length, MIME, ext, title, SSL, IP, time
            Ui.distributeWidths(table, 40, 170, 55, 300, 55, 55, 60, 70, 90, 75, 150, 45, 110, 120);
            Ui.centerCells(table);
            table.setRowSorter(new TableRowSorter<>(model));
            Ui.newestFirst(table, 0);

            JPanel top = new JPanel(new BorderLayout());
            top.add(new OutOfScopeBanner(ctx), BorderLayout.NORTH);
            top.add(buildFilterBar(), BorderLayout.CENTER);
            add(top, BorderLayout.NORTH);

            JSplitPane detail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqView, respView);
            detail.setDividerSize(5);
            Ui.balance(detail, 0.5);
            JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(table), detail);
            split.setDividerSize(5);
            Ui.balance(split, 0.45);
            add(split, BorderLayout.CENTER);

            table.getSelectionModel().addListSelectionListener(e -> showSelected());
            new HistoryContextMenu(ctx, table, model).install();

            server.setObserver(new ProxyServer.Observer() {
                public void exchangeLogged(LogEntry entry) {
                    SwingUtilities.invokeLater(() -> model.add(entry));
                }
                public void statusChanged(String text) {}
            });
            // A restored project replaces the history wholesale: reload the
            // table once from the store rather than leaving it empty.
            ctx.store().addListener(new MessageStore.Listener() {
                @Override public void entryAdded(LogEntry e) {}
                @Override public void entryUpdated(LogEntry e) {}
                @Override public void reloaded(java.util.List<LogEntry> all) {
                    SwingUtilities.invokeLater(() -> model.reload(all));
                }
            });

            ctx.addScopeListener(() -> { filter.scope = ctx.scope(); applyFilter(); });
            applyFilter();
        }

        private JPanel buildFilterBar() {
            // A clickable filter bar that opens the full filter popup, with the
            // fast URL search beside it, both at one uniform height.
            filterButton.setText("Filter settings: " + filter.summary());
            filterButton.addActionListener(e -> openFilter());
            JTextField urlSearch = Ui.field("", 22);
            urlSearch.setToolTipText("quick search of the URL only");
            urlSearch.getDocument().addDocumentListener(new SimpleDoc(() -> {
                filter.urlSearch = urlSearch.getText(); applyFilter();
            }));
            return Ui.filterBar(filterButton, Ui.label("Search:"), urlSearch);
        }

        private void openFilter() {
            Window owner = SwingUtilities.getWindowAncestor(this);
            new FilterDialog(owner, "HTTP history filter", filter, false, this::applyFilter).setVisible(true);
        }

        void applyFilter() {
            model.setFilter(filter::test);
            filterButton.setText("Filter settings: " + filter.summary());
        }

        private void showSelected() {
            int row = table.getSelectedRow();
            if (row < 0) return;
            LogEntry e = model.at(table.convertRowIndexToModel(row));
            if (e == null) return;
            reqView.showRequest(e);
            respView.showResponse(e);
        }
    }

    // ---------------- options tab ----------------

    static class OptionsPanel extends JPanel {
        OptionsPanel(SuiteContext ctx, ProxyServer server) {
            super(new BorderLayout());
            OptionsPage page = new OptionsPage();

            page.section("Proxy listeners", new ListenersPanel(ctx, server));

            JCheckBox interceptReq = Ui.check("intercept client requests", server.interceptRequests);
            JCheckBox interceptResp = Ui.check("intercept server responses", server.interceptResponses);
            interceptReq.addActionListener(e -> server.interceptRequests = interceptReq.isSelected());
            interceptResp.addActionListener(e -> server.interceptResponses = interceptResp.isSelected());
            page.section("intercept", interceptReq, interceptResp);

            JCheckBox unhide = Ui.check("unhide hidden form fields", false);
            JCheckBox enable = Ui.check("enable disabled form fields", false);
            JCheckBox limits = Ui.check("remove input field length limits", false);
            JCheckBox jsval = Ui.check("remove JavaScript form validation", false);
            JCheckBox alljs = Ui.check("remove all JavaScript", false);
            JCheckBox objs = Ui.check("remove object tags", false);
            JCheckBox stripSec = Ui.check(
                    "strip response security headers (CSP, X-Frame-Options) for PoC",
                    server.stripSecurityHeaders);
            unhide.addActionListener(e -> server.unhideHiddenFields = unhide.isSelected());
            enable.addActionListener(e -> server.enableDisabledFields = enable.isSelected());
            limits.addActionListener(e -> server.removeLengthLimits = limits.isSelected());
            jsval.addActionListener(e -> server.removeJsValidation = jsval.isSelected());
            alljs.addActionListener(e -> server.removeAllJavaScript = alljs.isSelected());
            objs.addActionListener(e -> server.removeObjectTags = objs.isSelected());
            stripSec.addActionListener(e -> {
                server.stripSecurityHeaders = stripSec.isSelected();
                ctx.alert("proxy", stripSec.isSelected()
                        ? "stripping response security headers for the browser; the scanner still "
                          + "audits the server's original response"
                        : "no longer stripping response security headers");
            });
            JTextArea stripNote = Ui.note(
                "Stripping security headers lets an injected payload actually run in the browser, so "
                + "you can build and show a working proof of concept. It affects only what the "
                + "browser receives; the scanner always audits the server's original response, so a "
                + "stripped header is never mistaken for a missing one.");
            page.section("response modification", unhide, enable, limits, jsval, alljs, objs, stripSec, stripNote);

            JCheckBox http10 = Ui.check("talk HTTP/1.0 to the server", server.http10);
            JCheckBox unpack = Ui.check("unpack gzip / deflate", server.unpackGzip);
            JCheckBox logoos = Ui.check("log out-of-scope traffic", server.logOutOfScope);
            http10.addActionListener(e -> server.http10 = http10.isSelected());
            unpack.addActionListener(e -> server.unpackGzip = unpack.isSelected());
            logoos.addActionListener(e -> ctx.settings().setBool(ProjectOptions.LOG_OUT_OF_SCOPE, logoos.isSelected()));
            ctx.settings().addListener(key -> {
                if (ProjectOptions.LOG_OUT_OF_SCOPE.equals(key))
                    SwingUtilities.invokeLater(() -> logoos.setSelected(ProjectOptions.logOutOfScope(ctx.settings())));
            });
            JTextArea logoosNote = Ui.note(
                    "Off, the proxy still passes everything through but records only in-scope traffic. "
                    + "What to do when an item is added to scope is set under Settings > Project.");
            page.section("misc", http10, unpack, logoos, logoosNote);

            // Upstream proxy chaining: a titled section like the others.
            page.section("Upstream proxy servers", new UpstreamProxyPanel(ctx));

            add(page, BorderLayout.CENTER);
        }
    }

    @Override public JMenu[] menus() { return null; }

    // small helper
    static class SimpleDoc implements javax.swing.event.DocumentListener {
        private final Runnable r;
        SimpleDoc(Runnable r) { this.r = r; }
        public void insertUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }
}
