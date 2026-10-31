package opentrap.ui;

import opentrap.core.Issue;
import opentrap.core.LogEntry;
import opentrap.core.ProjectOptions;
import opentrap.core.SuiteContext;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Project > Project size: where the bytes go. One row per tool with its entry
 * count and its header and body bytes, a row for the findings, and the size of
 * the project file on disk. The rows are the session as it is held in memory,
 * uncompressed; the file is smaller, because it is compressed and leaves audit
 * bodies out unless asked to keep them.
 */
public final class ProjectSizeDialog {
    private ProjectSizeDialog() {}

    /** One row of the breakdown. */
    public record Row(String what, int count, long headerBytes, long bodyBytes) {
        public long total() { return headerBytes + bodyBytes; }
    }

    /** The breakdown for a session, tools in history order then the findings. */
    public static List<Row> rows(SuiteContext ctx) {
        Map<String, long[]> byTool = new LinkedHashMap<>();   // count, headers, bodies
        for (LogEntry e : ctx.store().all()) {
            long[] t = byTool.computeIfAbsent(e.tool(), k -> new long[3]);
            t[0]++;
            if (e.request() != null) { t[1] += e.request().bodyOffset(); t[2] += e.request().length() - e.request().bodyOffset(); }
            if (e.response() != null) { t[1] += e.response().bodyOffset(); t[2] += e.response().length() - e.response().bodyOffset(); }
            if (e.originalResponse() != null) {
                t[1] += e.originalResponse().bodyOffset();
                t[2] += e.originalResponse().length() - e.originalResponse().bodyOffset();
            }
        }
        List<Row> out = new ArrayList<>();
        for (var en : byTool.entrySet()) {
            long[] t = en.getValue();
            out.add(new Row(en.getKey() + " history", (int) t[0], t[1], t[2]));
        }
        long ih = 0, ib = 0;
        List<Issue> issues = ctx.issues();
        for (Issue i : issues) {
            if (i.request() != null) { ih += i.request().bodyOffset(); ib += i.request().length() - i.request().bodyOffset(); }
            if (i.response() != null) { ih += i.response().bodyOffset(); ib += i.response().length() - i.response().bodyOffset(); }
        }
        out.add(new Row("findings", issues.size(), ih, ib));
        return out;
    }

    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    public static void show(Window owner, SuiteContext ctx) {
        List<Row> rows = rows(ctx);
        long total = 0;
        for (Row r : rows) total += r.total();

        AbstractTableModel model = new AbstractTableModel() {
            final String[] cols = {"What", "Items", "Headers", "Bodies", "Total"};
            @Override public int getRowCount() { return rows.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Object getValueAt(int r, int c) {
                Row x = rows.get(r);
                return switch (c) {
                    case 0 -> x.what();
                    case 1 -> String.valueOf(x.count());
                    case 2 -> human(x.headerBytes());
                    case 3 -> human(x.bodyBytes());
                    default -> human(x.total());
                };
            }
        };
        JTable table = new JTable(model);
        Ui.styleTable(table);
        Ui.distributeWidths(table, 160, 70, 90, 90, 90);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(560, Math.min(260, 40 + rows.size() * 22)));

        java.io.File f = ctx.projectFile();
        String fileLine = f == null ? "This session is not saved as a project yet."
                : "Project file: " + f.getName() + ", " + human(f.length()) + " on disk.";
        boolean keep = ProjectOptions.keepAuditBodies(ctx.settings());
        JTextArea note = Ui.note("In memory, uncompressed: " + human(total) + ". " + fileLine
                + " The file is compressed and "
                + (keep ? "keeps every audit response body, as the project settings ask."
                        : "leaves audit response bodies out; the findings keep their own full copies.")
                + " Save a copy with in-scope items only, or fewer tools, to shrink it further.");
        Ui.sizeNote(note, 560);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBorder(Ui.pad(14, 16, 12, 16));
        body.add(sp, BorderLayout.CENTER);
        body.add(note, BorderLayout.SOUTH);

        JButton close = Ui.button("Close");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(close);
        JPanel root = new JPanel(new BorderLayout());
        root.add(body, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        JDialog d = new JDialog(owner, "Project size", Dialog.ModalityType.APPLICATION_MODAL);
        d.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        d.setContentPane(root);
        close.addActionListener(e -> d.dispose());
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }
}
