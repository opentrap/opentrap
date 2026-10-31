package opentrap.ui.settings;

import opentrap.core.Settings;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A small editable table persisted in one settings key: rows are lines, cells
 * are tab-separated. An optional leading "Enabled" column is a checkbox. Add
 * and Edit open a form built from the column names; extra buttons (Up, Down,
 * Paste URL, Load ...) can be asked for. Used by every settings table that has
 * no model of its own: platform authentication, DNS overrides, certificates,
 * API keys, scheduled tasks.
 */
public final class KeyedTable extends JPanel {

    private final Settings settings;
    private final String key;
    private final String[] cols;
    private final boolean enabledColumn;
    private final List<String[]> rows = new ArrayList<>();
    private final AbstractTableModel model = new AbstractTableModel() {
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return enabledColumn && c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return enabledColumn && c == 0; }
        @Override public Object getValueAt(int r, int c) {
            String v = c < rows.get(r).length ? rows.get(r)[c] : "";
            return enabledColumn && c == 0 ? !"false".equals(v) : v;
        }
        @Override public void setValueAt(Object v, int r, int c) { if (enabledColumn && c == 0) { rows.get(r)[0] = String.valueOf(Boolean.TRUE.equals(v)); save(); } }
    };
    private final JTable table = new JTable(model);

    /**
     * @param cols          column names; if the first is "Enabled" it becomes a checkbox column
     * @param buttons       which buttons to show, from: Add, Edit, Remove, Up, Down, Paste URL, Load ...
     */
    public KeyedTable(Settings settings, String key, String[] cols, int height, String... buttons) {
        super(new BorderLayout(8, 0));
        this.settings = settings;
        this.key = key;
        this.cols = cols;
        this.enabledColumn = cols.length > 0 && "Enabled".equals(cols[0]);
        setOpaque(false);
        load();
        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        if (enabledColumn) table.getColumnModel().getColumn(0).setMaxWidth(70);
        List<JButton> bs = new ArrayList<>();
        for (String b : buttons.length == 0 ? new String[]{"Add", "Edit", "Remove"} : buttons) {
            JButton btn = Ui.button(b);
            switch (b) {
                case "Add" -> btn.addActionListener(e -> { String[] r = form(null); if (r != null) { rows.add(r); save(); model.fireTableDataChanged(); } });
                case "Edit" -> btn.addActionListener(e -> { int i = table.getSelectedRow(); if (i >= 0) { String[] r = form(rows.get(i)); if (r != null) { rows.set(i, r); save(); model.fireTableDataChanged(); } } });
                case "Remove" -> btn.addActionListener(e -> { int i = table.getSelectedRow(); if (i >= 0) { rows.remove(i); save(); model.fireTableDataChanged(); } });
                case "Up" -> btn.addActionListener(e -> move(-1));
                case "Down" -> btn.addActionListener(e -> move(1));
                case "Paste URL" -> btn.addActionListener(e -> {
                    try {
                        Object t = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                        if (t != null) { String[] r = blank(); r[enabledColumn ? 1 : 0] = String.valueOf(t).trim(); rows.add(r); save(); model.fireTableDataChanged(); }
                    } catch (Exception ignore) { }
                });
                case "Load ..." -> btn.addActionListener(e -> {
                    JFileChooser fc = new JFileChooser();
                    if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
                    try {
                        for (String line : java.nio.file.Files.readAllLines(fc.getSelectedFile().toPath())) {
                            if (line.isBlank()) continue;
                            String[] r = blank(); r[enabledColumn ? 1 : 0] = line.strip(); rows.add(r);
                        }
                        save(); model.fireTableDataChanged();
                    } catch (java.io.IOException ex) { JOptionPane.showMessageDialog(this, "Could not read the file: " + ex.getMessage()); }
                });
                default -> { }
            }
            bs.add(btn);
        }
        add(Ui.buttonColumn(bs.toArray(new JButton[0])), BorderLayout.WEST);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(560, height));
        add(sp, BorderLayout.CENTER);
    }

    private String[] blank() {
        String[] r = new String[cols.length];
        java.util.Arrays.fill(r, "");
        if (enabledColumn) r[0] = "true";
        return r;
    }

    private void move(int d) {
        int i = table.getSelectedRow(), to = i + d;
        if (i < 0 || to < 0 || to >= rows.size()) return;
        String[] r = rows.remove(i);
        rows.add(to, r);
        save(); model.fireTableDataChanged();
        table.setRowSelectionInterval(to, to);
    }

    private String[] form(String[] editing) {
        int from = enabledColumn ? 1 : 0;
        JTextField[] fields = new JTextField[cols.length];
        Object[] formParts = new Object[(cols.length - from) * 2];
        for (int c = from, i = 0; c < cols.length; c++, i += 2) {
            fields[c] = Ui.field(editing == null || c >= editing.length ? "" : editing[c], 28);
            if (cols[c].toLowerCase().contains("password")) {
                JPasswordField pf = new JPasswordField(fields[c].getText(), 28);
                pf.setFont(Ui.PLAIN);
                fields[c] = pf;
            }
            formParts[i] = cols[c] + ":";
            formParts[i + 1] = fields[c];
        }
        JPanel f = Ui.form(formParts);
        f.setBorder(Ui.pad(10, 10, 10, 10));
        int ok = JOptionPane.showConfirmDialog(this, f, (editing == null ? "Add" : "Edit") + " entry", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return null;
        String[] r = blank();
        if (editing != null && enabledColumn) r[0] = editing[0];
        for (int c = from; c < cols.length; c++) r[c] = fields[c] instanceof JPasswordField pf ? new String(pf.getPassword()) : fields[c].getText().strip();
        return r;
    }

    private void load() {
        rows.clear();
        String v = settings.get(key, "");
        if (v.isEmpty()) return;
        for (String line : v.split("\n")) {
            if (line.isEmpty()) continue;
            String[] cells = line.split("\t", -1);
            String[] r = blank();
            System.arraycopy(cells, 0, r, 0, Math.min(cells.length, r.length));
            rows.add(r);
        }
    }

    private void save() {
        StringBuilder sb = new StringBuilder();
        for (String[] r : rows) sb.append(String.join("\t", r).replace("\n", " ")).append('\n');
        settings.set(key, sb.toString());
    }

    public List<String[]> rows() { return new ArrayList<>(rows); }
    public JTable table() { return table; }
}
