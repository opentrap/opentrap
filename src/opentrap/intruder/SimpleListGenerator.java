package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.List;

/** A hand-built list of payload strings. */
class SimpleListGenerator extends AbstractGenerator {

    private final DefaultTableModel model =
            new DefaultTableModel(new Object[]{"item"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
    private final JTable table = new JTable(model);
    private final JTextField entry = Ui.field("", 18);
    private final JLabel countLabel = Ui.label("0 items");
    private final JPanel panel;

    SimpleListGenerator() {
        Ui.styleTable(table);
        Ui.setWidths(table, 300);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(360, 150));

        JButton add    = Ui.button("Add");
        JButton load   = Ui.button("Load ...", "load payloads from a file");
        JButton paste  = Ui.button("Paste");
        JButton delete = Ui.button("Remove");
        JButton clear  = Ui.button("Clear");
        JButton dedupe = Ui.button("Deduplicate", "remove repeated items, keeping the first of each");

        entry.putClientProperty("JTextField.placeholderText", "Enter a new item");
        add.addActionListener(e -> addEntry());
        entry.addActionListener(e -> addEntry());
        load.addActionListener(e -> load());
        paste.addActionListener(e -> paste());
        delete.addActionListener(e -> deleteSelected());
        clear.addActionListener(e -> { model.setRowCount(0); changed(); });
        dedupe.addActionListener(e -> deduplicate());

        // As in the reference: the button column to the left of the list, the
        // entry field with its Add button beneath, and a built-in list chooser
        // under that.
        JPanel buttons = Ui.buttonColumn(paste, load, delete, clear, dedupe);
        JPanel addRow = Ui.row(entry, add);
        JComboBox<String> fromList = Ui.combo(new String[]{
                "Add from list ...", "Short words", "Common directories", "Common filenames",
                "HTTP methods", "Fuzzing - quick", "Numbers 0-99", "User agents"});
        fromList.addActionListener(e -> {
            int i = fromList.getSelectedIndex();
            if (i > 0) { addAll(BUILT_IN[i - 1]); fromList.setSelectedIndex(0); }
        });
        JPanel below = new JPanel(new BorderLayout());
        below.setOpaque(false);
        below.add(addRow, BorderLayout.NORTH);
        below.add(Ui.row(fromList), BorderLayout.SOUTH);

        panel = new JPanel(new BorderLayout(8, 4));
        panel.setOpaque(false);
        JPanel list = new JPanel(new BorderLayout());
        list.setOpaque(false);
        list.add(sp, BorderLayout.CENTER);
        list.add(below, BorderLayout.SOUTH);
        panel.add(list, BorderLayout.CENTER);
        panel.add(buttons, BorderLayout.WEST);
        JPanel head = new JPanel(new java.awt.BorderLayout());
        head.setOpaque(false);
        head.add(Ui.heading("Payload configuration"), java.awt.BorderLayout.WEST);
        head.add(countLabel, java.awt.BorderLayout.EAST);
        panel.add(head, BorderLayout.NORTH);
    }

    /** The built-in lists the "Add from list ..." chooser offers. */
    private static final String[][] BUILT_IN = {
        {"a", "b", "c", "d", "e", "f", "g", "test", "foo", "bar"},
        {"admin", "api", "assets", "backup", "cgi-bin", "config", "css", "images", "js", "logs",
         "private", "public", "static", "tmp", "uploads"},
        {"index.html", "index.php", "login.php", "admin.php", "config.php", "web.config",
         ".env", ".git/HEAD", "robots.txt", "sitemap.xml", "backup.zip"},
        {"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT"},
        {"'", "\"", "<script>", "../", "..\\", "%00", "${7*7}", "{{7*7}}", "|id", ";id", "`id`"},
        null,   // filled below: 0-99
        {"Mozilla/5.0", "curl/8.5.0", "python-requests/2.31.0", "Googlebot/2.1", "Wget/1.21"},
    };
    static {
        String[] nums = new String[100];
        for (int i = 0; i < 100; i++) nums[i] = String.valueOf(i);
        BUILT_IN[5] = nums;
    }

    private void addAll(String[] items) {
        for (String i : items) model.addRow(new Object[]{i});
        changed();
    }

    /** Drop repeated items, keeping the first of each. */
    private void deduplicate() {
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (int r = 0; r < model.getRowCount(); r++) seen.add(String.valueOf(model.getValueAt(r, 0)));
        model.setRowCount(0);
        for (String v : seen) model.addRow(new Object[]{v});
        changed();
    }

    private void addEntry() {
        String t = entry.getText();
        if (t.isEmpty()) return;
        model.addRow(new Object[]{t});
        entry.setText("");
        changed();
    }

    private void deleteSelected() {
        int[] rows = table.getSelectedRows();
        for (int i = rows.length - 1; i >= 0; i--) model.removeRow(rows[i]);
        changed();
    }

    private void load() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(panel) != JFileChooser.APPROVE_OPTION) return;
        try (BufferedReader r = new BufferedReader(new FileReader(fc.getSelectedFile()))) {
            String line;
            while ((line = r.readLine()) != null) model.addRow(new Object[]{line});
            changed();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(panel, "could not read file: " + ex.getMessage());
        }
    }

    private void paste() {
        try {
            String data = (String) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
            if (data == null) return;
            for (String line : data.split("\r\n|\r|\n")) model.addRow(new Object[]{line});
            changed();
        } catch (Exception ignored) {}
    }

    private void changed() {
        countLabel.setText(model.getRowCount() + " items");
        fire();
    }

    @Override public String label() { return " simple list"; }
    @Override public JComponent config() { return panel; }

    @Override public List<String> generate() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < model.getRowCount() && out.size() < CAP; i++) {
            out.add(String.valueOf(model.getValueAt(i, 0)));
        }
        return out;
    }

    @Override public int count() { return model.getRowCount(); }
}
