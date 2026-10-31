package opentrap.proxy;

import opentrap.core.*;

import javax.swing.table.AbstractTableModel;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

/** Table model over the proxy history, with filtering. */
public class HistoryModel extends AbstractTableModel {

    private static final String[] COLS = {
        "#", "Host", "Method", "URL", "Params", "Edited", "Status code",
        "Length", "MIME type", "Extension", "Title", "Notes", "TLS", "IP", "Time"
    };

    private final List<LogEntry> all = new ArrayList<>();
    private final List<LogEntry> shown = new ArrayList<>();
    private final DateFormat fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM);
    private java.util.function.Predicate<LogEntry> filter = e -> true;
    private String toolFilter;

    public HistoryModel(String toolFilter) { this.toolFilter = toolFilter; }

    public void setFilter(java.util.function.Predicate<LogEntry> f) {
        this.filter = f == null ? e -> true : f;
        reapply();
    }

    public synchronized void add(LogEntry e) {
        if (toolFilter != null && !toolFilter.equals(e.tool())) return;
        all.add(e);
        if (filter.test(e)) {
            shown.add(e);
            fireTableRowsInserted(shown.size() - 1, shown.size() - 1);
        }
    }

    public synchronized void updated(LogEntry e) {
        int i = shown.indexOf(e);
        if (i >= 0) fireTableRowsUpdated(i, i);
        else if (filter.test(e) && all.contains(e)) reapply();
    }

    public synchronized void reapply() {
        shown.clear();
        for (LogEntry e : all) if (filter.test(e)) shown.add(e);
        fireTableDataChanged();
    }

    /** Replace the contents from a restored history in one go (one table event). */
    public synchronized void reload(java.util.List<LogEntry> entries) {
        all.clear();
        for (LogEntry e : entries) {
            if (toolFilter == null || toolFilter.equals(e.tool())) all.add(e);
        }
        reapply();
    }

    public synchronized void clear() { all.clear(); shown.clear(); fireTableDataChanged(); }

    public synchronized LogEntry at(int row) {
        return (row >= 0 && row < shown.size()) ? shown.get(row) : null;
    }

    public synchronized void remove(LogEntry e) { all.remove(e); reapply(); }

    @Override public int getRowCount() { return shown.size(); }
    @Override public int getColumnCount() { return COLS.length; }
    @Override public String getColumnName(int c) { return COLS[c]; }

    @Override public Class<?> getColumnClass(int c) {
        return (c == 4 || c == 5 || c == 12) ? Boolean.class
             : (c == 0) ? Integer.class : String.class;
    }

    @Override public synchronized Object getValueAt(int row, int col) {
        LogEntry e = shown.get(row);
        return switch (col) {
            case 0 -> e.number();
            case 1 -> e.service().url();
            case 2 -> e.request() == null ? "" : e.request().method();
            case 3 -> e.pathAndQuery();
            case 4 -> e.request() != null && e.request().hasParameters();
            case 5 -> e.edited();
            case 6 -> e.status() == 0 ? "" : String.valueOf(e.status());
            case 7 -> e.responseLen() == 0 ? "" : String.valueOf(e.responseLen());
            case 8 -> e.mimeType();
            case 9 -> e.request() == null ? "" : e.request().fileExtension();
            case 10 -> e.title();
            case 11 -> e.notes() == null ? "" : e.notes();
            case 12 -> e.service().secure();
            case 13 -> e.remoteIp();
            default -> fmt.format(e.time());
        };
    }
}
