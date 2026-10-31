package opentrap.ui;

import opentrap.core.Issue;
import opentrap.core.SuiteContext;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The "All issues" panel that the status bar link slides open at the bottom
 * of the window: every issue the suite has recorded, with the advisory of
 * the selected one beside it.
 */
public final class IssuesPanel extends JPanel {

    private final SuiteContext ctx;
    private List<Issue> rows = new ArrayList<>();
    private final AbstractTableModel model = new AbstractTableModel() {
        private final String[] cols = {"Time", "Source", "Issue type", "Host", "Path", "Severity", "Confidence"};
        private final java.text.DateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss d MMM yyyy");
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Issue i = rows.get(r);
            return switch (c) {
                case 0 -> fmt.format(new java.util.Date(i.time()));
                case 1 -> i.source();
                case 2 -> i.title();
                case 3 -> i.service() == null ? "" : i.service().url();
                case 4 -> i.path();
                case 5 -> i.severity().label();
                default -> i.confidence().label();
            };
        }
    };
    private final JTable table = new JTable(model);
    private final JEditorPane advisory = new JEditorPane();

    public IssuesPanel(SuiteContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        Ui.styleTable(table);
        Ui.distributeWidths(table, 140, 90, 260, 200, 200, 80, 80);
        Ui.severityColumn(table, 5);
        Ui.severityIconColumn(table, 2, 5, 6);
        advisory.setEditable(false);
        advisory.setEditorKit(Theme.htmlKit());
        table.getSelectionModel().addListSelectionListener(e -> {
            int r = table.getSelectedRow();
            advisory.setText(r < 0 ? "" : Advisory.render(rows.get(table.convertRowIndexToModel(r))));
            advisory.setCaretPosition(0);
        });
        JButton filter = Ui.filterButton();
        filter.setText("Filter: Showing all issues");
        JTextField search = Ui.searchField(16);
        javax.swing.table.TableRowSorter<AbstractTableModel> sorter = new javax.swing.table.TableRowSorter<>(model);
        table.setRowSorter(sorter);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void go() { String q = search.getText().strip(); sorter.setRowFilter(q.isEmpty() ? null : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(q))); }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        JPanel bar = Ui.filterBar(filter, search, Glyphs.menuButton("Table layout", () -> TableLayoutMenu.build(table)));
        add(bar, BorderLayout.NORTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(table), Ui.scroll(advisory));
        split.setDividerSize(5);
        Ui.balance(split, 0.6);
        add(split, BorderLayout.CENTER);
        ctx.addIssueListener(i -> SwingUtilities.invokeLater(this::reload));
        reload();
    }

    public void reload() {
        rows = new ArrayList<>(ctx.issues());
        model.fireTableDataChanged();
    }
}
