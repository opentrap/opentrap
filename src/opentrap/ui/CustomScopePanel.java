package opentrap.ui;

import opentrap.core.Scope;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A self-contained scope definition for one task: "Use advanced scope control"
 * flips between two tabs of URL prefixes (one per line) and two tables of
 * advanced rules (protocol, host, port, file). Used by the scan wizard's
 * detailed scope configuration and the live task dialog's custom scope, with
 * Select from library / Save to library hooks beneath. {@link #scope()} builds
 * a {@link Scope} from whatever the user typed.
 */
public final class CustomScopePanel extends JPanel {

    private final JCheckBox advanced = Ui.check("Use advanced scope control", false);
    private final JTextArea includeText = new JTextArea(6, 50);
    private final JTextArea excludeText = new JTextArea(6, 50);
    private final RuleTable includeRules = new RuleTable(true);
    private final RuleTable excludeRules = new RuleTable(false);
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);

    public CustomScopePanel(String includePrefill) {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        includeText.setFont(Ui.MONO);
        excludeText.setFont(Ui.MONO);
        if (includePrefill != null) includeText.setText(includePrefill);

        JTabbedPane simple = new JTabbedPane();
        simple.setFont(Ui.PLAIN);
        simple.addTab("Included URL prefixes", Ui.scroll(includeText));
        simple.addTab("Excluded URL prefixes", Ui.scroll(excludeText));
        JTabbedPane adv = new JTabbedPane();
        adv.setFont(Ui.PLAIN);
        adv.addTab("Included URLs", includeRules);
        adv.addTab("Excluded URLs", excludeRules);
        body.setOpaque(false);
        body.add(simple, "simple");
        body.add(adv, "advanced");
        advanced.addItemListener(e -> cards.show(body, advanced.isSelected() ? "advanced" : "simple"));

        JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        links.setOpaque(false);
        links.add(link("Select from library", this::selectFromLibrary));
        links.add(link("Save to library", this::saveToLibrary));

        add(advanced, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(links, BorderLayout.SOUTH);
    }

    private static JComponent link(String text, Runnable action) {
        JLabel l = new JLabel("<html><u>" + text + "</u></html>");
        l.setFont(Ui.PLAIN);
        l.setForeground(new Color(0x1A, 0x5F, 0xB4));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { action.run(); }
        });
        return l;
    }

    /** Build the scope: prefixes in simple mode, rules in advanced mode. */
    public Scope scope() {
        Scope s = new Scope();
        if (!advanced.isSelected()) {
            for (String line : includeText.getText().split("\n")) {
                Scope.Rule r = Scope.Rule.prefix(line.strip(), true);
                if (r != null) s.include().add(r);
            }
            for (String line : excludeText.getText().split("\n")) {
                Scope.Rule r = Scope.Rule.prefix(line.strip(), false);
                if (r != null) s.exclude().add(r);
            }
        } else {
            s.include().addAll(includeRules.rules);
            s.exclude().addAll(excludeRules.rules);
        }
        return s;
    }

    /** One line of text describing the scope, for a task card. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        for (Scope.Rule r : scope().include()) if (r.enabled) parts.add(r.describe());
        return parts.isEmpty() ? "Custom scope" : String.join(", ", parts);
    }

    public boolean isEmpty() {
        return advanced.isSelected() ? includeRules.rules.isEmpty() : includeText.getText().isBlank();
    }

    private void selectFromLibrary() {
        String name = LibraryDialog.pick(this, "scope", "Load from configuration library");
        if (name == null) return;
        String text = LibraryDialog.read("scope", name);
        if (text == null) return;
        String[] halves = text.split("\n--- exclude ---\n", 2);
        includeText.setText(halves[0]);
        excludeText.setText(halves.length > 1 ? halves[1] : "");
        advanced.setSelected(false);
    }

    private void saveToLibrary() {
        String name = JOptionPane.showInputDialog(this, "Name for this scope:", "Save to library", JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.isBlank()) return;
        LibraryDialog.write("scope", name.strip(), includeText.getText() + "\n--- exclude ---\n" + excludeText.getText());
    }

    /** A table of advanced rules with Add / Edit / Remove / Paste URL / Load. */
    private static final class RuleTable extends JPanel {
        final List<Scope.Rule> rules = new ArrayList<>();
        private final boolean include;
        private final AbstractTableModel model = new AbstractTableModel() {
            private final String[] cols = {"Enabled", "Protocol", "Host / IP range", "Port", "File"};
            @Override public int getRowCount() { return rules.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
            @Override public Object getValueAt(int r, int c) {
                Scope.Rule x = rules.get(r);
                return switch (c) { case 0 -> x.enabled; case 1 -> x.protocol; case 2 -> x.host; case 3 -> x.port; default -> x.file; };
            }
            @Override public void setValueAt(Object v, int r, int c) { if (c == 0) rules.get(r).enabled = Boolean.TRUE.equals(v); }
        };
        private final JTable table = new JTable(model);

        RuleTable(boolean include) {
            super(new BorderLayout(8, 0));
            this.include = include;
            setOpaque(false);
            setBorder(Ui.pad(6, 0, 0, 0));
            Ui.styleTable(table);
            table.getColumnModel().getColumn(0).setMaxWidth(70);
            JButton add = Ui.button("Add");
            JButton edit = Ui.button("Edit");
            JButton remove = Ui.button("Remove");
            JButton paste = Ui.button("Paste URL");
            JButton load = Ui.button("Load ...");
            add.addActionListener(e -> { Scope.Rule r = AddScopeUrlDialog.show(this, include, null); if (r != null) { rules.add(r); model.fireTableDataChanged(); } });
            edit.addActionListener(e -> { int i = table.getSelectedRow(); if (i >= 0 && AddScopeUrlDialog.show(this, include, rules.get(i)) != null) model.fireTableDataChanged(); });
            remove.addActionListener(e -> { int i = table.getSelectedRow(); if (i >= 0) { rules.remove(i); model.fireTableDataChanged(); } });
            paste.addActionListener(e -> {
                try {
                    Object t = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                    Scope.Rule r = t == null ? null : Scope.Rule.prefix(String.valueOf(t).trim(), include);
                    if (r != null) { rules.add(r); model.fireTableDataChanged(); }
                } catch (Exception ignore) { }
            });
            load.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
                try {
                    for (String line : java.nio.file.Files.readAllLines(fc.getSelectedFile().toPath())) {
                        Scope.Rule r = Scope.Rule.prefix(line.strip(), include);
                        if (r != null) rules.add(r);
                    }
                    model.fireTableDataChanged();
                } catch (java.io.IOException ex) { JOptionPane.showMessageDialog(this, "Could not read the file: " + ex.getMessage()); }
            });
            add(Ui.buttonColumn(add, edit, remove, paste, load), BorderLayout.WEST);
            JScrollPane sp = Ui.scroll(table);
            sp.setPreferredSize(new Dimension(520, 150));
            add(sp, BorderLayout.CENTER);
        }
    }
}
