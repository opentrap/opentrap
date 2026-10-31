package opentrap.extensions;

import opentrap.ui.Glyphs;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Extensions > Store: a searchable list of the engine's marketplace add-ons on
 * the left and the selected add-on's detail card on the right: name and authors,
 * rating, popularity, version and update date (when known), description,
 * features, usage, source and estimated system impact, with Install at the top.
 */
public final class StorePanel extends JPanel {

    private final List<Catalogue.Entry> all = Catalogue.load();
    private final List<Catalogue.Entry> shown = new ArrayList<>(all);
    private final java.util.Set<String> installed = new java.util.HashSet<>();
    private final ListModel model = new ListModel();
    private final JTable list = new JTable(model);
    private final JPanel detail = new JPanel(new BorderLayout());

    public StorePanel() {
        super(new BorderLayout());
        setOpaque(false);

        // ---- left: search + name list ----
        JTextField search = Ui.field("", 16);
        search.putClientProperty("JTextField.placeholderText", "Search");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(search.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(search.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(search.getText()); }
        });
        Ui.styleTable(list);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setShowGrid(false);
        list.getColumnModel().getColumn(0).setMaxWidth(24);
        list.getColumnModel().getColumn(0).setCellRenderer(new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, "", sel, f, r, c);
                setIcon(Boolean.TRUE.equals(v) ? Glyphs.bookmark(14, Ui.ACCENT, true) : null);
                setHorizontalAlignment(CENTER);
                return this;
            }
        });
        list.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) showSelected();
        });
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setOpaque(false);
        left.setBorder(Ui.pad(10, 10, 10, 6));
        JPanel searchRow = new JPanel(new BorderLayout());
        searchRow.setOpaque(false);
        searchRow.add(search, BorderLayout.CENTER);
        left.add(searchRow, BorderLayout.NORTH);
        JScrollPane sp = Ui.scroll(list);
        sp.setPreferredSize(new Dimension(260, 10));
        left.add(sp, BorderLayout.CENTER);

        // ---- right: detail card ----
        detail.setOpaque(false);
        detail.setBorder(Ui.pad(10, 12, 10, 12));
        showEmpty();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, detail);
        split.setDividerSize(5);
        split.setBorder(null);
        split.setDividerLocation(290);
        add(split, BorderLayout.CENTER);
        if (!shown.isEmpty()) list.setRowSelectionInterval(0, 0);
    }

    private void filter(String q) {
        String needle = q == null ? "" : q.strip().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Catalogue.Entry x : all) {
            if (needle.isEmpty() || x.name().toLowerCase(Locale.ROOT).contains(needle)
                    || x.description().toLowerCase(Locale.ROOT).contains(needle)
                    || x.id().toLowerCase(Locale.ROOT).contains(needle)) shown.add(x);
        }
        model.fireTableDataChanged();
        if (!shown.isEmpty()) list.setRowSelectionInterval(0, 0); else showEmpty();
    }

    private Catalogue.Entry selected() {
        int r = list.getSelectedRow();
        return r < 0 || r >= shown.size() ? null : shown.get(r);
    }

    private void showEmpty() {
        detail.removeAll();
        JLabel l = Ui.label(all.isEmpty()
                ? "The catalogue is empty. Add entries to " + Catalogue.userFile()
                : "Select an extension to see its details.");
        l.setForeground(Theme.MUTED);
        l.setHorizontalAlignment(SwingConstants.CENTER);
        detail.add(l, BorderLayout.CENTER);
        detail.revalidate();
        detail.repaint();
    }

    private void showSelected() {
        Catalogue.Entry x = selected();
        if (x == null) { showEmpty(); return; }
        detail.removeAll();
        detail.add(Ui.scroll(card(x)), BorderLayout.CENTER);
        detail.revalidate();
        detail.repaint();
    }

    /** The detail card, laid out top to bottom like the reference store. */
    private JComponent card(Catalogue.Entry x) {
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        col.setBorder(Ui.pad(4, 6, 20, 6));

        // Title row: icon + name, Submit rating + Install on the right.
        JPanel title = new JPanel(new BorderLayout(10, 0));
        title.setOpaque(false);
        JLabel name = new JLabel(x.name(), Glyphs.bolt(18, Ui.ACCENT), SwingConstants.LEADING);
        name.setFont(new Font("Dialog", Font.BOLD, 17));
        name.setIconTextGap(10);
        title.add(name, BorderLayout.WEST);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        JButton rate = Glyphs.button(Glyphs.star(14, Theme.MUTED, false), "rate this extension", null);
        rate.setText("Submit rating");
        rate.setFont(Ui.PLAIN);
        rate.setForeground(Theme.MUTED);
        rate.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Ratings are collected on the add-on's marketplace page.", "Submit rating", JOptionPane.INFORMATION_MESSAGE));
        boolean isInstalled = installed.contains(x.id());
        JButton install = Ui.primaryButton(isInstalled ? "Installed" : "Install",
                "open the add-on's marketplace page, where the engine's add-on manager installs it");
        install.setEnabled(!isInstalled);
        install.addActionListener(e -> install(x, install));
        actions.add(rate);
        actions.add(install);
        title.add(actions, BorderLayout.EAST);
        add(col, title);

        // Authors line.
        if (!x.authors().isBlank()) {
            JLabel by = Ui.label(x.authors());
            by.setForeground(Theme.MUTED);
            by.setBorder(Ui.pad(2, 28, 0, 0));
            add(col, by);
        }
        add(col, Box.createVerticalStrut(10));

        // Stats row: rating | popularity | version | updated. Only facts we have.
        JPanel stats = new JPanel(new GridLayout(1, 0, 20, 0));
        stats.setOpaque(false);
        if (x.rating() > 0) stats.add(stat("Rating:", stars(x.rating())));
        if (x.popularity() > 0) stats.add(stat("Popularity:", meter(x.popularity())));
        if (!x.version().isBlank()) stats.add(stat("Version:", Ui.label(x.version())));
        if (!x.updated().isBlank()) stats.add(stat("Updated:", Ui.label(x.updated())));
        if (stats.getComponentCount() > 0) {
            add(col, stats);
            add(col, rule());
        }

        // Description, note.
        if (!x.description().isBlank()) add(col, para(x.description()));
        if (!x.note().isBlank()) {
            JTextArea n = para("Note: " + x.note());
            n.setForeground(Ui.ERROR_RED);
            add(col, n);
        }

        if (!x.features().isEmpty()) {
            add(col, Box.createVerticalStrut(8));
            add(col, Ui.bold("Features"));
            for (String f : x.features()) add(col, bullet("•  " + f));
        }
        if (!x.usage().isEmpty()) {
            add(col, Box.createVerticalStrut(8));
            add(col, Ui.bold("Usage"));
            int i = 1;
            for (String u : x.usage()) add(col, bullet((i++) + ".  " + u));
        }

        if (!x.source().isBlank()) {
            add(col, rule());
            JPanel src = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            src.setOpaque(false);
            src.add(Ui.bold("Source:"));
            src.add(link(x.source(), x.source()));
            add(col, src);
        }
        JPanel page = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        page.setOpaque(false);
        page.add(Ui.bold("Marketplace page:"));
        page.add(link(x.page(), x.page()));
        add(col, page);

        add(col, Box.createVerticalStrut(12));
        JPanel impactHead = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        impactHead.setOpaque(false);
        impactHead.add(new JLabel(Glyphs.help(16, Theme.MUTED)));
        impactHead.add(Ui.bold("Estimated system impact"));
        add(col, impactHead);
        JPanel impact = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        impact.setOpaque(false);
        impact.add(Ui.label("Overall:"));
        impact.add(Ui.bold(x.impact().isBlank() ? "Not measured" : x.impact()));
        add(col, impact);
        return col;
    }

    private void install(Catalogue.Entry x, JButton button) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(x.page()));
            }
        } catch (Exception ignore) {
            // fall through to the message
        }
        installed.add(x.id());
        button.setText("Installed");
        button.setEnabled(false);
        model.fireTableDataChanged();
        JOptionPane.showMessageDialog(this,
                "The engine's add-on manager installs marketplace add-ons.\n"
              + "Opened the page for \"" + x.name() + "\"; install it from there, then restart the engine.",
                "Install " + x.name(), JOptionPane.INFORMATION_MESSAGE);
    }

    // ---- small layout helpers ----

    private static void add(JPanel col, Component c) {
        if (c instanceof JComponent jc) {
            jc.setAlignmentX(Component.LEFT_ALIGNMENT);
            Dimension p = jc.getPreferredSize();
            jc.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.height));
        }
        col.add(c);
    }

    private static JPanel stat(String label, Component value) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        p.add(Ui.bold(label));
        p.add(value);
        return p;
    }

    private static JComponent stars(double rating) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 1, 0));
        p.setOpaque(false);
        for (int i = 1; i <= 5; i++) p.add(new JLabel(Glyphs.star(14, Ui.ACCENT, i <= Math.round(rating))));
        return p;
    }

    private static JComponent meter(int pct) {
        JProgressBar bar = new JProgressBar(0, 100);
        bar.setValue(Math.max(0, Math.min(100, pct)));
        bar.setPreferredSize(new Dimension(110, 6));
        bar.setBorderPainted(false);
        bar.setForeground(Ui.ACCENT);
        return bar;
    }

    private static JComponent rule() {
        JSeparator s = new JSeparator();
        s.setBorder(Ui.pad(10, 0, 10, 0));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(Ui.pad(10, 0, 10, 0));
        wrap.add(s, BorderLayout.CENTER);
        return wrap;
    }

    private static JTextArea para(String text) {
        JTextArea a = Ui.note(text);
        Ui.sizeNote(a, 760);
        return a;
    }

    private static JTextArea bullet(String text) {
        JTextArea a = Ui.note(text);
        a.setBorder(Ui.pad(0, 14, 0, 0));
        Ui.sizeNote(a, 740);
        return a;
    }

    private static JComponent link(String text, String url) {
        JLabel l = new JLabel("<html><u>" + text + "</u></html>");
        l.setFont(Ui.PLAIN);
        l.setForeground(new Color(0x1A, 0x5F, 0xB4));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                try { Desktop.getDesktop().browse(java.net.URI.create(url)); } catch (Exception ignore) { }
            }
        });
        return l;
    }

    private final class ListModel extends AbstractTableModel {
        @Override public int getRowCount() { return shown.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int c) { return c == 0 ? "" : "Name"; }
        @Override public Object getValueAt(int r, int c) {
            Catalogue.Entry x = shown.get(r);
            return c == 0 ? installed.contains(x.id()) : x.name();
        }
    }
}
