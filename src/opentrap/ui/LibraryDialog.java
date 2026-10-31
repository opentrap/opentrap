package opentrap.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * "Load from configuration library": the saved items of one kind (scopes,
 * logins, scan configurations) with Built-in / Custom filter chips, a search
 * box and a table of Name, Functions, Last used and Built-in. Items are plain
 * text files under {@code ~/.opentrap/library/<kind>/}, so they can be shared.
 */
public final class LibraryDialog {

    private LibraryDialog() {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm dd MMM yyyy").withZone(ZoneId.systemDefault());

    public static Path dir(String kind) { return Path.of(System.getProperty("user.home", "."), ".opentrap", "library", kind); }

    public static List<String> names(String kind) {
        List<String> out = new ArrayList<>();
        Path d = dir(kind);
        if (!Files.isDirectory(d)) return out;
        try (var ds = Files.newDirectoryStream(d, "*.txt")) {
            for (Path p : ds) out.add(p.getFileName().toString().replaceAll("\\.txt$", ""));
        } catch (IOException ignore) { }
        java.util.Collections.sort(out);
        return out;
    }

    public static String read(String kind, String name) {
        try { return Files.readString(dir(kind).resolve(name + ".txt")); } catch (IOException e) { return null; }
    }

    public static void write(String kind, String name, String text) {
        try {
            Files.createDirectories(dir(kind));
            Files.writeString(dir(kind).resolve(name.replaceAll("[^A-Za-z0-9 _.-]", "_") + ".txt"), text);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "Could not save to the library: " + e.getMessage(), "Save to library", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Show the chooser; returns the chosen name or null. */
    public static String pick(Component parent, String kind, String title) {
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        final String[] out = {null};

        JToggleButton builtIn = new JToggleButton("Built-in");
        JToggleButton custom = new JToggleButton("Custom");
        builtIn.setFont(Ui.PLAIN); custom.setFont(Ui.PLAIN);
        builtIn.setFocusable(false); custom.setFocusable(false);
        custom.setSelected(true);
        JTextField search = Ui.field("", 18);
        search.putClientProperty("JTextField.placeholderText", "Search");
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        bar.setOpaque(false);
        bar.add(new JLabel(Glyphs.funnel(14, Theme.MUTED)));
        bar.add(Ui.label("Filter"));
        bar.add(builtIn); bar.add(custom);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(search);

        DefaultTableModel model = new DefaultTableModel(new Object[]{"Name", "Functions", "Last used", "Built-in"}, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        JTable table = new JTable(model);
        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Runnable reload = () -> {
            model.setRowCount(0);
            if (!custom.isSelected()) return;
            String q = search.getText().strip().toLowerCase();
            for (String n : names(kind)) {
                if (!q.isEmpty() && !n.toLowerCase().contains(q)) continue;
                String used = "";
                try { used = FMT.format(Instant.ofEpochMilli(Files.getLastModifiedTime(dir(kind).resolve(n + ".txt")).toMillis())); } catch (IOException ignore) { }
                model.addRow(new Object[]{n, kindLabel(kind), used, ""});
            }
        };
        builtIn.addActionListener(e -> reload.run());
        custom.addActionListener(e -> reload.run());
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { reload.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { reload.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { reload.run(); }
        });
        reload.run();
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) { out[0] = String.valueOf(model.getValueAt(table.getSelectedRow(), 0)); d.dispose(); }
            }
        });

        JButton select = Ui.primaryButton("Select", "use the selected item");
        JButton close = Ui.button("Close");
        select.addActionListener(e -> { int r = table.getSelectedRow(); if (r >= 0) { out[0] = String.valueOf(model.getValueAt(r, 0)); d.dispose(); } });
        close.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(select); buttons.add(close);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setBorder(Ui.pad(12, 14, 4, 14));
        body.add(bar, BorderLayout.NORTH);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(700, 360));
        body.add(sp, BorderLayout.CENTER);
        JPanel root = new JPanel(new BorderLayout());
        root.add(body, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
        return out[0];
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case "scope" -> "Scope";
            case "login" -> "Application login";
            case "scan" -> "Crawling & Auditing";
            default -> kind;
        };
    }
}
