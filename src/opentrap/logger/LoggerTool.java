package opentrap.logger;

import opentrap.core.*;
import opentrap.ui.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * Central logger: every request/response the suite performs, from any tool
 * (proxy, browser, spider, scanner, intruder, repeater), in one combined view.
 * Reads the shared message store, which every tool records to.
 */
public class LoggerTool implements ToolModule {

    private SuiteContext ctx;
    private final Model model = new Model();
    private JTable table;
    private MessageEditor reqView, respView;
    private String filter = "";

    @Override public String name() { return "logger"; }

    private volatile boolean loggingOn = true;
    private final JLabel viewFilterLabel = Ui.label("View filter: showing all items");

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        ctx.store().addListener(new MessageStore.Listener() {
            @Override public void entryAdded(LogEntry e) {
                if (loggingOn) SwingUtilities.invokeLater(() -> model.add(e));
            }
            @Override public void entryUpdated(LogEntry e) { SwingUtilities.invokeLater(() -> model.updated(e)); }
            @Override public void entryRemoved(LogEntry e) { SwingUtilities.invokeLater(() -> model.remove(e)); }
            @Override public void reloaded(List<LogEntry> all) { SwingUtilities.invokeLater(() -> model.reload(all)); }
        });
    }

    @Override public Tab[] tabs() {
        table = new JTable(model);
        Ui.styleTable(table);
        // #, time, tool, method, host, path, query, params, status, length, timer, comment
        Ui.distributeWidths(table, 45, 95, 70, 55, 160, 240, 160, 80, 75, 70, 120, 150);
        Ui.centerCells(table);
        table.setRowSorter(new TableRowSorter<>(model));
        Ui.newestFirst(table, 0);

        reqView = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        respView = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
        table.getSelectionModel().addListSelectionListener(e -> showSelected());

        // View-filter bar, as in the reference: what is shown on the left, the
        // search box, the logging toggle and the actions on the right.
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(Ui.pad(2, 2, 3, 2),
                BorderFactory.createLineBorder(Ui.GRID)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        left.setOpaque(false);
        left.add(viewFilterLabel);
        bar.add(left, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        right.setOpaque(false);
        right.add(Ui.label("Search"));
        JTextField search = Ui.field("", 22);
        search.setToolTipText("show only entries containing this text");
        search.getDocument().addDocumentListener(new SimpleDoc(() -> { filter = search.getText(); model.reapply(); }));
        right.add(search);
        right.add(opentrap.ui.Glyphs.menuButton("Table layout and custom columns", () -> opentrap.ui.TableLayoutMenu.build(table)));
        JCheckBox logging = Ui.check("Logging on", true);
        logging.setToolTipText("stop or resume recording new traffic here");
        logging.addActionListener(e -> loggingOn = logging.isSelected());
        right.add(logging);
        JButton export = Ui.button("Export", "export the filtered traffic as CSV or HAR");
        export.addActionListener(e -> exportTraffic());
        JButton clear = Ui.button("Clear", "remove every logged entry");
        clear.addActionListener(e -> model.clear());
        right.add(export);
        right.add(clear);
        bar.add(right, BorderLayout.EAST);

        JSplitPane detail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqView, respView);
        detail.setDividerSize(5);
        Ui.balance(detail, 0.5);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(table), detail);
        split.setDividerSize(5);
        Ui.balance(split, 0.5);

        JPanel root = new JPanel(new BorderLayout());
        root.add(bar, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        return new Tab[]{ new Tab("logger", root) };
    }

    private void exportTraffic() {
        List<LogEntry> entries = new ArrayList<>(model.shown);
        if (entries.isEmpty()) {
            JOptionPane.showMessageDialog(ctx.frame(), "There is no traffic to export.",
                    "export", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object[] options = {"CSV", "HAR", "Cancel"};
        int choice = JOptionPane.showOptionDialog(ctx.frame(), "Export " + entries.size()
                        + " logged requests as:", "export traffic", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == 0) opentrap.report.Export.save(ctx.frame(), "opentrap-traffic.csv", opentrap.report.Export.trafficCsv(entries));
        else if (choice == 1) opentrap.report.Export.save(ctx.frame(), "opentrap-traffic.har", opentrap.report.Export.trafficHar(entries));
    }

    private void showSelected() {
        int row = table.getSelectedRow();
        if (row < 0) return;
        LogEntry e = model.at(table.convertRowIndexToModel(row));
        if (e == null) return;
        reqView.showRequest(e);
        respView.showResponse(e);
    }

    private boolean matches(LogEntry e) {
        if (filter == null || filter.isBlank()) return true;
        String f = filter.toLowerCase();
        return e.url().toLowerCase().contains(f)
            || e.tool().toLowerCase().contains(f)
            || (e.request() != null && e.request().method().toLowerCase().contains(f))
            || String.valueOf(e.status()).contains(f);
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"#", "Time", "Tool", "Method", "Host", "Path", "Query",
                "Param count", "Status code", "Length", "Start response timer", "Comment"};
        private final List<LogEntry> all = new ArrayList<>();
        private final List<LogEntry> shown = new ArrayList<>();
        private final DateFormat fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM);

        void add(LogEntry e) {
            all.add(e);
            if (matches(e)) { shown.add(e); fireTableRowsInserted(shown.size() - 1, shown.size() - 1); }
            refreshFilterLabel();
        }
        void updated(LogEntry e) {
            int i = shown.indexOf(e);
            if (i >= 0) fireTableRowsUpdated(i, i);
            else if (matches(e) && all.contains(e)) reapply();
        }
        void reapply() {
            shown.clear();
            for (LogEntry e : all) if (matches(e)) shown.add(e);
            fireTableDataChanged();
            refreshFilterLabel();
        }
        void clear() { all.clear(); shown.clear(); fireTableDataChanged(); refreshFilterLabel(); }
        /** An entry the store dropped to stay within the audit traffic cap. */
        void remove(LogEntry e) {
            all.remove(e);
            int i = shown.indexOf(e);
            if (i >= 0) { shown.remove(i); fireTableRowsDeleted(i, i); refreshFilterLabel(); }
        }
        /** Replace the contents from a restored history in one go. */
        void reload(List<LogEntry> entries) { all.clear(); all.addAll(entries); reapply(); }
        private void refreshFilterLabel() {
            viewFilterLabel.setText(filter.isEmpty()
                    ? "View filter: showing all items"
                    : "View filter: showing " + shown.size() + " of " + all.size() + " items");
        }
        LogEntry at(int r) { return (r >= 0 && r < shown.size()) ? shown.get(r) : null; }

        @Override public int getRowCount() { return shown.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : String.class; }
        @Override public Object getValueAt(int r, int c) {
            LogEntry e = shown.get(r);
            return switch (c) {
                case 0 -> e.number();
                case 1 -> fmt.format(e.time());
                case 2 -> e.tool();
                case 3 -> e.request() == null ? "" : e.request().method();
                case 4 -> e.service() == null ? "" : e.service().url();
                case 5 -> e.request() == null ? "" : e.request().path();
                case 6 -> e.request() == null ? "" : e.request().query();
                case 7 -> e.request() == null ? "" : String.valueOf(e.request().parameters().size());
                case 8 -> e.status() == 0 ? "" : String.valueOf(e.status());
                case 9 -> e.responseLen() == 0 ? "" : String.valueOf(e.responseLen());
                case 10 -> e.durationMillis() < 0 ? "" : String.valueOf(e.durationMillis());
                case 11 -> e.notes() == null ? "" : e.notes();
                default -> "";
            };
        }
    }

    private static final class SimpleDoc implements javax.swing.event.DocumentListener {
        private final Runnable r;
        SimpleDoc(Runnable r) { this.r = r; }
        public void insertUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
        public void removeUpdate(javax.swing.event.DocumentEvent e)  { r.run(); }
        public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
    }
}
