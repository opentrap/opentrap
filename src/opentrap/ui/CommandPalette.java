package opentrap.ui;

import opentrap.core.SuiteContext;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The command palette behind the {@code >_} icon (and Ctrl+K): a search box
 * over every place in the suite. Rows are "Go to <tool>" and "Go to <tool> >
 * <sub-tab>" navigation entries, and one entry per Settings page that opens
 * Settings there. Typing filters; Enter or a click runs the row; Escape closes.
 */
public final class CommandPalette {

    private CommandPalette() {}

    /** One row: what it says, which kind it is, and what it does. */
    public record Entry(String text, boolean settings, Runnable action, String hotkey) {}

    /**
     * Show the palette beneath {@code anchor}.
     * @param tools   tool name -> its inner tab titles (empty list for a single-view tool)
     * @param goTo    navigates to a tool, and optionally one of its inner tabs
     */
    public static void show(Component anchor, SuiteContext ctx, java.util.Map<String, List<String>> tools,
                            java.util.function.BiConsumer<String, String> goTo) {
        List<Entry> entries = new ArrayList<>();
        for (var e : tools.entrySet()) {
            String tool = capitalise(e.getKey());
            entries.add(new Entry("Go to " + tool, false, () -> goTo.accept(e.getKey(), null), hotkeyFor(e.getKey())));
            for (String sub : e.getValue())
                entries.add(new Entry("Go to " + tool + " > " + sub, false, () -> goTo.accept(e.getKey(), sub), null));
        }
        Window owner = anchor == null ? null : SwingUtilities.getWindowAncestor(anchor);
        for (String path : settingsPaths())
            entries.add(new Entry(path, true, () -> SettingsDialog.open(owner, ctx, path), null));

        final JWindow w = new JWindow(owner);
        JTextField search = Ui.field("", 40);
        search.putClientProperty("JTextField.placeholderText", "Type to search actions and settings");
        DefaultListModel<Entry> model = new DefaultListModel<>();
        JList<Entry> list = new JList<>(model);
        list.setFont(Ui.PLAIN);
        list.setFixedCellHeight(30);
        list.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean f) {
                super.getListCellRendererComponent(l, "", i, sel, f);
                Entry en = (Entry) v;
                setIcon(en.settings() ? Glyphs.gear(16, sel ? Color.WHITE : Theme.ICON) : Glyphs.arrow(16, sel ? Color.WHITE : Theme.ICON, 0));
                setIconTextGap(10);
                String bold = en.text();
                int gt = bold.indexOf(" > ");
                String html = gt < 0 ? bold : bold.substring(0, gt) + " <span style='color:" + (sel ? "#ffffff" : "#6b6b6b") + "'>&gt; " + bold.substring(gt + 3) + "</span>";
                if (!en.settings() && bold.startsWith("Go to ")) html = "Go to <b>" + html.substring(6) + "</b>";
                setText("<html><table width='100%' cellpadding='0'><tr><td>" + html + "</td><td align='right' style='color:"
                        + (sel ? "#ffffff" : "#6b6b6b") + "'>" + (en.hotkey() == null ? "" : en.hotkey()) + "</td></tr></table></html>");
                setBorder(Ui.pad(2, 10, 2, 10));
                return this;
            }
        });
        Consumer<String> filter = q -> {
            model.clear();
            String n = q == null ? "" : q.strip().toLowerCase();
            for (Entry en : entries) if (n.isEmpty() || en.text().toLowerCase().contains(n)) model.addElement(en);
            if (model.size() > 0) list.setSelectedIndex(0);
        };
        filter.accept("");
        Runnable run = () -> {
            Entry en = list.getSelectedValue();
            w.dispose();
            if (en != null) SwingUtilities.invokeLater(en.action());
        };
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filter.accept(search.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filter.accept(search.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filter.accept(search.getText()); }
        });
        search.addKeyListener(new java.awt.event.KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_ESCAPE -> w.dispose();
                    case KeyEvent.VK_ENTER -> run.run();
                    case KeyEvent.VK_DOWN -> { int i = Math.min(model.size() - 1, list.getSelectedIndex() + 1); list.setSelectedIndex(i); list.ensureIndexIsVisible(i); }
                    case KeyEvent.VK_UP -> { int i = Math.max(0, list.getSelectedIndex() - 1); list.setSelectedIndex(i); list.ensureIndexIsVisible(i); }
                    default -> { }
                }
            }
        });
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (list.locationToIndex(e.getPoint()) >= 0) run.run(); }
        });
        JPanel root = new JPanel(new BorderLayout(0, 4));
        root.setBackground(Theme.BG);
        root.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.BORDER), Ui.pad(8, 8, 8, 8)));
        JPanel top = new JPanel(new BorderLayout(6, 0));
        top.setOpaque(false);
        top.add(new JLabel(Glyphs.search(16, Theme.ICON)), BorderLayout.WEST);
        top.add(search, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);
        JScrollPane sp = Ui.scroll(list);
        sp.setPreferredSize(new Dimension(640, 400));
        root.add(sp, BorderLayout.CENTER);
        w.setContentPane(root);
        w.pack();
        Point p = anchor == null ? new Point(100, 100) : anchor.getLocationOnScreen();
        if (anchor != null) p.translate(anchor.getWidth() - w.getWidth(), anchor.getHeight() + 4);
        w.setLocation(p);
        w.addWindowFocusListener(new java.awt.event.WindowAdapter() {
            @Override public void windowLostFocus(java.awt.event.WindowEvent e) { w.dispose(); }
        });
        w.setVisible(true);
        search.requestFocusInWindow();
    }

    private static String hotkeyFor(String tool) {
        return switch (tool) {
            case "proxy" -> "Ctrl+Shift+P";
            case "intruder" -> "Ctrl+Shift+I";
            case "repeater" -> "Ctrl+Shift+R";
            case "logger" -> "Ctrl+Shift+L";
            case "organizer" -> "Ctrl+Shift+O";
            default -> null;
        };
    }

    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Every Settings page, as "Section > Page", in tree order. */
    public static List<String> settingsPaths() {
        return List.of(
                "Tools > Proxy", "Tools > Intruder", "Tools > Repeater", "Tools > Sequencer", "Tools > Comparer", "Tools > Browser",
                "Project > Scope", "Project > Collaborator", "Project > Tasks", "Project > Automatic backup", "Project > Logging", "Project > Project file",
                "Sessions",
                "Network > Connections", "Network > DNS", "Network > TLS", "Network > HTTP",
                "User interface > Side panel", "User interface > Message editor", "User interface > Hotkeys", "User interface > Display",
                "Suite > REST API", "Suite > Updates", "Suite > Performance", "Suite > Temporary files location", "Suite > Startup behavior", "Suite > Shutdown behavior",
                "Extensions > Core extension settings", "Configuration library");
    }
}
