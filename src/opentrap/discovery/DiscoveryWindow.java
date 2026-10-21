package opentrap.discovery;

import opentrap.core.*;
import opentrap.ui.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The content-discovery (forced browse) engagement tool: a popup window opened
 * from the right-click "send to content discovery" action, seeded with the
 * chosen host. Finds unlinked paths and files; crawling remains ZAP's job.
 */
public class DiscoveryWindow extends JFrame {

    private final SuiteContext ctx;
    private final ContentDiscovery engine;

    private final JTextField target = Ui.field("http://", 36);
    private final JTextField extensions = Ui.field("php,bak,txt,zip,old", 20);
    private final JTextField threads = Ui.field("10", 4);
    private final JTextField throttle = Ui.field("0", 6);
    private final JTextArea words = new JTextArea(String.join("\n", Wordlist.DEFAULT));
    private final ResultModel results = new ResultModel();
    private final JProgressBar progress = new JProgressBar();
    private final JLabel status = Ui.label("ready");
    private JButton start, stop;

    public DiscoveryWindow(SuiteContext ctx, HttpService svc) {
        super("content discovery");
        this.ctx = ctx;
        this.engine = new ContentDiscovery(ctx);
        if (svc != null) target.setText(svc.url());

        words.setFont(Ui.MONO);
        JButton load = Ui.button("load wordlist...", "replace the wordlist from a file");
        load.addActionListener(e -> loadWordlist());
        start = Ui.primaryButton("start", "begin content discovery");
        stop = Ui.button("stop");
        stop.setEnabled(false);
        start.addActionListener(e -> start());
        stop.addActionListener(e -> stop());

        JPanel top = Ui.form(
                "target", target,
                "extensions (comma-separated)", extensions,
                "threads", threads,
                "throttle (ms)", throttle);
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controls.add(start); controls.add(stop); controls.add(load); controls.add(status);

        JPanel left = new JPanel(new BorderLayout());
        left.add(top, BorderLayout.NORTH);
        left.add(controls, BorderLayout.CENTER);
        JPanel wordPanel = new JPanel(new BorderLayout());
        wordPanel.add(Ui.bold(" wordlist"), BorderLayout.NORTH);
        wordPanel.add(Ui.scroll(words), BorderLayout.CENTER);

        JTable table = new JTable(results);
        Ui.styleTable(table);
        Ui.distributeWidths(table, 320, 70, 90);

        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, left, wordPanel);
        leftSplit.setDividerSize(5);
        Ui.balance(leftSplit, 0.35);
        JPanel resultsPanel = new JPanel(new BorderLayout());
        resultsPanel.add(Ui.bold(" discovered content"), BorderLayout.NORTH);
        resultsPanel.add(Ui.scroll(table), BorderLayout.CENTER);
        resultsPanel.add(progress, BorderLayout.SOUTH);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftSplit, resultsPanel);
        split.setDividerSize(5);
        Ui.balance(split, 0.42);

        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
        setSize(900, 560);
        setLocationRelativeTo(ctx.frame());
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) { engine.stop(); }
        });
    }

    private void loadWordlist() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try { words.setText(java.nio.file.Files.readString(fc.getSelectedFile().toPath())); }
        catch (Exception ex) { JOptionPane.showMessageDialog(this, "could not read file: " + ex.getMessage()); }
    }

    private void start() {
        HttpService base;
        try {
            String url = target.getText().trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) url = "http://" + url;
            base = HttpService.parse(url);
            if (base == null || base.host().isEmpty()) throw new IllegalArgumentException("bad target");
        } catch (Exception e) {
            status.setText("enter a valid target, e.g. http://host");
            return;
        }
        List<String> ext = new ArrayList<>();
        for (String s : extensions.getText().split(",")) if (!s.isBlank()) ext.add(s.trim());
        List<String> lines = new ArrayList<>();
        for (String s : words.getText().split("\n")) lines.add(s);
        List<String> paths = Wordlist.candidates(lines, ext);

        int th; long thr;
        try { th = Math.max(1, Integer.parseInt(threads.getText().trim())); } catch (Exception e) { th = 10; }
        try { thr = Math.max(0, Long.parseLong(throttle.getText().trim())); } catch (Exception e) { thr = 0; }

        results.clear();
        progress.setMaximum(Math.max(1, paths.size()));
        progress.setValue(0);
        status.setText("running " + paths.size() + " requests...");
        start.setEnabled(false);
        stop.setEnabled(true);

        engine.start(base, paths, th, thr, new ContentDiscovery.Listener() {
            @Override public void hit(String path, int statusCode, int length) {
                SwingUtilities.invokeLater(() -> results.add(path, statusCode, length));
            }
            @Override public void progress(int done, int total) {
                SwingUtilities.invokeLater(() -> {
                    progress.setValue(done);
                    status.setText(done + " / " + total + " (" + results.getRowCount() + " found)");
                });
            }
            @Override public void finished() {
                SwingUtilities.invokeLater(() -> {
                    status.setText("finished - " + results.getRowCount() + " found");
                    start.setEnabled(true);
                    stop.setEnabled(false);
                });
            }
        });
    }

    private void stop() {
        engine.stop();
        status.setText("stopped");
        start.setEnabled(true);
        stop.setEnabled(false);
    }

    private static final class ResultModel extends AbstractTableModel {
        private final String[] cols = {"path", "status", "length"};
        private final List<Object[]> rows = new ArrayList<>();
        void add(String path, int status, int length) { rows.add(new Object[]{path, status, length}); fireTableRowsInserted(rows.size() - 1, rows.size() - 1); }
        void clear() { rows.clear(); fireTableDataChanged(); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? String.class : Integer.class; }
        @Override public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
    }
}
