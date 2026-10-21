package opentrap.intruder;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.SuiteContext;
import opentrap.ui.MessageEditor;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A detached window showing the live results of one Intruder attack: a sortable
 * table of exchanges, request and response viewers, a status line and a
 * progress bar. There is one window per attack.
 */
public class ResultsWindow extends JFrame {

    /** One row of attack output. */
    public record Result(int number, String payload, HttpService svc, HttpMessage request,
                         HttpMessage response, int status, int length, long millis,
                         String error, boolean[] grepHits) {}

    private final int fixedColumns;
    private final DefaultTableModel model;
    private final JTable table;
    private final List<Result> rows = new ArrayList<>();

    private final MessageEditor reqEditor;
    private final MessageEditor respEditor;
    private final JLabel status = Ui.label("waiting");
    private final JProgressBar progress = new JProgressBar();
    private java.util.function.Consumer<Boolean> pauseControl;

    public ResultsWindow(SuiteContext ctx, int attackNumber, List<String> grepExpressions) {
        super("intruder attack " + attackNumber);
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { confirmClose(); }
        });

        List<String> cols = new ArrayList<>(List.of(
                "request#", "payload", "status", "length", "time (ms)", "error"));
        cols.addAll(grepExpressions);
        fixedColumns = 6;

        model = new DefaultTableModel(cols.toArray(), 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
            @Override public Class<?> getColumnClass(int c) {
                if (c >= fixedColumns) return Boolean.class;
                return switch (c) {
                    case 0, 2, 3 -> Integer.class;
                    case 4 -> Long.class;
                    default -> String.class;
                };
            }
        };
        table = new JTable(model);
        Ui.styleTable(table);
        Ui.centerCells(table);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setColumnSelectionAllowed(false);
        table.setAutoCreateRowSorter(true);
        table.getTableHeader().setToolTipText(
                "click column to sort ascending (SHIFT-click = descending)");
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected();
        });
        int[] widths = new int[cols.size()];
        widths[0] = 60; widths[1] = 160; widths[2] = 55; widths[3] = 60; widths[4] = 70; widths[5] = 140;
        for (int i = fixedColumns; i < widths.length; i++) widths[i] = 70;
        Ui.setWidths(table, widths);

        JScrollPane tableScroll = Ui.scroll(table);
        tableScroll.setPreferredSize(new Dimension(640, 260));
        tableScroll.getVerticalScrollBar().setUnitIncrement(25);

        reqEditor = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        respEditor = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
        JPanel reqPanel = new JPanel(new BorderLayout());
        reqPanel.add(Ui.bold(" request"), BorderLayout.NORTH);
        reqPanel.add(reqEditor, BorderLayout.CENTER);
        JPanel respPanel = new JPanel(new BorderLayout());
        respPanel.add(Ui.bold(" response"), BorderLayout.NORTH);
        respPanel.add(respEditor, BorderLayout.CENTER);
        JSplitPane viewers = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqPanel, respPanel);
        viewers.setDividerSize(8);
        Ui.balance(viewers, 0.5);

        JSplitPane outer = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, viewers);
        outer.setDividerSize(8);
        Ui.balance(outer, 0.45);

        JPanel south = new JPanel(new BorderLayout(20, 0));
        south.setBorder(Ui.pad(3, 6, 3, 6));
        south.add(status, BorderLayout.WEST);
        south.add(progress, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        add(buildToolbar(), BorderLayout.NORTH);
        add(outer, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setSize(820, 560);
        setLocationRelativeTo(ctx.frame());
    }

    private JComponent buildToolbar() {
        JToggleButton pause = new JToggleButton("pause");
        pause.setFont(Ui.PLAIN);
        pause.setFocusable(false);
        pause.addActionListener(e -> {
            boolean p = pause.isSelected();
            pause.setText(p ? "resume" : "pause");
            if (pauseControl != null) pauseControl.accept(p);
        });
        JButton export = Ui.button("export CSV", "save the results table to a CSV file");
        export.addActionListener(e -> exportCsv());

        JTextField filter = Ui.field("", 18);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(filter.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter(filter.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter(filter.getText()); }
        });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
        bar.add(pause);
        bar.add(export);
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(Ui.label("filter:"));
        bar.add(filter);
        return bar;
    }

    @SuppressWarnings("unchecked")
    private void applyFilter(String text) {
        javax.swing.RowSorter<?> rs = table.getRowSorter();
        if (!(rs instanceof javax.swing.table.TableRowSorter)) return;
        javax.swing.table.TableRowSorter<javax.swing.table.TableModel> sorter =
                (javax.swing.table.TableRowSorter<javax.swing.table.TableModel>) rs;
        if (text == null || text.isEmpty()) { sorter.setRowFilter(null); return; }
        try { sorter.setRowFilter(javax.swing.RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(text))); }
        catch (Exception ex) { sorter.setRowFilter(null); }
    }

    private void exportCsv() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("export results to CSV");
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (java.io.PrintWriter w = new java.io.PrintWriter(fc.getSelectedFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            int cols = model.getColumnCount();
            StringBuilder header = new StringBuilder();
            for (int c = 0; c < cols; c++) { if (c > 0) header.append(','); header.append(csv(model.getColumnName(c))); }
            w.println(header);
            for (int r = 0; r < model.getRowCount(); r++) {
                StringBuilder line = new StringBuilder();
                for (int c = 0; c < cols; c++) {
                    if (c > 0) line.append(',');
                    Object v = model.getValueAt(r, c);
                    line.append(csv(v == null ? "" : v.toString()));
                }
                w.println(line);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "export failed: " + ex.getMessage());
        }
    }

    private static String csv(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    public void setPauseControl(java.util.function.Consumer<Boolean> c) { this.pauseControl = c; }

    private void confirmClose() {
        int choice = JOptionPane.showConfirmDialog(this,
                "This will exit the current attack", "Warning",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.OK_OPTION) {
            if (onClose != null) onClose.run();
            dispose();
        }
    }

    private Runnable onClose;
    public void setOnClose(Runnable r) { this.onClose = r; }

    /** Set the total number of requests expected. */
    public void setTotal(int total) {
        SwingUtilities.invokeLater(() -> {
            progress.setMaximum(Math.max(total, 1));
            progress.setValue(0);
            status.setText("0 of " + total + " requests");
        });
    }

    /** Append one completed result. Safe to call off the EDT. */
    public void addResult(Result r) {
        SwingUtilities.invokeLater(() -> {
            rows.add(r);
            Object[] row = new Object[model.getColumnCount()];
            row[0] = r.number();
            row[1] = r.payload();
            row[2] = r.status() > 0 ? r.status() : null;
            row[3] = r.response() != null ? r.length() : null;
            row[4] = r.millis();
            row[5] = r.error() == null ? "" : r.error();
            for (int i = 0; i < r.grepHits().length && fixedColumns + i < row.length; i++) {
                row[fixedColumns + i] = r.grepHits()[i];
            }
            model.addRow(row);
            progress.setValue(progress.getValue() + 1);
            status.setText(model.getRowCount() + " of " + progress.getMaximum() + " requests");
            if (model.getRowCount() == 1) table.setRowSelectionInterval(0, 0);
        });
    }

    public void finished() {
        SwingUtilities.invokeLater(() ->
                status.setText("finished - " + model.getRowCount() + " requests"));
    }

    private void showSelected() {
        int view = table.getSelectedRow();
        if (view < 0) return;
        int modelRow = table.convertRowIndexToModel(view);
        if (modelRow < 0 || modelRow >= rows.size()) return;
        Result r = rows.get(modelRow);
        reqEditor.setMessage(r.svc(), r.request());
        if (r.response() != null) respEditor.setMessage(r.svc(), r.response(), r.request());
        else respEditor.setMessage(r.svc(), new HttpMessage(new byte[0]));
    }
}
