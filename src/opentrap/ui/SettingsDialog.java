package opentrap.ui;

import opentrap.core.SuiteContext;
import opentrap.ui.settings.InterfacePages;
import opentrap.ui.settings.NetworkPages;
import opentrap.ui.settings.ProjectPages;
import opentrap.ui.settings.SettingsPage;
import opentrap.ui.settings.SuitePages;
import opentrap.ui.settings.ToolPages;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The suite Settings window: a tree of sections and pages on the left with
 * All / User / Project filter pills and a search box, the chosen page on the
 * right under a breadcrumb and a "Manage global settings" menu, and the
 * Configuration library beneath the tree.
 *
 * <p>Tools own their settings panes and register them by category name; the
 * dialog places each registered pane at its spot in the tree (a tool's
 * category is a page under Tools, and so on). Pages nobody registers are
 * built here from the shared settings pages.
 */
public final class SettingsDialog {

    private SettingsDialog() {}

    /** One page in the tree. */
    private record Page(String section, String name, Supplier<JComponent> pane) {
        String path() { return section == null ? name : section + " > " + name; }
    }

    /** Registered panes by legacy category name (a tool's name, "Sessions", ...). */
    private static final Map<String, Supplier<JComponent>> PANES = new LinkedHashMap<>();

    /** The categories, for callers that open the dialog at one. */
    public static final String[] CATEGORIES = {
        "General", "Proxy", "Intruder", "Repeater", "Sequencer", "Comparer", "Browser",
        "Project", "Sessions", "Suite", "Extensions", "Configuration library",
    };

    /** Claim a category. The supplier is called each time the dialog opens. */
    public static void register(String category, Supplier<JComponent> pane) {
        PANES.put(category, pane);
    }

    /** The tree: section -> pages, in the reference order. */
    private static List<Page> pages(SuiteContext ctx) {
        List<Page> out = new ArrayList<>();
        // Tools
        out.add(new Page("Tools", "Proxy", registered("Proxy")));
        out.add(new Page("Tools", "Intruder", registered("Intruder")));
        out.add(new Page("Tools", "Repeater", registered("Repeater")));
        out.add(new Page("Tools", "Sequencer", registered("Sequencer")));
        out.add(new Page("Tools", "Comparer", () -> ToolPages.comparer(ctx)));
        out.add(new Page("Tools", "Browser", () -> ToolPages.browser(ctx, PANES.containsKey("Browser") ? PANES.get("Browser").get() : null)));
        // Project
        out.add(new Page("Project", "Scope", () -> ProjectPages.scope(ctx)));
        out.add(new Page("Project", "Collaborator", () -> ProjectPages.collaborator(ctx)));
        out.add(new Page("Project", "Tasks", () -> ProjectPages.tasks(ctx)));
        out.add(new Page("Project", "Automatic backup", () -> ProjectPages.backup(ctx)));
        out.add(new Page("Project", "Logging", () -> ProjectPages.logging(ctx)));
        out.add(new Page("Project", "Project file", registered("Project")));
        // Sessions
        out.add(new Page(null, "Sessions", registered("Sessions")));
        // Network
        out.add(new Page("Network", "Connections", () -> NetworkPages.connections(ctx)));
        out.add(new Page("Network", "DNS", () -> NetworkPages.dns(ctx)));
        out.add(new Page("Network", "TLS", () -> NetworkPages.tls(ctx)));
        out.add(new Page("Network", "HTTP", () -> NetworkPages.http(ctx)));
        // User interface
        out.add(new Page("User interface", "Side panel", () -> InterfacePages.sidePanel(ctx)));
        out.add(new Page("User interface", "Message editor", () -> InterfacePages.messageEditor(ctx)));
        out.add(new Page("User interface", "Hotkeys", () -> InterfacePages.hotkeys(ctx)));
        out.add(new Page("User interface", "Display", () -> InterfacePages.display(ctx, null)));
        // Suite
        out.add(new Page("Suite", "REST API", () -> SuitePages.restApi(ctx)));
        out.add(new Page("Suite", "Updates", () -> SuitePages.updates(ctx)));
        out.add(new Page("Suite", "Performance", () -> SuitePages.performance(ctx)));
        out.add(new Page("Suite", "Temporary files location", () -> SuitePages.tempFiles(ctx)));
        out.add(new Page("Suite", "Startup behavior", () -> SuitePages.startup(ctx)));
        out.add(new Page("Suite", "Shutdown behavior", () -> SuitePages.shutdown(ctx)));
        // Extensions
        out.add(new Page("Extensions", "Core extension settings", registered("Extensions")));
        return out;
    }

    private static Supplier<JComponent> registered(String category) {
        return () -> {
            Supplier<JComponent> s = PANES.get(category);
            if (s != null) return s.get();
            JPanel p = new JPanel(new BorderLayout());
            p.add(Ui.note("No settings registered for " + category + "."), BorderLayout.NORTH);
            return p;
        };
    }

    /** Where a legacy category name lands in the tree. */
    private static String pathFor(String category) {
        if (category == null) return "Tools > Proxy";
        return switch (category) {
            case "General" -> "User interface > Display";
            case "Proxy", "Intruder", "Repeater", "Sequencer", "Comparer", "Browser" -> "Tools > " + category;
            case "Project" -> "Project > Project file";
            case "Scope" -> "Project > Scope";
            case "Collaborator" -> "Project > Collaborator";
            case "Tasks" -> "Project > Tasks";
            case "Sessions" -> "Sessions";
            case "Suite" -> "Suite > Performance";
            case "Extensions" -> "Extensions > Core extension settings";
            case "Configuration library" -> "Configuration library";
            default -> category.contains(" > ") ? category : "Tools > Proxy";
        };
    }

    public static void open(Window owner, SuiteContext ctx) { open(owner, ctx, null); }

    /** Open the dialog, showing {@code category} (a tool name or a "Section > Page" path) if given. */
    public static void open(Window owner, SuiteContext ctx, String category) {
        final JDialog d = new JDialog(owner, "Settings", Dialog.ModalityType.APPLICATION_MODAL);
        List<Page> pages = pages(ctx);

        // ---- right: breadcrumb + manage menu, then the page cards ----
        CardLayout cards = new CardLayout();
        final JPanel right = new JPanel(cards);
        right.setOpaque(false);
        Map<String, JComponent> built = new LinkedHashMap<>();
        Map<String, Page> byPath = new LinkedHashMap<>();
        for (Page p : pages) byPath.put(p.path(), p);
        final JLabel crumb = Ui.heading("");
        JPanel manage = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        manage.setOpaque(false);
        JLabel manageLabel = new JLabel("Manage global settings");
        manageLabel.setFont(Ui.PLAIN);
        manageLabel.setForeground(Theme.MUTED);
        manage.add(manageLabel);
        manage.add(Glyphs.menuButton("Manage global settings", () -> manageMenu(d, ctx, built)));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(Ui.pad(10, 14, 6, 10));
        head.add(crumb, BorderLayout.WEST);
        head.add(manage, BorderLayout.EAST);
        JPanel rightWrap = new JPanel(new BorderLayout());
        rightWrap.setOpaque(false);
        rightWrap.add(head, BorderLayout.NORTH);
        rightWrap.add(right, BorderLayout.CENTER);

        // The Configuration library is a page too, reached from the footer link.
        Supplier<JComponent> library = registered("Configuration library");

        java.util.function.Consumer<String> show = path -> {
            JComponent pane = built.get(path);
            if (pane == null) {
                Page p = byPath.get(path);
                pane = "Configuration library".equals(path) ? library.get() : (p == null ? new JPanel() : p.pane().get());
                if (!(pane instanceof OptionsPage)) {
                    JPanel wrap = new JPanel(new BorderLayout());
                    wrap.setOpaque(false);
                    wrap.setBorder(Ui.pad(8, 10, 8, 10));
                    wrap.add(pane, BorderLayout.CENTER);
                    pane = wrap;
                }
                built.put(path, pane);
                right.add(pane, path);
            }
            crumb.setText(path.replace(" > ", "  ›  "));
            cards.show(right, path);
        };

        // ---- left: search, pills, tree, library link ----
        JTextField search = Ui.field("", 18);
        search.putClientProperty("JTextField.placeholderText", "Search settings");
        JToggleButton all = pill("All", true), user = pill("User", false), project = pill("Project", false);
        ButtonGroup pills = new ButtonGroup();
        pills.add(all); pills.add(user); pills.add(project);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("settings");
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Ui.PLAIN);
        tree.setRowHeight(26);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        Runnable rebuild = () -> {
            root.removeAllChildren();
            String q = search.getText().strip().toLowerCase();
            SettingsPage.Kind want = user.isSelected() ? SettingsPage.Kind.USER : project.isSelected() ? SettingsPage.Kind.PROJECT : null;
            Map<String, DefaultMutableTreeNode> sections = new LinkedHashMap<>();
            for (Page p : pages) {
                if (!q.isEmpty() && !p.name().toLowerCase().contains(q) && !(p.section() != null && p.section().toLowerCase().contains(q))) continue;
                if (want != null && !matchesKind(p, want, built)) continue;
                DefaultMutableTreeNode leaf = new DefaultMutableTreeNode(p);
                if (p.section() == null) root.add(leaf);
                else sections.computeIfAbsent(p.section(), k -> { DefaultMutableTreeNode n = new DefaultMutableTreeNode(k); root.add(n); return n; }).add(leaf);
            }
            ((DefaultTreeModel) tree.getModel()).reload();
            for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
        };
        tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object v, boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, v, sel, exp, leaf, row, focus);
                Object o = ((DefaultMutableTreeNode) v).getUserObject();
                setText(o instanceof Page p ? p.name() : String.valueOf(o));
                setIcon(o instanceof Page ? null : sectionIcon(String.valueOf(o)));
                setFont(leaf ? Ui.PLAIN : Ui.BOLD);
                return this;
            }
        });
        tree.addTreeSelectionListener(e -> {
            Object o = tree.getLastSelectedPathComponent();
            if (o instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Page p) show.accept(p.path());
        });
        for (AbstractButton b : new AbstractButton[]{all, user, project}) b.addActionListener(e -> rebuild.run());
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { rebuild.run(); }
        });
        JPanel pillRow = new JPanel(new BorderLayout());
        pillRow.setOpaque(false);
        JPanel pillsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pillsPanel.setOpaque(false);
        pillsPanel.add(all); pillsPanel.add(user); pillsPanel.add(project);
        pillRow.add(pillsPanel, BorderLayout.WEST);
        JPanel expandIcons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        expandIcons.setOpaque(false);
        expandIcons.add(Glyphs.button(Glyphs.chevron(14, Theme.ICON, 1), "Expand all", () -> { for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i); }));
        expandIcons.add(Glyphs.button(Glyphs.chevron(14, Theme.ICON, 2), "Collapse all", () -> { for (int i = tree.getRowCount() - 1; i >= 0; i--) tree.collapseRow(i); }));
        pillRow.add(expandIcons, BorderLayout.EAST);
        rebuild.run();

        JLabel libLink = new JLabel("Configuration library", Glyphs.book(16, Theme.ICON), SwingConstants.LEADING);
        libLink.setFont(Ui.PLAIN);
        libLink.setBorder(Ui.pad(8, 6, 8, 6));
        libLink.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        libLink.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { tree.clearSelection(); show.accept("Configuration library"); }
        });
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setOpaque(false);
        left.setBorder(Ui.pad(10, 10, 6, 6));
        left.add(Ui.vbox(search, Box.createVerticalStrut(6), pillRow), BorderLayout.NORTH);
        JScrollPane ts = Ui.scroll(tree);
        ts.setPreferredSize(new Dimension(290, 10));
        left.add(ts, BorderLayout.CENTER);
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID), Ui.pad(6, 10, 6, 10)));
        foot.add(libLink, BorderLayout.WEST);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightWrap);
        split.setDividerSize(4);
        split.setBorder(null);
        split.setDividerLocation(310);

        // One footer across the whole window, as the reference draws it: the
        // configuration library link at the left, Close at the right.
        JButton close = Ui.button("Close");
        close.addActionListener(e -> d.dispose());
        JPanel closeBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        closeBox.setOpaque(false);
        closeBox.add(close);
        foot.add(closeBox, BorderLayout.EAST);
        JPanel rootPanel = new JPanel(new BorderLayout());
        rootPanel.add(split, BorderLayout.CENTER);
        rootPanel.add(foot, BorderLayout.SOUTH);

        // Open at the requested page.
        String path = "Configuration library".equals(category) ? "Configuration library" : pathFor(category);
        if (byPath.containsKey(path) || "Configuration library".equals(path)) {
            selectPath(tree, path);
            show.accept(path);
        }

        // Release the hosted panes when the dialog goes, so a tool's pane can be
        // hosted again next time (and shown by the tool meanwhile if it wants).
        // DISPOSE_ON_CLOSE makes this run for the window-manager close too, not
        // only the Close button: without it a dialog closed with the X is only
        // hidden and is retained, with every pane it hosted, until exit.
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { right.removeAll(); }
        });
        d.setContentPane(rootPanel);
        d.setSize(1300, 820);
        d.setMinimumSize(new Dimension(900, 600));
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    private static boolean matchesKind(Page p, SettingsPage.Kind want, Map<String, JComponent> built) {
        JComponent c = built.get(p.path());
        if (c instanceof SettingsPage sp) {
            if (sp.kinds().isEmpty()) return true;
            return sp.kinds().contains(want) || sp.kinds().contains(SettingsPage.Kind.BOTH);
        }
        // Not built yet: classify by section, as the reference does.
        String sec = p.section() == null ? p.name() : p.section();
        return switch (sec) {
            case "User interface", "Suite", "Extensions" -> want == SettingsPage.Kind.USER;
            case "Sessions" -> want == SettingsPage.Kind.PROJECT;
            case "Project" -> want == SettingsPage.Kind.PROJECT || "Automatic backup".equals(p.name()) || "Collaborator".equals(p.name());
            default -> true;   // Tools and Network mix both kinds
        };
    }

    private static void selectPath(JTree tree, String path) {
        for (int i = 0; i < tree.getRowCount(); i++) {
            Object o = tree.getPathForRow(i).getLastPathComponent();
            if (o instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Page p && p.path().equals(path)) {
                tree.setSelectionRow(i);
                tree.scrollRowToVisible(i);
                return;
            }
        }
    }

    /**
     * One of the All / User / Project filter pills. The chosen pill is filled
     * with the control colour, which is what every switched-on control in the
     * suite uses; the accent is reserved for the one button that starts an
     * action on a panel.
     */
    private static JToggleButton pill(String text, boolean on) {
        JToggleButton b = new JToggleButton(text, on) {
            @Override protected void paintComponent(java.awt.Graphics g) {
                java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(isSelected() ? Theme.CONTROL : Theme.SURFACE);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 12, 12);
                g2.setColor(isSelected() ? Theme.CONTROL : Theme.BORDER);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
                g2.setColor(isSelected() ? java.awt.Color.WHITE : Theme.TEXT);
                g2.setFont(getFont());
                java.awt.FontMetrics fm = g2.getFontMetrics();
                g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2f,
                        (getHeight() + fm.getAscent() - fm.getDescent()) / 2f);
                g2.dispose();
            }
        };
        b.setFont(Ui.PLAIN);
        b.setFocusable(false);
        b.setContentAreaFilled(false);
        b.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        b.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        return b;
    }

    /** The mark beside a settings section, so the sections read apart at a glance. */
    private static Icon sectionIcon(String section) {
        return switch (section) {
            case "Tools"                 -> Glyphs.gear(14, Theme.ICON);
            case "Project"               -> Glyphs.book(14, Theme.ICON);
            case "Sessions"              -> Glyphs.clock(14, Theme.ICON);
            case "Network"               -> Glyphs.bolt(14, Theme.ICON);
            case "User interface"        -> Glyphs.hamburger(14, Theme.ICON);
            case "Suite"                 -> Glyphs.star(14, Theme.ICON, false);
            case "Extensions"            -> Glyphs.plusRing(14, Theme.ICON);
            case "Configuration library" -> Glyphs.book(14, Theme.ICON);
            default -> null;
        };
    }

    /** User settings > Restore defaults / Load / Save; Project settings > Restore defaults > ... / Load / Save. */
    private static JPopupMenu manageMenu(JDialog d, SuiteContext ctx, Map<String, JComponent> built) {
        JPopupMenu m = new JPopupMenu();
        JMenu userMenu = new JMenu("User settings");
        userMenu.setFont(Ui.PLAIN);
        userMenu.add(item("Restore defaults", () -> restoreDefaults(ctx, built, "User interface", "Suite", "Extensions", "Tools")));
        userMenu.add(item("Load user settings", () -> load(d, ctx)));
        userMenu.add(item("Save user settings", () -> save(d, ctx, "user")));
        JMenu projectMenu = new JMenu("Project settings");
        projectMenu.setFont(Ui.PLAIN);
        JMenu restore = new JMenu("Restore defaults");
        restore.setFont(Ui.PLAIN);
        restore.add(item("All", () -> restoreDefaults(ctx, built, "Project", "Network", "Sessions", "Tools")));
        restore.add(item("Target", () -> restoreDefaults(ctx, built, "Project > Scope")));
        restore.add(item("Proxy", () -> restoreDefaults(ctx, built, "Tools > Proxy")));
        restore.add(item("Repeater", () -> restoreDefaults(ctx, built, "Tools > Repeater")));
        restore.add(item("Sequencer", () -> restoreDefaults(ctx, built, "Tools > Sequencer")));
        restore.add(item("Logger", () -> restoreDefaults(ctx, built, "Project > Logging")));
        restore.add(item("Other project settings", () -> restoreDefaults(ctx, built, "Project", "Network")));
        projectMenu.add(restore);
        projectMenu.add(item("Load project settings", () -> load(d, ctx)));
        projectMenu.add(item("Save project settings", () -> save(d, ctx, "project")));
        m.add(userMenu);
        m.add(projectMenu);
        return m;
    }

    private static JMenuItem item(String text, Runnable r) {
        JMenuItem mi = new JMenuItem(text);
        mi.setFont(Ui.PLAIN);
        mi.addActionListener(e -> r.run());
        return mi;
    }

    /** Restore every built page whose path starts with one of the prefixes. */
    private static void restoreDefaults(SuiteContext ctx, Map<String, JComponent> built, String... prefixes) {
        int n = 0;
        for (Map.Entry<String, JComponent> e : built.entrySet()) {
            for (String p : prefixes) {
                if (e.getKey().startsWith(p) && e.getValue() instanceof SettingsPage sp) { sp.restoreAll(); n++; break; }
            }
        }
        JOptionPane.showMessageDialog(ctx.frame(), n == 0
                ? "Open a page first; only pages you have viewed in this window can be restored from here."
                : "Restored the defaults of " + n + " page(s).", "Restore defaults", JOptionPane.INFORMATION_MESSAGE);
    }

    private static void save(JDialog d, SuiteContext ctx, String kind) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("opentrap-" + kind + "-settings.properties"));
        if (fc.showSaveDialog(d) != JFileChooser.APPROVE_OPTION) return;
        java.util.Properties p = new java.util.Properties();
        p.putAll(ctx.settings().snapshot());
        try (var w = java.nio.file.Files.newBufferedWriter(fc.getSelectedFile().toPath())) { p.store(w, "OpenTRAP " + kind + " settings"); }
        catch (java.io.IOException e) { JOptionPane.showMessageDialog(d, "Could not save: " + e.getMessage(), "Save settings", JOptionPane.ERROR_MESSAGE); }
    }

    private static void load(JDialog d, SuiteContext ctx) {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(d) != JFileChooser.APPROVE_OPTION) return;
        java.util.Properties p = new java.util.Properties();
        try (var r = java.nio.file.Files.newBufferedReader(fc.getSelectedFile().toPath())) { p.load(r); }
        catch (java.io.IOException e) { JOptionPane.showMessageDialog(d, "Could not load: " + e.getMessage(), "Load settings", JOptionPane.ERROR_MESSAGE); return; }
        for (String k : p.stringPropertyNames()) ctx.settings().set(k, p.getProperty(k));
        JOptionPane.showMessageDialog(d, "Loaded " + p.size() + " setting(s). Reopen Settings to see them.", "Load settings", JOptionPane.INFORMATION_MESSAGE);
    }

    /**
     * A small page for a tool's own settings tab: it says where the settings
     * live and opens the dialog there, so each setting has exactly one home.
     */
    public static JComponent launcher(SuiteContext ctx, String category, String heading, String note) {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        JLabel h = Ui.heading(heading);
        h.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextArea n = Ui.note(note);
        n.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton open = Ui.primaryButton("Open settings", "open Settings at " + category);
        open.setAlignmentX(Component.LEFT_ALIGNMENT);
        open.addActionListener(e -> {
            Window w = ctx.frame() instanceof Window win ? win : null;
            open(w, ctx, category);
        });
        column.add(h);
        column.add(Box.createVerticalStrut(6));
        column.add(n);
        column.add(Box.createVerticalStrut(12));
        column.add(open);
        JPanel page = new JPanel(new BorderLayout());
        page.setBorder(Ui.pad(16, 16, 16, 16));
        page.add(column, BorderLayout.NORTH);
        return page;
    }
}
