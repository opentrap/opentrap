package opentrap.ui;

import opentrap.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite shell. It knows nothing about any individual tool: it asks each
 * registered module for a name, its tabs and its menus, and renders those.
 */
public class SuiteFrame extends JFrame {

    private final SuiteContext ctx;
    /** Width reserved at the right of the tab row for the tool icons. */
    private static final int TAB_ROW_RESERVE = 124;

    /**
     * The main tab strip. The right end of its tab row is kept clear for the
     * top-right tool icons (browser, console, search, settings), as in the
     * reference. The reserve is an empty right border, which is what the
     * scrolling tab layout sizes its tab viewport and scroll buttons from; the
     * content area is widened back by the same amount so pages still fill the
     * window. Re-applied on updateUI so it survives a theme switch.
     */
    private final JTabbedPane tabs = new JTabbedPane() {
        @Override public void updateUI() {
            setUI(new opentrap.ui.theme.FlatTabbedPaneUI() {
                @Override protected void installDefaults() {
                    super.installDefaults();
                    contentBorderInsets = new Insets(contentBorderInsets.top, contentBorderInsets.left,
                            contentBorderInsets.bottom, -TAB_ROW_RESERVE);
                }
            });
            setBorder(BorderFactory.createEmptyBorder(0, 0, 0, TAB_ROW_RESERVE));
        }
    };
    private final JMenuBar menuBar = new JMenuBar();
    private final List<ToolModule> modules = new ArrayList<>();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel memoryLabel = new JLabel();
    private final MemoryMeter memoryMeter = new MemoryMeter();
    private final JButton eventLogLabel = new JButton("Event log (0)");
    private final JButton issuesLabel = new JButton("All issues (0)");
    private AlertsPanel alertsPanel;
    private opentrap.session.SessionPanel sessionPanel;   // Settings > Sessions, built on first use
    private ConfigLibraryPanel configLibrary;             // Settings > Configuration library

    public SuiteFrame(SuiteContext ctx, String title) {
        super(title);
        this.ctx = ctx;
        ctx.setFrame(this);
        // Suite-level settings panes. Tools register their own in init().
        SettingsDialog.register("Sessions", () -> {
            if (sessionPanel == null) sessionPanel = new opentrap.session.SessionPanel(ctx);
            sessionPanel.refreshAll();
            return sessionPanel;
        });
        SettingsDialog.register("Configuration library", () -> {
            if (configLibrary == null) configLibrary = new ConfigLibraryPanel();
            configLibrary.reload();
            return configLibrary;
        });
        SettingsDialog.register("Project", () -> new ProjectSettingsPanel(ctx));
        // The audit traffic cap follows its setting, including one restored
        // from a project.
        ctx.store().setAuditCapBytes(opentrap.core.ProjectOptions.auditMemoryBytes(ctx.settings()));
        ctx.settings().addListener(key -> {
            if (opentrap.core.ProjectOptions.AUDIT_MEMORY_MB.equals(key))
                ctx.store().setAuditCapBytes(opentrap.core.ProjectOptions.auditMemoryBytes(ctx.settings()));
        });
        setIconImages(AppIcon.images());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        tabs.setFont(Ui.PLAIN);
        // One compact row, as in the reference: tabs keep their natural width and
        // never wrap onto a second row (which would stretch them to fill the
        // width). On a narrow window the row scrolls instead.
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        setLayout(new BorderLayout());
        add(buildCenter(), BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        setJMenuBar(menuBar);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { confirmExit(); }
        });

        // A session bound to a project file is written back every 30 seconds
        // when something changed (in the background, atomically), so a crash
        // costs half a minute at most. Writing on every request is not an
        // option: each save rewrites the whole file.
        Timer autosave = new Timer(30_000, e -> StateIO.autosave(ctx, size -> setStatus(
                "Project saved at " + java.time.LocalTime.now().withNano(0) + ", "
                + ProjectSizeDialog.human(size) + " on disk")));
        autosave.setRepeats(true);
        autosave.start();

        Timer t = new Timer(2000, e -> updateMemory());
        t.start();
        updateMemory();
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.SURFACE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID), Ui.pad(2, 10, 2, 10)));
        statusLabel.setFont(Ui.PLAIN);
        memoryLabel.setFont(Ui.PLAIN);
        memoryLabel.setForeground(Theme.MUTED);

        linkify(eventLogLabel, () -> selectTool("alerts"));
        linkify(issuesLabel, () -> selectTool("dashboard"));

        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        west.setOpaque(false);
        west.add(eventLogLabel);
        west.add(issuesLabel);
        west.add(statusLabel);
        p.add(west, BorderLayout.WEST);

        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        east.setOpaque(false);
        east.add(memoryLabel);
        east.add(memoryMeter);
        p.add(east, BorderLayout.EAST);

        // Live counters for the status bar.
        ctx.addAlertListener(a -> SwingUtilities.invokeLater(() ->
                eventLogLabel.setText("Event log (" + ctx.alerts().size() + ")")));
        ctx.addIssueListener(i -> SwingUtilities.invokeLater(() ->
                issuesLabel.setText("All issues (" + ctx.issues().size() + ")")));
        return p;
    }

    /** Make a button read as a flat clickable link in the status bar. */
    private void linkify(JButton b, Runnable action) {
        b.setFont(Ui.PLAIN);
        b.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        b.setContentAreaFilled(false);
        b.setFocusable(false);
        b.setForeground(Theme.MUTED);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> action.run());
    }

    private void updateMemory() {
        Runtime r = Runtime.getRuntime();
        long used = (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
        long max = r.maxMemory() / (1024 * 1024);
        memoryLabel.setText("Memory: " + used + "MB of " + max + "MB");
        memoryMeter.setFraction(max <= 0 ? 0 : (double) used / max);
    }

    /** A small rounded usage meter shown beside the memory readout. */
    private static final class MemoryMeter extends JComponent {
        private double fraction;
        MemoryMeter() {
            Dimension d = new Dimension(64, 10);
            setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
        }
        void setFraction(double f) {
            this.fraction = Math.max(0, Math.min(1, f));
            repaint();
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.SURFACE_3);
            g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            int fill = (int) Math.round(fraction * (w - 1));
            if (fill > 0) {
                g2.setColor(Theme.ACCENT);
                g2.fillRoundRect(0, 0, Math.max(h, fill), h - 1, h, h);
            }
            g2.setColor(Ui.GRID);
            g2.drawRoundRect(0, 0, w - 1, h - 1, h, h);
            g2.dispose();
        }
    }

    public void setStatus(String s) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(s == null ? " " : s));
    }

    /** Register a tool. Call before {@link #buildUi()}. */
    public void register(ToolModule m) {
        modules.add(m);
        ctx.registerTool(m);
    }

    /** Build the tab strip and menu bar from everything registered. */
    public void buildUi() {
        for (ToolModule m : modules) m.init(ctx);

        for (ToolModule m : modules) {
            ToolModule.Tab[] mt = m.tabs();
            JComponent comp;
            if (mt.length == 1 && !m.alwaysTabbed()) {
                comp = mt[0].component();
            } else {
                JTabbedPane inner = new JTabbedPane();
                inner.setFont(Ui.PLAIN);
                inner.setFocusable(false);
                for (ToolModule.Tab t : mt) inner.addTab(t.title(), t.component());
                comp = inner;
            }
            tabs.addTab(m.name(), comp);
        }

        // Suite-level tab after the tools. Settings used to live here as a "comms"
        // tab; they now live in the Settings dialog (the gear in the top bar), so
        // there is one home for them.
        alertsPanel = new AlertsPanel(ctx);
        tabs.addTab("alerts", alertsPanel);

        buildMenus();
    }

    private void buildMenus() {
        menuBar.removeAll();

        JMenu suite = new JMenu("OpenTRAP");
        suite.setFont(Ui.PLAIN);
        // Search is built into the places it belongs: the quick URL box and the
        // filter popup on the proxy history and the target site map. It is no
        // longer a separate window reached from this menu.
        addItem(suite, "settings", e -> SettingsDialog.open(this, ctx));
        suite.addSeparator();
        addItem(suite, "save state", e -> StateIO.saveDialog(ctx, this));
        addItem(suite, "restore state", e -> StateIO.restoreDialog(ctx, this));
        suite.addSeparator();
        addItem(suite, "session handling...", e -> SettingsDialog.open(this, ctx, "Sessions"));
        suite.addSeparator();
        addItem(suite, "exit", e -> confirmExit());
        menuBar.add(suite);

        JMenu project = new JMenu("Project");
        project.setFont(Ui.PLAIN);
        addItem(project, "project settings", e -> SettingsDialog.open(this, ctx, "Project"));
        addItem(project, "rename project", e -> renameProject());
        addItem(project, "save copy", e -> saveProjectCopy());
        addItem(project, "project size", e -> ProjectSizeDialog.show(this, ctx));
        addItem(project, "import project file", e -> StateIO.restoreDialog(ctx, this));
        menuBar.add(project);

        for (ToolModule m : modules) {
            JMenu[] ms = m.menus();
            if (ms == null) continue;
            for (JMenu x : ms) { x.setFont(Ui.PLAIN); x.setText(capitalise(x.getText())); menuBar.add(x); }
        }

        JMenu view = new JMenu("View");
        view.setFont(Ui.PLAIN);
        // Changing scale relaunches the JVM; the session is written to its
        // project file on the way past, exactly as on exit.
        JMenuItem bigger = new JMenuItem("increase text size");
        bigger.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bigger.addActionListener(e -> relaunchScaled(currentScale() + 0.25));
        JMenuItem smaller = new JMenuItem("decrease text size");
        smaller.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        smaller.addActionListener(e -> relaunchScaled(currentScale() - 0.25));
        JMenuItem reset = new JMenuItem("reset text size");
        reset.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_0,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        reset.addActionListener(e -> relaunchScaled(1.0));
        bigger.setFont(Ui.PLAIN); smaller.setFont(Ui.PLAIN); reset.setFont(Ui.PLAIN);
        view.add(bigger); view.add(smaller); view.add(reset);
        view.addSeparator();
        for (double f : new double[]{1.0, 1.25, 1.5, 2.0}) {
            final double ff = f;
            JMenuItem mi = new JMenuItem((int) (f * 100) + "%");
            mi.setFont(Ui.PLAIN);
            mi.addActionListener(e -> relaunchScaled(ff));
            view.add(mi);
        }
        menuBar.add(view);

        JMenu window = new JMenu("Window");
        window.setFont(Ui.PLAIN);
        for (int i = 0; i < modules.size(); i++) {
            final int idx = i;
            addItem(window, "go to " + modules.get(i).name(), e -> tabs.setSelectedIndex(idx));
        }
        menuBar.add(window);

        JMenu help = new JMenu("Help");
        help.setFont(Ui.PLAIN);
        addItem(help, "proxy setup instructions", e -> showProxyHelp());
        addItem(help, "roadmap", e -> selectTool("discover"));
        addItem(help, "about", e -> AboutDialog.show(this));
        menuBar.add(help);
    }

    /** Rename the project file this session is bound to, keeping its contents. */
    private void renameProject() {
        java.io.File current = ctx.projectFile();
        if (current == null) {
            JOptionPane.showMessageDialog(this,
                    "This session is not saved as a project yet. Use OpenTRAP > save state "
                    + "to give it a file, then rename it.", "Rename project", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String name = JOptionPane.showInputDialog(this, "New project name:", RecentProjects.baseName(current));
        if (name == null || name.isBlank()) return;
        name = name.trim();
        if (!name.toLowerCase().endsWith(StateIO.EXT)) name += StateIO.EXT;
        java.io.File target = new java.io.File(current.getParentFile(), name);
        if (target.equals(current)) return;
        if (target.exists()) {
            JOptionPane.showMessageDialog(this, "A project called " + target.getName() + " already exists.",
                    "Rename project", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            if (current.exists()) java.nio.file.Files.move(current.toPath(), target.toPath());
            ctx.setProjectFile(target);
            RecentProjects.record(RecentProjects.baseName(target), target);
            ctx.alert("suite", "Project renamed to " + target.getName());
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Could not rename the project: " + e.getMessage(),
                    "Rename project", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Write the current state to another file without rebinding the session to
     * it. First a small dialog chooses what the copy keeps: in-scope items only,
     * which tools' history, the scan cards and the audit bodies; it is also the
     * way to shrink a project, since a copy drops what the filters leave out.
     */
    private void saveProjectCopy() {
        StateIO.SaveOptions opts = StateIO.SaveOptions.fromSettings(ctx);
        JCheckBox inScope = Ui.check("Save in-scope items only", false);
        JCheckBox tasks = Ui.check("Include the scans on the dashboard", true);
        JCheckBox audit = Ui.check("Keep full audit responses", opts.keepAuditBodies);
        java.util.LinkedHashMap<String, JCheckBox> tools = new java.util.LinkedHashMap<>();
        for (opentrap.core.LogEntry e : ctx.store().all()) {
            tools.computeIfAbsent(e.tool(), t -> Ui.check(t, true));
        }
        JPanel toolBox = new JPanel();
        toolBox.setLayout(new BoxLayout(toolBox, BoxLayout.Y_AXIS));
        toolBox.setOpaque(false);
        for (JCheckBox b : tools.values()) { b.setAlignmentX(Component.LEFT_ALIGNMENT); toolBox.add(b); }
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(Ui.pad(4, 4, 4, 4));
        JLabel lead = Ui.label("Choose what the copy keeps.");
        lead.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(lead);
        body.add(Box.createVerticalStrut(10));
        inScope.setAlignmentX(Component.LEFT_ALIGNMENT); body.add(inScope);
        tasks.setAlignmentX(Component.LEFT_ALIGNMENT); body.add(tasks);
        audit.setAlignmentX(Component.LEFT_ALIGNMENT); body.add(audit);
        if (!tools.isEmpty()) {
            body.add(Box.createVerticalStrut(10));
            JLabel th = Ui.label("History from these tools:");
            th.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(th);
            toolBox.setBorder(Ui.pad(2, 16, 0, 0));
            toolBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            body.add(toolBox);
        }
        int r = JOptionPane.showOptionDialog(this, body, "Save copy", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, new Object[]{"Choose file...", "Cancel"}, "Choose file...");
        if (r != 0) return;
        opts.inScopeOnly = inScope.isSelected();
        opts.includeTasks = tasks.isSelected();
        opts.keepAuditBodies = audit.isSelected();
        if (!tools.isEmpty()) {
            opts.tools = new java.util.HashSet<>();
            for (var en : tools.entrySet()) if (en.getValue().isSelected()) opts.tools.add(en.getKey());
        }

        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("save copy");
        java.io.File current = ctx.projectFile();
        fc.setSelectedFile(current != null
                ? new java.io.File(current.getParentFile(), RecentProjects.baseName(current) + "-copy" + StateIO.EXT)
                : new java.io.File(StateIO.defaultProjectFileName()));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        java.io.File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(StateIO.EXT)) f = new java.io.File(f.getParentFile(), f.getName() + StateIO.EXT);
        if (f.exists() && JOptionPane.showConfirmDialog(this, "This file already exists. Overwrite it?",
                "save copy", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            StateIO.save(ctx, f, opts);
            ctx.alert("suite", "Copy saved to " + f.getName() + ", " + ProjectSizeDialog.human(f.length()));
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "Could not save the copy: " + e.getMessage(),
                    "save copy", JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * The tab strip with the top-right tool icons on its tab row, as in the
     * reference: a globe (browser), a console, a magnifier (search) and a gear
     * (settings). The tab UI reserves the right end of the row for them, so the
     * tabs wrap before that point and never sit under the icons. The browser,
     * console and search are not yet implemented; the gear opens Settings.
     */
    private JComponent buildCenter() {
        final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.setOpaque(false);
        tools.add(iconButton(ToolIcons.browser(), "Open browser", this::launchBrowser));
        tools.add(iconButton(ToolIcons.console(), "Console", () -> Unbuilt.CONSOLE.showDialog(this)));
        tools.add(iconButton(ToolIcons.search(), "Search", () -> new SearchWindow(ctx).setVisible(true)));
        tools.add(iconButton(ToolIcons.gear(), "Settings", () -> SettingsDialog.open(this, ctx)));

        final JLayeredPane layer = new JLayeredPane();
        layer.setLayout(null);
        layer.add(tabs, JLayeredPane.DEFAULT_LAYER);
        layer.add(tools, JLayeredPane.PALETTE_LAYER);
        layer.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int w = layer.getWidth(), h = layer.getHeight();
                tabs.setBounds(0, 0, w, h);
                tabs.doLayout();
                Dimension ts = tools.getPreferredSize();
                int rowH = tabs.getTabCount() > 0
                        ? tabs.getUI().getTabBounds(tabs, 0).height : ts.height;
                tools.setBounds(w - ts.width - 10, Math.max(0, (rowH - ts.height) / 2), ts.width, ts.height);
            }
        });
        return layer;
    }

    /**
     * Launch the bundled browser routed through the proxy, with the CA already
     * trusted: the same action as Open browser on the Proxy tab, reachable from
     * the globe in the top bar.
     */
    private void launchBrowser() {
        opentrap.proxy.ProxyServer server = null;
        for (ToolModule m : modules) {
            if (m instanceof opentrap.proxy.ProxyTool p) { server = p.server(); break; }
        }
        if (server == null) {
            JOptionPane.showMessageDialog(this, "The proxy is not running, so the browser cannot be launched.",
                    "Open browser", JOptionPane.WARNING_MESSAGE);
            return;
        }
        boolean relax = ctx.settings().getBool(opentrap.proxy.BrowserFlags.RELAX_KEY, true);
        String err = opentrap.proxy.EmbeddedBrowser.launch(server, "http://opentrap/", relax);
        if (err != null) {
            JOptionPane.showMessageDialog(this, err, "Open browser", JOptionPane.WARNING_MESSAGE);
        } else {
            ctx.alert("proxy", "launched browser through the proxy, "
                    + opentrap.proxy.BrowserFlags.describe(relax));
        }
    }

    /** A flat, icon-only button for the top-right tool strip. */
    private JButton iconButton(Icon icon, String tip, Runnable action) {
        JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setContentAreaFilled(false);
        b.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> action.run());
        return b;
    }

    private void showProxyHelp() {
        JOptionPane.showMessageDialog(this,
            "1. Set your browser's HTTP and HTTPS proxy to 127.0.0.1 port " + opentrap.proxy.ProxyListener.DEFAULT_PORT + ".\n" +
            "2. Browse to http://opentrap/ and install the CA certificate.\n" +
            "3. Trust it for identifying websites, then reload your target.\n\n" +
            "Traffic appears on the proxy history tab and builds the target site map.",
            "proxy setup", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Menu titles read as headings, like the tab strip: a leading capital. */
    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void addItem(JMenu menu, String label, java.awt.event.ActionListener l) {
        JMenuItem mi = new JMenuItem(label);
        mi.setFont(Ui.PLAIN);
        mi.addActionListener(l);
        menu.add(mi);
    }

    public void selectTool(String name) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getTitleAt(i).equals(name)) { tabs.setSelectedIndex(i); return; }
        }
    }

    public void flashTool(String name) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getTitleAt(i).equals(name)) {
                final int idx = i;
                tabs.setBackgroundAt(idx, Ui.ACCENT);
                Timer t = new Timer(900, e -> tabs.setBackgroundAt(idx, null));
                t.setRepeats(false);
                t.start();
                return;
            }
        }
    }

    private double currentScale() {
        String s = System.getProperty("opentrap.scaled");
        if (s != null) try { return Double.parseDouble(s); } catch (Exception ignored) {}
        return UiScale.desired();
    }

    /** Apply a new scale: save the project first, then relaunch to apply it. */
    private void relaunchScaled(double scale) {
        StateIO.saveBeforeRestart(ctx);
        UiScale.changeTo(scale, Main.launchArgs);
    }

    /** Kept for call-site compatibility; the JVM now handles scaling. */
    public void refitAfterScale() {
        if (getExtendedState() == Frame.MAXIMIZED_BOTH) { validate(); repaint(); return; }
        Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int w = Math.min(getWidth(), screen.width);
        int h = Math.min(getHeight(), screen.height);
        setSize(w, h);
        validate();
        repaint();
    }

    private void confirmExit() {
        int r = JOptionPane.showConfirmDialog(this,
                "This will close all opentrap windows - are you sure you want to exit?",
                "confirm", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null);
        if (r == JOptionPane.YES_OPTION) {
            // A session bound to a project file is written to it on the way out,
            // so nothing done since the last autosave is lost.
            java.io.File project = ctx.projectFile();
            if (project != null) {
                setStatus("Saving project " + project.getName() + "...");
                try {
                    StateIO.save(ctx, project);
                } catch (Exception e) {
                    int again = JOptionPane.showConfirmDialog(this,
                            "Could not save the project to " + project.getName() + ":\n" + e.getMessage()
                            + "\n\nExit anyway and lose the unsaved work?",
                            "Save project", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                    if (again != JOptionPane.YES_OPTION) { setStatus(" "); return; }
                }
            }
            for (ToolModule m : modules) {
                try { m.shutdown(); }
                catch (Exception e) {
                    // Exit continues regardless, but a tool that failed to shut
                    // down (a listener still bound, a socket left open) is
                    // worth a trace rather than silence.
                    Debug.error("tool '" + m.name() + "' failed to shut down", e);
                }
            }
            dispose();
            System.exit(0);
        }
    }
}
