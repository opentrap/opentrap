package opentrap.ui.settings;

import opentrap.core.Settings;
import opentrap.ui.Glyphs;
import opentrap.ui.OptionsPage;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * A settings page in the reference layout: a stack of blocks, each with a
 * help mark and title on the left, a "User setting" / "Project setting" pill
 * on the right, then a restore-defaults mark beside the description, then the
 * controls. The page remembers which kinds of setting it holds so the
 * dialog's All / User / Project filter can show or hide it.
 */
public class SettingsPage extends OptionsPage {

    public enum Kind { USER, PROJECT, BOTH }

    private final EnumSet<Kind> kinds = EnumSet.noneOf(Kind.class);
    private final List<String> blockTitles = new ArrayList<>();
    private final List<Runnable> restorers = new ArrayList<>();

    public EnumSet<Kind> kinds() { return kinds; }
    public List<String> blockTitles() { return blockTitles; }

    /** Restore every block on this page to its defaults. */
    public void restoreAll() { for (Runnable r : restorers) r.run(); }

    /**
     * Add a block. {@code restore} may be null when the block has nothing to
     * reset (an informational block, or one whose controls save themselves).
     */
    public JPanel block(String title, Kind kind, String description, Runnable restore, Component... parts) {
        kinds.add(kind);
        blockTitles.add(title);
        if (restore != null) restorers.add(restore);

        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.setOpaque(false);
        left.add(new JLabel(Glyphs.help(18, Theme.MUTED)));
        JLabel h = Ui.heading(title);
        h.setFont(new Font("Dialog", Font.BOLD, 14));
        left.add(h);
        head.add(left, BorderLayout.WEST);
        JPanel pills = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        pills.setOpaque(false);
        if (kind == Kind.USER || kind == Kind.BOTH) pills.add(pill("User setting"));
        if (kind == Kind.PROJECT || kind == Kind.BOTH) pills.add(pill("Project setting"));
        head.add(pills, BorderLayout.EAST);

        JPanel descRow = new JPanel(new BorderLayout(8, 0));
        descRow.setOpaque(false);
        Icon mark = restore == null ? Glyphs.gear(18, Theme.MUTED) : Glyphs.arrow(18, Theme.MUTED, 3);
        JButton markBtn = Glyphs.button(mark, restore == null ? "This block's controls apply as you change them" : "Restore this block's defaults", restore);
        JPanel markWrap = new JPanel(new BorderLayout());
        markWrap.setOpaque(false);
        markWrap.add(markBtn, BorderLayout.NORTH);
        descRow.add(markWrap, BorderLayout.WEST);
        JTextArea desc = Ui.note(description == null ? "" : description);
        Ui.sizeNote(desc, 640);
        descRow.add(desc, BorderLayout.CENTER);

        List<Component> all = new ArrayList<>();
        all.add(head);
        all.add(descRow);
        all.add(Box.createVerticalStrut(4));
        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setBorder(Ui.pad(0, 34, 0, 0));
        for (Component c : parts) {
            if (c instanceof JTextArea ta && ta.getLineWrap()) { Ui.sizeNote(ta, 600); ta.setAlignmentX(Component.LEFT_ALIGNMENT); }
            else if (c instanceof JComponent jc) {
                jc.setAlignmentX(Component.LEFT_ALIGNMENT);
                Dimension pref = jc.getPreferredSize();
                jc.setMaximumSize(new Dimension(Math.max(620, pref.width), pref.height));
            }
            body.add(c);
            body.add(Box.createVerticalStrut(4));
        }
        all.add(body);
        return section("", all.toArray(new Component[0]));
    }

    private static JLabel pill(String text) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.PLAIN.deriveFont(11f));
        l.setForeground(Theme.TEXT);
        l.setOpaque(true);
        l.setBackground(Theme.SURFACE_2);
        l.setBorder(BorderFactory.createCompoundBorder(
                new opentrap.ui.theme.RoundedBorder(Theme.BORDER, Theme.SURFACE_2, 10, new Insets(2, 8, 2, 8)),
                Ui.pad(0, 0, 0, 0)));
        return l;
    }

    // ---- small binding helpers shared by the pages ----

    public static JCheckBox check(Settings s, String key, boolean def, String text) {
        JCheckBox c = Ui.check(text, s.getBool(key, def));
        c.addItemListener(e -> s.setBool(key, c.isSelected()));
        s.addListener(k -> { if (k.equals(key)) SwingUtilities.invokeLater(() -> { boolean v = s.getBool(key, def); if (c.isSelected() != v) c.setSelected(v); }); });
        return c;
    }

    public static JTextField text(Settings s, String key, String def, int cols) {
        JTextField f = Ui.field(s.get(key, def), cols);
        f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { s.set(key, f.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { s.set(key, f.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { s.set(key, f.getText()); }
        });
        return f;
    }

    public static JTextField number(Settings s, String key, int def, int cols) {
        JTextField f = Ui.field(String.valueOf(s.getInt(key, def)), cols);
        f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void go() { try { s.setInt(key, Integer.parseInt(f.getText().trim())); } catch (NumberFormatException ignore) { } }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        return f;
    }

    /** A radio group bound to one key; values[i] is stored when radios[i] is chosen. */
    public static JRadioButton[] radios(Settings s, String key, String def, String[] values, String... labels) {
        ButtonGroup g = new ButtonGroup();
        JRadioButton[] out = new JRadioButton[labels.length];
        String cur = s.get(key, def);
        for (int i = 0; i < labels.length; i++) {
            final String v = values[i];
            out[i] = Ui.radio(labels[i], v.equals(cur));
            out[i].addItemListener(e -> { if (out2(out, v, values).isSelected()) s.set(key, v); });
            g.add(out[i]);
        }
        return out;
    }
    private static JRadioButton out2(JRadioButton[] out, String v, String[] values) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(v)) return out[i];
        return out[0];
    }

    public static <T> JComboBox<T> combo(Settings s, String key, T[] items, T def) {
        JComboBox<T> c = Ui.combo(items);
        String cur = s.get(key, String.valueOf(def));
        for (T t : items) if (String.valueOf(t).equals(cur)) c.setSelectedItem(t);
        c.addActionListener(e -> s.set(key, String.valueOf(c.getSelectedItem())));
        return c;
    }

    /** A path field with a chooser button. */
    public static JPanel pathRow(Settings s, String key, String def, boolean folder, String buttonText) {
        JTextField f = text(s, key, def, 40);
        JButton pick = Ui.button(buttonText);
        pick.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(f.getText().isBlank() ? System.getProperty("user.home") : f.getText());
            if (folder) fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) f.setText(fc.getSelectedFile().getAbsolutePath());
        });
        return Ui.row(f, pick);
    }

    /** A boxed "Override options for this project only" toggle row, as the reference draws it. */
    public static JPanel overrideRow(Settings s, String key) {
        JCheckBox c = check(s, key, false, "Override options for this project only");
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        p.add(c);
        return p;
    }

    /** A flow row of items with a leading label. */
    public static JPanel labelled(String label, Component... items) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        p.add(Ui.label(label));
        for (Component c : items) p.add(c);
        return p;
    }

    /** Remove every key under a prefix, so bound controls fall back to their defaults. */
    public static void clearPrefix(Settings s, String prefix) {
        for (String k : new ArrayList<>(s.keys())) if (k.startsWith(prefix)) s.remove(k);
    }
}
