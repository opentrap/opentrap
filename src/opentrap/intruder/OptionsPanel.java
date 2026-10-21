package opentrap.intruder;

import opentrap.core.HttpMode;
import opentrap.ui.OptionsPage;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The options tab: concurrency, redirect handling, and the grep-match list
 * whose expressions each become a boolean column in the results table.
 */
public class OptionsPanel extends OptionsPage {

    private static final String[] DEFAULT_GREP = {"error", "exception", "sql", "warning", "fail"};

    private final JTextField threads = Ui.field("5", 4);
    private final JTextField throttle = Ui.field("0", 6);
    private final JComboBox<HttpMode> mode = Ui.combo(HttpMode.values());
    private final JCheckBox followRedirects = Ui.check("follow redirects", false);

    private final DefaultTableModel grepModel =
            new DefaultTableModel(new Object[]{"expression"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
    private final JTable grepTable = new JTable(grepModel);
    private final JTextField grepEntry = Ui.field("", 16);

    public OptionsPanel() {
        for (String g : DEFAULT_GREP) grepModel.addRow(new Object[]{g});
        Ui.styleTable(grepTable);
        Ui.setWidths(grepTable, 260);
        JScrollPane sp = Ui.scroll(grepTable);
        sp.setPreferredSize(new Dimension(280, 150));

        JButton add    = Ui.button("add");
        JButton load   = Ui.button("load ...", "load expressions from a file");
        JButton paste  = Ui.button("paste");
        JButton delete = Ui.button("delete");
        JButton clear  = Ui.button("clear");
        add.addActionListener(e -> addEntry());
        grepEntry.addActionListener(e -> addEntry());
        load.addActionListener(e -> load());
        paste.addActionListener(e -> paste());
        delete.addActionListener(e -> {
            int[] rows = grepTable.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) grepModel.removeRow(rows[i]);
        });
        clear.addActionListener(e -> grepModel.setRowCount(0));

        JPanel grepPanel = new JPanel(new BorderLayout(8, 4));
        grepPanel.setOpaque(false);
        grepPanel.add(sp, BorderLayout.CENTER);
        grepPanel.add(Ui.buttonColumn(add, load, paste, delete, clear), BorderLayout.EAST);
        grepPanel.add(Ui.row(grepEntry, add), BorderLayout.SOUTH);

        section("timing",
                Ui.note("The number of concurrent request threads used to run the attack, "
                        + "and an optional pause between requests on each thread."),
                OptionsPage.fieldRow("number of threads:", threads, ""),
                OptionsPage.fieldRow("throttle (ms between requests):", throttle, ""));
        section("protocol",
                Ui.note("HTTP/1 is raw and byte-exact; HTTP/2 and auto use the JDK transport."),
                Ui.row(Ui.label("send requests over:"), mode));
        section("redirects", followRedirects);
        section("grep - match",
                Ui.note("Flag result items whose response contains these expressions. Each "
                        + "expression adds a column to the results table."),
                grepPanel);
    }

    private void addEntry() {
        String t = grepEntry.getText().trim();
        if (t.isEmpty()) return;
        grepModel.addRow(new Object[]{t});
        grepEntry.setText("");
    }

    private void load() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (BufferedReader r = new BufferedReader(new FileReader(fc.getSelectedFile()))) {
            String line;
            while ((line = r.readLine()) != null) if (!line.isEmpty()) grepModel.addRow(new Object[]{line});
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "could not read file: " + ex.getMessage());
        }
    }

    private void paste() {
        try {
            String data = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (data == null) return;
            for (String line : data.split("\r\n|\r|\n")) if (!line.isEmpty()) grepModel.addRow(new Object[]{line});
        } catch (Exception ignored) {}
    }

    // ---------------- accessors ----------------

    public int threads() {
        try { return Math.max(1, Integer.parseInt(threads.getText().trim())); }
        catch (Exception e) { return 5; }
    }

    public boolean followRedirects() { return followRedirects.isSelected(); }

    public long throttleMs() {
        try { return Math.max(0, Long.parseLong(throttle.getText().trim())); }
        catch (Exception e) { return 0; }
    }

    public HttpMode mode() {
        HttpMode m = (HttpMode) mode.getSelectedItem();
        return m == null ? HttpMode.HTTP_1 : m;
    }

    public List<String> grepExpressions() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < grepModel.getRowCount(); i++) {
            String s = String.valueOf(grepModel.getValueAt(i, 0));
            if (s != null && !s.isEmpty()) out.add(s);
        }
        return out;
    }
}
