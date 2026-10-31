package opentrap.ui.settings;

import opentrap.core.Settings;
import opentrap.core.SuiteContext;
import opentrap.ui.Glyphs;
import opentrap.ui.OptionsPage;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** Settings > User interface pages: Side panel, Message editor, Hotkeys, Display. */
public final class InterfacePages {
    private InterfacePages() {}

    public static SettingsPage sidePanel(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JRadioButton[] pos = SettingsPage.radios(s, "sidepanel.position", "right", new String[]{"left", "right"}, "Left", "Right");
        JRadioButton[] mode = SettingsPage.radios(s, "sidepanel.mode", "auto", new String[]{"auto", "collapsed"},
                "Auto-expanded when there is enough space", "Always collapsed");
        p.block("Default side panel layout", SettingsPage.Kind.USER,
                "Where the Inspector side panel sits beside a message editor, and whether it opens by itself when there is room.",
                () -> { s.set("sidepanel.position", "right"); s.set("sidepanel.mode", "auto"); },
                SettingsPage.labelled("Position:", pos[0], pos[1]),
                SettingsPage.labelled("Display mode:", mode[0], mode[1]));
        String[] widgets = {"Selection", "Request attributes", "Request query parameters", "Request body parameters", "Request cookies", "Request headers", "Response headers"};
        p.block("Inspector widgets", SettingsPage.Kind.USER,
                "Which sections the Inspector shows, which open by default, and whether their values wrap.",
                () -> SettingsPage.clearPrefix(s, "inspector."),
                new WidgetTable(s, widgets));
        return p;
    }

    /** Widget / Show / Open by default / Wrap text, with Up and Down. */
    private static final class WidgetTable extends JPanel {
        private final List<String> names;
        private final Settings s;
        private final AbstractTableModel model = new AbstractTableModel() {
            private final String[] cols = {"Widget", "Show", "Open by default", "Wrap text"};
            @Override public int getRowCount() { return names.size(); }
            @Override public int getColumnCount() { return 4; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? String.class : Boolean.class; }
            @Override public boolean isCellEditable(int r, int c) { return c > 0 && !(r == 0 && c == 1); }
            @Override public Object getValueAt(int r, int c) {
                String n = names.get(r);
                return switch (c) { case 0 -> n; case 1 -> s.getBool(key(n, "show"), true); case 2 -> s.getBool(key(n, "open"), false); default -> s.getBool(key(n, "wrap"), false); };
            }
            @Override public void setValueAt(Object v, int r, int c) {
                String n = names.get(r);
                s.setBool(key(n, c == 1 ? "show" : c == 2 ? "open" : "wrap"), Boolean.TRUE.equals(v));
            }
        };
        private static String key(String n, String what) { return "inspector." + n.toLowerCase().replace(' ', '-') + "." + what; }
        WidgetTable(Settings s, String[] widgets) {
            super(new BorderLayout(8, 0));
            this.s = s;
            setOpaque(false);
            String order = s.get("inspector.order", "");
            names = new ArrayList<>(order.isEmpty() ? List.of(widgets) : List.of(order.split("\\|")));
            JTable t = new JTable(model);
            Ui.styleTable(t);
            JButton up = Ui.button("Up"), down = Ui.button("Down");
            up.addActionListener(e -> move(t, -1));
            down.addActionListener(e -> move(t, 1));
            add(Ui.buttonColumn(up, down), BorderLayout.WEST);
            JScrollPane sp = Ui.scroll(t);
            sp.setPreferredSize(new Dimension(560, 190));
            add(sp, BorderLayout.CENTER);
        }
        private void move(JTable t, int d) {
            int i = t.getSelectedRow(), to = i + d;
            if (i < 0 || to < 0 || to >= names.size()) return;
            names.add(to, names.remove(i));
            s.set("inspector.order", String.join("|", names));
            model.fireTableDataChanged();
            t.setRowSelectionInterval(to, to);
        }
    }

    public static SettingsPage messageEditor(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("Message editor request views", SettingsPage.Kind.USER,
                "Which views a request editor offers and whether their text wraps. Raw is always available.",
                () -> SettingsPage.clearPrefix(s, "editor.request."),
                new ViewsTable(s, "editor.request.", new String[]{"Pretty", "Raw", "Hex", "Headers", "Query params", "Body params", "Cookies", "Attributes"},
                        new boolean[]{true, true, true, false, false, false, false, false}));
        p.block("Message editor response views", SettingsPage.Kind.USER,
                "Which views a response editor offers and whether their text wraps.",
                () -> SettingsPage.clearPrefix(s, "editor.response."),
                new ViewsTable(s, "editor.response.", new String[]{"Pretty", "Raw", "Hex", "Render", "Headers"},
                        new boolean[]{true, true, true, true, false}));
        JLabel font = Ui.label(s.get("editor.font", "Monospaced 13pt"));
        JButton change = Ui.button("Change font ...");
        change.addActionListener(e -> {
            String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
            JComboBox<String> fam = Ui.combo(families);
            fam.setSelectedItem(s.get("editor.font", "Monospaced 13pt").replaceAll(" \\d+pt$", ""));
            JSpinner size = new JSpinner(new SpinnerNumberModel(13, 6, 40, 1));
            JPanel f = Ui.form("Family:", fam, "Size:", size);
            f.setBorder(Ui.pad(10, 10, 10, 10));
            if (JOptionPane.showConfirmDialog(change, f, "Message editor font", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
                String v = fam.getSelectedItem() + " " + size.getValue() + "pt";
                s.set("editor.font", v); font.setText(v);
            }
        });
        p.block("HTTP message display", SettingsPage.Kind.USER,
                "How requests and responses are rendered in every message editor.",
                () -> SettingsPage.clearPrefix(s, "editor.display."),
                Ui.row(Ui.label("Font:"), font, change),
                SettingsPage.check(s, "editor.display.highlightRequest", true, "Highlight request syntax"),
                SettingsPage.check(s, "editor.display.highlightResponse", true, "Highlight response syntax"),
                SettingsPage.check(s, "editor.display.prettyByDefault", true, "Pretty print by default"),
                SettingsPage.check(s, "editor.display.hideDecodedOnHover", false, "Hide decoded text on hover"));
        JRadioButton[] cs = SettingsPage.radios(s, "editor.charset", "auto", new String[]{"auto", "platform", "raw", "specific"},
                "Recognize automatically based on message headers", "Use the platform default (UTF-8)", "Display as raw bytes", "Use a specific character set:");
        JComboBox<String> charset = SettingsPage.combo(s, "editor.charset.name", java.nio.charset.Charset.availableCharsets().keySet().toArray(new String[0]), "UTF-8");
        p.block("Character sets", SettingsPage.Kind.USER,
                "Which character set is used to display message bodies.",
                () -> { s.set("editor.charset", "auto"); s.set("editor.charset.name", "UTF-8"); },
                cs[0], cs[1], cs[2], Ui.row(cs[3], charset));
        p.block("HTML rendering", SettingsPage.Kind.USER,
                "Whether the Render view may fetch the images, styles and scripts a page refers to.",
                () -> s.setBool("render.allowHttp", false),
                SettingsPage.check(s, "render.allowHttp", false, "Make additional HTTP requests if required to render the page"));
        p.block("HTTP message search", SettingsPage.Kind.USER,
                "Defaults for the search box at the bottom of every message editor.",
                () -> SettingsPage.clearPrefix(s, "editor.search."),
                Ui.row(SettingsPage.check(s, "editor.search.caseSensitive", false, "Case sensitive"),
                       SettingsPage.check(s, "editor.search.regex", false, "Regex"),
                       SettingsPage.check(s, "editor.search.autoScroll", false, "Auto-scroll to match when text changes")));
        p.block("Hide request headers", SettingsPage.Kind.USER,
                "Headers the Pretty view folds away by default, so the interesting part of a request is visible at once.",
                () -> { s.setBool("editor.hideRequestHeaders", false); s.set("editor.hideRequestHeaders.list", ""); },
                SettingsPage.check(s, "editor.hideRequestHeaders", false, "Hide the following headers by default:"),
                new ListBox(s, "editor.hideRequestHeaders.list", List.of("accept", "accept-encoding", "accept-language", "if-modified-since", "if-none-match",
                        "priority", "sec-ch-ua", "sec-ch-ua-mobile", "sec-ch-ua-platform", "sec-fetch-dest", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-user", "upgrade-insecure-requests"), "Enter a new header"));
        p.block("Hide response headers", SettingsPage.Kind.USER,
                "Headers the Pretty view folds away by default in responses.",
                () -> { s.setBool("editor.hideResponseHeaders", false); s.set("editor.hideResponseHeaders.list", ""); },
                SettingsPage.check(s, "editor.hideResponseHeaders", false, "Hide the following headers by default:"),
                new ListBox(s, "editor.hideResponseHeaders.list", List.of("accept-ranges", "alt-svc", "cache-control", "cf-ray", "connection", "content-security-policy",
                        "cross-origin-embedder-policy", "cross-origin-opener-policy", "cross-origin-resource-policy", "date", "etag", "expires", "last-modified", "nel",
                        "report-to", "server", "strict-transport-security", "vary", "via", "x-content-type-options", "x-frame-options", "x-xss-protection"), "Enter a new header"));
        return p;
    }

    /** Type / Show / Wrap text, for the editor views. The Raw row's Show is locked. */
    private static final class ViewsTable extends JPanel {
        ViewsTable(Settings s, String prefix, String[] types, boolean[] showDefaults) {
            super(new BorderLayout());
            setOpaque(false);
            AbstractTableModel model = new AbstractTableModel() {
                private final String[] cols = {"Type", "Show", "Wrap text"};
                @Override public int getRowCount() { return types.length; }
                @Override public int getColumnCount() { return 3; }
                @Override public String getColumnName(int c) { return cols[c]; }
                @Override public Class<?> getColumnClass(int c) { return c == 0 ? String.class : Boolean.class; }
                @Override public boolean isCellEditable(int r, int c) { return c > 0 && !(c == 1 && "Raw".equals(types[r])); }
                @Override public Object getValueAt(int r, int c) {
                    String k = prefix + types[r].toLowerCase().replace(' ', '-');
                    return switch (c) { case 0 -> types[r]; case 1 -> s.getBool(k + ".show", showDefaults[r]); default -> s.getBool(k + ".wrap", false); };
                }
                @Override public void setValueAt(Object v, int r, int c) {
                    String k = prefix + types[r].toLowerCase().replace(' ', '-');
                    s.setBool(k + (c == 1 ? ".show" : ".wrap"), Boolean.TRUE.equals(v));
                }
            };
            JTable t = new JTable(model);
            Ui.styleTable(t);
            JScrollPane sp = Ui.scroll(t);
            sp.setPreferredSize(new Dimension(560, 22 * types.length + 30));
            add(sp, BorderLayout.CENTER);
        }
    }

    /** A list with Remove / Clear and an Add field, persisted as newline-separated text. */
    static final class ListBox extends JPanel {
        ListBox(Settings s, String key, List<String> defaults, String placeholder) {
            super(new BorderLayout(8, 0));
            setOpaque(false);
            DefaultListModel<String> m = new DefaultListModel<>();
            String cur = s.get(key, "");
            for (String v : cur.isEmpty() ? defaults : List.of(cur.split("\n"))) if (!v.isBlank()) m.addElement(v);
            Runnable save = () -> { StringBuilder sb = new StringBuilder(); for (int i = 0; i < m.size(); i++) sb.append(m.get(i)).append('\n'); s.set(key, sb.toString()); };
            JList<String> list = new JList<>(m);
            list.setFont(Ui.PLAIN);
            JButton remove = Ui.button("Remove"), clear = Ui.button("Clear");
            remove.addActionListener(e -> { int i = list.getSelectedIndex(); if (i >= 0) { m.remove(i); save.run(); } });
            clear.addActionListener(e -> { m.clear(); save.run(); });
            JTextField entry = Ui.field("", 24);
            entry.putClientProperty("JTextField.placeholderText", placeholder);
            JButton add = Ui.button("Add");
            add.addActionListener(e -> { if (!entry.getText().isBlank()) { m.addElement(entry.getText().strip()); entry.setText(""); save.run(); } });
            entry.addActionListener(e -> add.doClick());
            JScrollPane sp = Ui.scroll(list);
            sp.setPreferredSize(new Dimension(420, 120));
            add(Ui.buttonColumn(remove, clear), BorderLayout.WEST);
            add(sp, BorderLayout.CENTER);
            add(Ui.row(add, entry), BorderLayout.SOUTH);
        }
    }

    public static SettingsPage hotkeys(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JTextField search = Ui.field("", 24);
        search.putClientProperty("JTextField.placeholderText", "Search");
        HotkeyTable table = new HotkeyTable(s);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { table.filter(search.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { table.filter(search.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { table.filter(search.getText()); }
        });
        p.block("Hotkeys", SettingsPage.Kind.USER,
                "Select an action to change its hotkey. All hotkeys must use the Control key together with another key; "
              + "use the reset mark beside a row to return it to its default.",
                () -> SettingsPage.clearPrefix(s, "hotkey."),
                search, table);
        return p;
    }

    /** Action / Category / Hotkey. Rows are the suite's bindable actions; the reference list is appended as it is confirmed. */
    static final class HotkeyTable extends JPanel {
        record Action(String name, String category, String def) {}
        static final List<Action> ACTIONS = List.of(
                new Action("Send to Repeater", "Send to", "Ctrl+R"),
                new Action("Send to Intruder", "Send to", "Ctrl+I"),
                new Action("Send to Organizer", "Send to", "Ctrl+O"),
                new Action("Send Repeater request", "Repeater", "Ctrl+Space"),
                new Action("Toggle interception on/off", "Intercept", "Ctrl+T"),
                new Action("Forward intercepted message", "Intercept", "Ctrl+F"),
                new Action("Open command palette", "Open", "Ctrl+K"),
                new Action("Search in editor", "Edit", "Ctrl+S"),
                new Action("Next search result in editor", "Edit", "Ctrl+Period"),
                new Action("Previous search result in editor", "Edit", "Ctrl+Comma"),
                new Action("URL-encode key characters", "Edit", "Ctrl+U"),
                new Action("URL-decode", "Edit", "Ctrl+Shift+U"),
                new Action("Base64-encode", "Edit", "Ctrl+B"),
                new Action("Base64-decode", "Edit", "Ctrl+Shift+B"),
                new Action("Copy", "Edit", "Ctrl+C"), new Action("Cut", "Edit", "Ctrl+X"), new Action("Paste", "Edit", "Ctrl+V"),
                new Action("Undo", "Edit", "Ctrl+Z"), new Action("Redo", "Edit", "Ctrl+Y"), new Action("Select all", "Edit", "Ctrl+A"),
                new Action("Delete line", "Edit", "Ctrl+D"), new Action("Delete word", "Edit", "Ctrl+Delete"), new Action("Backspace word", "Edit", "Ctrl+Backspace"),
                new Action("Go to next word", "Edit", "Ctrl+Right"), new Action("Go to previous word", "Edit", "Ctrl+Left"),
                new Action("Go to next paragraph", "Edit", "Ctrl+Down"), new Action("Go to previous paragraph", "Edit", "Ctrl+Up"),
                new Action("Go to next tab", "Switch tab", "Ctrl+Equals"), new Action("Go to previous tab", "Switch tab", "Ctrl+Minus"),
                new Action("Go to Proxy", "Switch tab", "Ctrl+Shift+P"), new Action("Go to Intruder", "Switch tab", "Ctrl+Shift+I"),
                new Action("Go to Repeater", "Switch tab", "Ctrl+Shift+R"), new Action("Go to Logger", "Switch tab", "Ctrl+Shift+L"),
                new Action("Go to Organizer", "Switch tab", "Ctrl+Shift+O"),
                new Action("Active scan selected message", "Scan", ""), new Action("Add notes", "Annotate", ""),
                new Action("Add payload position", "Payload positions", ""), new Action("Clear payload positions", "Payload positions", ""),
                new Action("Add to scope", "Scope", ""), new Action("Add to site map", "Site map", ""),
                new Action("Analyze target", "Engagement tools", ""), new Action("Clear history", "History", ""),
                new Action("Clear interaction history", "Collaborator", ""), new Action("Close all tabs", "Close", ""),
                new Action("Close other tabs", "Close", ""), new Action("Copy prettified", "Edit", "Ctrl+Alt+C"));
        private final Settings s;
        private final List<Action> shown = new ArrayList<>(ACTIONS);
        private final AbstractTableModel model = new AbstractTableModel() {
            @Override public int getRowCount() { return shown.size(); }
            @Override public int getColumnCount() { return 3; }
            @Override public String getColumnName(int c) { return c == 0 ? "Action" : c == 1 ? "Category" : "Hotkey"; }
            @Override public Object getValueAt(int r, int c) {
                Action a = shown.get(r);
                return c == 0 ? a.name() : c == 1 ? a.category() : s.get("hotkey." + a.name(), a.def());
            }
        };
        HotkeyTable(Settings s) {
            super(new BorderLayout());
            this.s = s;
            setOpaque(false);
            JTable t = new JTable(model);
            Ui.styleTable(t);
            Ui.distributeWidths(t, 300, 150, 140);
            t.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    int r = t.getSelectedRow();
                    if (e.getClickCount() == 2 && r >= 0) edit(shown.get(r));
                }
            });
            JScrollPane sp = Ui.scroll(t);
            sp.setPreferredSize(new Dimension(600, 320));
            add(sp, BorderLayout.CENTER);
        }
        void filter(String q) {
            shown.clear();
            String n = q.strip().toLowerCase();
            for (Action a : ACTIONS) if (n.isEmpty() || a.name().toLowerCase().contains(n) || a.category().toLowerCase().contains(n)) shown.add(a);
            model.fireTableDataChanged();
        }
        private void edit(Action a) {
            JTextField f = Ui.field(s.get("hotkey." + a.name(), a.def()), 18);
            f.setEditable(false);
            f.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyCode() == java.awt.event.KeyEvent.VK_CONTROL || e.getKeyCode() == java.awt.event.KeyEvent.VK_SHIFT || e.getKeyCode() == java.awt.event.KeyEvent.VK_ALT) return;
                    if (!e.isControlDown()) { f.setText("Hotkeys must use Ctrl"); return; }
                    f.setText("Ctrl+" + (e.isShiftDown() ? "Shift+" : "") + (e.isAltDown() ? "Alt+" : "") + java.awt.event.KeyEvent.getKeyText(e.getKeyCode()));
                }
            });
            JButton reset = Ui.button("Reset to default");
            reset.addActionListener(e -> f.setText(a.def()));
            JPanel form = Ui.vbox(Ui.label("Press the new hotkey for \"" + a.name() + "\":"), Ui.row(f, reset));
            form.setBorder(Ui.pad(10, 10, 10, 10));
            if (JOptionPane.showConfirmDialog(this, form, "Hotkey", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION
                    && f.getText().startsWith("Ctrl")) {
                s.set("hotkey." + a.name(), f.getText());
                model.fireTableDataChanged();
            }
        }
    }

    public static SettingsPage display(SuiteContext ctx, JComponent existingDisplay) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JComboBox<String> fontSize = SettingsPage.combo(s, "ui.fontSize", new String[]{"10", "11", "12", "13", "14", "15", "16", "18", "20"}, "13");
        JRadioButton light = Ui.radio("Light", !Theme.darkPreferred());
        JRadioButton dark = Ui.radio("Dark", Theme.darkPreferred());
        ButtonGroup g = new ButtonGroup(); g.add(light); g.add(dark);
        java.awt.event.ItemListener theme = e -> {
            boolean wantDark = dark.isSelected();
            if (wantDark == Theme.dark) return;
            s.set("ui.theme", wantDark ? "dark" : "light");
            try { java.util.prefs.Preferences.userRoot().node("opentrap.v1").put("ui.theme", wantDark ? "dark" : "light"); } catch (Exception ignore) { }
            if (opentrap.ui.UiScale.relaunchWith(opentrap.ui.UiScale.desired(), opentrap.ui.Main.launchArgs)) System.exit(0);
        };
        light.addItemListener(theme); dark.addItemListener(theme);
        p.block("Appearance", SettingsPage.Kind.USER,
                "The interface font size and colour theme. Changing the theme restarts the app so every panel comes up consistent.",
                () -> { s.set("ui.fontSize", "13"); },
                SettingsPage.labelled("Font size:", fontSize),
                SettingsPage.labelled("Theme:", light, dark));
        if (existingDisplay != null) p.addSection(existingDisplay);
        JCheckBox configure = SettingsPage.check(s, "ui.scale.configure", true, "Configure scaling settings");
        JCheckBox systemDpi = SettingsPage.check(s, "ui.scale.systemDpi", true, "Use system DPI settings");
        JComboBox<String> factor = SettingsPage.combo(s, "ui.scale.factor", new String[]{"1.0", "1.25", "1.5", "1.75", "2.0", "2.5", "3.0"},
                String.valueOf(opentrap.ui.UiScale.desired()));
        factor.addActionListener(e -> {
            try { opentrap.ui.UiScale.changeTo(Double.parseDouble(String.valueOf(factor.getSelectedItem())), opentrap.ui.Main.launchArgs); } catch (Exception ignore) { }
        });
        p.block("Scaling", SettingsPage.Kind.USER,
                "Scale the whole interface for high-density screens. Changing the scale factor restarts the app.",
                () -> SettingsPage.clearPrefix(s, "ui.scale."),
                configure, systemDpi, SettingsPage.labelled("Scale factor:", factor), Ui.note("Requires a restart to take effect."));
        p.block("Title bar appearance", SettingsPage.Kind.USER,
                "Draw the top-level menu inside the window's title bar, where the window manager allows it.",
                () -> s.setBool("ui.mergeTitleBar", true),
                SettingsPage.check(s, "ui.mergeTitleBar", true, "Merge top-level menu with system title bar"));
        JRadioButton[] zebra = SettingsPage.radios(s, "ui.zebra", "on", new String[]{"on", "off"}, "Zebra stripe", "No zebra stripe");
        p.block("Table appearance", SettingsPage.Kind.USER,
                "Whether tables alternate row colours.",
                () -> s.set("ui.zebra", "on"),
                Ui.row(preview(true), preview(false)), Ui.row(zebra[0], Box.createHorizontalStrut(80), zebra[1]));
        JRadioButton[] ev = SettingsPage.radios(s, "ui.issueEvidence", "grouped", new String[]{"grouped", "ungrouped"}, "Grouped evidence", "Ungrouped evidence");
        p.block("Issues", SettingsPage.Kind.USER,
                "Control how the evidence for an issue is displayed: grouped by request, or one item per piece of evidence.",
                () -> { s.set("ui.issueEvidence", "grouped"); s.setBool("ui.expandEvidence", false); },
                Ui.row(ev[0], Box.createHorizontalStrut(60), ev[1]),
                SettingsPage.check(s, "ui.expandEvidence", false, "Expand evidence groups by default"));
        p.block("Title bar actions", SettingsPage.Kind.USER,
                "Choose the actions shown as icons in the title bar, and the order in which they appear. The settings cog is always shown at the end.",
                () -> s.set("ui.titleActions", ""),
                Ui.note("Drag to reorder. Click the X to remove."),
                new TitleActions(s));
        return p;
    }

    private static JComponent preview(boolean zebra) {
        JPanel card = new JPanel(new GridLayout(4, 1));
        card.setPreferredSize(new Dimension(150, 70));
        card.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        for (int i = 0; i < 4; i++) {
            JPanel row = new JPanel();
            row.setBackground(zebra && i % 2 == 1 ? Theme.SURFACE : Theme.BG);
            card.add(row);
        }
        return card;
    }

    /** The reorderable list of title bar actions, with an add button and an X per row. */
    static final class TitleActions extends JPanel {
        static final String[] ALL = {"Open browser", "Open command palette", "Open search window"};
        private final Settings s;
        private final List<String> order = new ArrayList<>();
        TitleActions(Settings s) {
            super(new BorderLayout(0, 4));
            this.s = s;
            setOpaque(false);
            String cur = s.get("ui.titleActions", "");
            order.addAll(cur.isEmpty() ? List.of(ALL) : List.of(cur.split("\\|")));
            rebuild();
        }
        private void save() { s.set("ui.titleActions", String.join("|", order)); }
        private void rebuild() {
            removeAll();
            JPanel top = new JPanel(new BorderLayout());
            top.setOpaque(false);
            JButton add = Glyphs.button(Glyphs.plus(16, Theme.ICON), "Add an action", () -> {
                List<String> missing = new ArrayList<>();
                for (String a : ALL) if (!order.contains(a)) missing.add(a);
                if (missing.isEmpty()) return;
                Object pick = JOptionPane.showInputDialog(this, "Action:", "Add title bar action", JOptionPane.PLAIN_MESSAGE, null, missing.toArray(), missing.get(0));
                if (pick != null) { order.add(String.valueOf(pick)); save(); rebuild(); }
            });
            top.add(add, BorderLayout.EAST);
            add(top, BorderLayout.NORTH);
            JPanel rows = new JPanel();
            rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
            rows.setOpaque(false);
            for (int i = 0; i < order.size(); i++) {
                final int idx = i;
                String name = order.get(i);
                JPanel row = new JPanel(new BorderLayout(8, 0));
                row.setOpaque(true);
                row.setBackground(Theme.SURFACE);
                row.setBorder(Ui.pad(4, 8, 4, 8));
                row.setMaximumSize(new Dimension(420, 32));
                Icon ic = switch (name) { case "Open browser" -> Glyphs.search(16, Theme.ICON); case "Open command palette" -> Glyphs.chevronRight(); default -> Glyphs.search(16, Theme.ICON); };
                JLabel l = new JLabel(name, ic, SwingConstants.LEADING);
                l.setFont(Ui.PLAIN);
                JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
                right.setOpaque(false);
                right.add(Glyphs.button(Glyphs.arrow(14, Theme.ICON, 2), "Move up", () -> { if (idx > 0) { order.add(idx - 1, order.remove(idx)); save(); rebuild(); } }));
                right.add(Glyphs.button(Glyphs.arrow(14, Theme.ICON, 1), "Move down", () -> { if (idx < order.size() - 1) { order.add(idx + 1, order.remove(idx)); save(); rebuild(); } }));
                right.add(Glyphs.button(Glyphs.cross(14, Theme.ICON), "Remove", () -> { order.remove(idx); save(); rebuild(); }));
                row.add(l, BorderLayout.CENTER);
                row.add(right, BorderLayout.EAST);
                rows.add(row);
                rows.add(Box.createVerticalStrut(4));
            }
            add(rows, BorderLayout.CENTER);
            revalidate();
            repaint();
        }
    }
}
