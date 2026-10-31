package opentrap.ui;

import javax.swing.*;
import javax.swing.table.TableColumn;
import javax.swing.table.TableColumnModel;
import java.util.ArrayList;
import java.util.List;

/**
 * The menu every table's kebab opens: "Table layout" with one check item per
 * column to show or hide it, "Restore default layout", and "Add custom
 * column". Hidden columns are kept (removed from the column model, remembered
 * on the table) so they can be restored; the default layout is whatever the
 * table had the first time the menu saw it.
 */
public final class TableLayoutMenu {
    private TableLayoutMenu() {}

    private static final String HIDDEN = "opentrap.table.hidden";
    private static final String DEFAULT = "opentrap.table.default";
    private static final String CUSTOM = "opentrap.table.customColumns";

    /** Build the menu for this table. */
    public static JPopupMenu build(JTable table) {
        JPopupMenu m = new JPopupMenu();
        JMenu layout = new JMenu("Table layout");
        layout.setFont(Ui.PLAIN);
        layout.setIcon(Glyphs.copy(14, Theme.MUTED));
        rememberDefault(table);
        List<TableColumn> hidden = hidden(table);
        // Every column the model knows, in the table's current order then the hidden ones.
        List<TableColumn> all = new ArrayList<>();
        TableColumnModel cm = table.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) all.add(cm.getColumn(i));
        all.addAll(hidden);
        for (TableColumn col : all) {
            String name = String.valueOf(col.getHeaderValue());
            if (name.isBlank()) name = "(column " + (col.getModelIndex() + 1) + ")";
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(name, !hidden.contains(col));
            item.setFont(Ui.PLAIN);
            item.addActionListener(e -> {
                if (item.isSelected()) { hidden.remove(col); cm.addColumn(col); }
                else if (cm.getColumnCount() > 1) { hidden.add(col); cm.removeColumn(col); }
            });
            layout.add(item);
        }
        layout.addSeparator();
        JMenuItem restore = new JMenuItem("Restore default layout");
        restore.setFont(Ui.PLAIN);
        restore.addActionListener(e -> restoreDefault(table));
        layout.add(restore);
        m.add(layout);

        JMenuItem custom = new JMenuItem("Add custom column");
        custom.setFont(Ui.PLAIN);
        custom.setIcon(Glyphs.plus(14, Theme.MUTED));
        custom.addActionListener(e -> addCustomColumn(table));
        m.add(custom);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<TableColumn> hidden(JTable t) {
        Object o = t.getClientProperty(HIDDEN);
        if (o instanceof List<?> l) return (List<TableColumn>) l;
        List<TableColumn> l = new ArrayList<>();
        t.putClientProperty(HIDDEN, l);
        return l;
    }

    private static void rememberDefault(JTable t) {
        if (t.getClientProperty(DEFAULT) != null) return;
        List<TableColumn> cols = new ArrayList<>();
        TableColumnModel cm = t.getColumnModel();
        for (int i = 0; i < cm.getColumnCount(); i++) cols.add(cm.getColumn(i));
        t.putClientProperty(DEFAULT, cols);
    }

    @SuppressWarnings("unchecked")
    private static void restoreDefault(JTable t) {
        Object o = t.getClientProperty(DEFAULT);
        if (!(o instanceof List<?> def)) return;
        TableColumnModel cm = t.getColumnModel();
        while (cm.getColumnCount() > 0) cm.removeColumn(cm.getColumn(0));
        for (TableColumn c : (List<TableColumn>) def) cm.addColumn(c);
        hidden(t).clear();
    }

    /** A custom column: a name and a script; shown as a column whose cells say what it would compute. */
    private static void addCustomColumn(JTable t) {
        JTextField name = Ui.field("", 24);
        JTextArea script = new JTextArea("return requestResponse.response() == null ? \"\" : requestResponse.response().statusCode();", 6, 48);
        script.setFont(Ui.MONO);
        JPanel form = new JPanel(new java.awt.BorderLayout(0, 6));
        form.add(Ui.form("Column name:", name), java.awt.BorderLayout.NORTH);
        form.add(Ui.vbox(Ui.label("Script (Object column(HttpRequestResponse requestResponse, Utilities utilities)):"),
                Ui.scroll(script)), java.awt.BorderLayout.CENTER);
        int ok = JOptionPane.showConfirmDialog(t, form, "Add custom column", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION || name.getText().isBlank()) return;
        TableColumn col = new TableColumn(0);
        col.setHeaderValue(name.getText().strip());
        col.setIdentifier("custom:" + name.getText().strip());
        col.setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public java.awt.Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(tb, "", sel, f, r, c);
                setForeground(Theme.MUTED);
                setText("script");
                return this;
            }
        });
        t.getColumnModel().addColumn(col);
    }
}
