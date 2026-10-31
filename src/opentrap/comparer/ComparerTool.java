package opentrap.comparer;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Holds a set of loaded items and compares any two of them. Both lists are
 * views of the same items: the user picks one row in each, then compares.
 */
public class ComparerTool implements ToolModule {

    private static final int PREVIEW_CHARS = 120;

    /** Text colour for the row the other list has already taken. */
    private static final Color TAKEN = new Color(0xA8, 0xAE, 0xB8);

    /** One loaded blob, numbered in the order it arrived. */
    record Item(int id, byte[] data) {}

    private final List<Item> items = new ArrayList<>();
    private final ItemModel model = new ItemModel();
    private final JTable firstList = new JTable(model);
    private final JTable secondList = new JTable(model);
    private final JPanel firstCards = new JPanel(new CardLayout());
    private final JPanel secondCards = new JPanel(new CardLayout());
    private final JButton words = compareButton("Words");
    private final JButton bytes = compareButton("Bytes");
    private final JPanel root = new JPanel(new BorderLayout(8, 0));

    private SuiteContext ctx;
    private int nextId = 1;

    public ComparerTool() {
        cards(firstCards, firstList);
        cards(secondCards, secondList);

        // Heading and a one-line description, as in the reference.
        JTextArea intro = Ui.note("This function lets you do a word- or byte-level comparison "
                + "between different data. You can load, paste, or send data here from other "
                + "tools and then select the comparison you want to perform.");
        intro.setMaximumSize(new Dimension(Integer.MAX_VALUE, intro.getPreferredSize().height));
        JPanel headRow = new JPanel(new BorderLayout());
        headRow.setOpaque(false);
        headRow.add(Ui.heading("Comparer"), BorderLayout.WEST);
        headRow.add(opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.help(20, opentrap.ui.Theme.MUTED), "About Comparer", () ->
                JOptionPane.showMessageDialog(root, "Pick one item in each list, then compare them by words or by bytes.", "Comparer", JOptionPane.INFORMATION_MESSAGE)), BorderLayout.EAST);
        JPanel head = Ui.vbox(headRow, Box.createVerticalStrut(4), intro,
                Box.createVerticalStrut(10));

        JPanel lists = new JPanel(new GridLayout(2, 1, 0, 10));
        lists.setOpaque(false);
        lists.add(titled("Select item 1:", firstCards));
        lists.add(titled("Select item 2:", secondCards));

        root.setBorder(Ui.pad(8, 8, 8, 8));
        root.add(head, BorderLayout.NORTH);
        root.add(lists, BorderLayout.CENTER);
        root.add(buttons(), BorderLayout.EAST);

        dim(firstList, secondList);
        dim(secondList, firstList);

        // Each list repaints when the other's selection moves, so the row that
        // is spoken for is always the greyed one.
        firstList.getSelectionModel().addListSelectionListener(e -> {
            refreshButtons();
            secondList.repaint();
        });
        secondList.getSelectionModel().addListSelectionListener(e -> {
            refreshButtons();
            firstList.repaint();
        });
        refreshButtons();
        showCards();
    }

    @Override public String name() { return "comparer"; }

    @Override public Tab[] tabs() { return new Tab[]{ new Tab("comparer", root) }; }

    @Override public java.io.Serializable saveState() {
        java.util.ArrayList<byte[]> data = new java.util.ArrayList<>();
        for (Item it : items) data.add(it.data());
        return data.isEmpty() ? null : data;
    }

    @Override public void restoreState(java.io.Serializable state) {
        if (state instanceof java.util.List<?> list) {
            for (Object o : list) if (o instanceof byte[] b) add(b);
        }
    }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        ctx.registerSink("comparer", new SuiteContext.MessageSink() {
            @Override public void receive(HttpService svc, HttpMessage req,
                                         HttpMessage resp, String caption) {
                // One send is one item. Senders honour singleItem() and pass a
                // single half; if both arrive anyway the request wins, so a
                // stray caller can never load two rows from one click.
                byte[] data = req != null ? req.raw() : resp != null ? resp.raw() : null;
                if (data == null) return;
                SwingUtilities.invokeLater(() -> add(data));
            }

            @Override public boolean singleItem() { return true; }
        });
    }

    // ---- layout ----

    private void cards(JPanel cards, JTable table) {
        Ui.styleTable(table);
        Ui.setWidths(table, 60, 120, 1000);
        // the two narrow columns are pinned so the preview column takes the rest
        pin(table.getColumnModel().getColumn(0), 60);
        pin(table.getColumnModel().getColumn(1), 120);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // The table is always shown, with its headers, even while empty.
        cards.add(Ui.scroll(table), "items");
    }

    private static void pin(TableColumn column, int width) {
        column.setMinWidth(width);
        column.setMaxWidth(width);
    }

    /**
     * Grey, in {@code table}, whichever row {@code other} has selected. An item
     * cannot be compared with itself, so showing it as spoken for saves the user
     * picking it and finding the compare buttons dead.
     */
    private void dim(JTable table, JTable other) {
        for (int column = 0; column < model.getColumnCount(); column++) {
            TableCellRenderer base = table.getDefaultRenderer(model.getColumnClass(column));
            table.getColumnModel().getColumn(column).setCellRenderer(
                    (t, value, selected, focus, row, col) -> {
                        Component c = base.getTableCellRendererComponent(
                                t, value, selected, focus, row, col);
                        c.setForeground(!selected && row == other.getSelectedRow()
                                ? TAKEN : opentrap.ui.Theme.TEXT);
                        return c;
                    });
        }
    }

    private static JPanel titled(String title, JComponent body) {
        JPanel p = new JPanel(new BorderLayout(0, 3));
        p.setOpaque(false);
        p.add(Ui.label(title), BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    /** The right-hand column: item actions at the top, the compare choice at the bottom. */
    private JComponent buttons() {
        JButton paste = Ui.button("Paste", "add the clipboard contents as an item");
        JButton load = Ui.button("Load", "add the contents of a file as an item");
        JButton remove = Ui.button("Remove", "remove the selected items");
        JButton clear = Ui.button("Clear", "remove every item");
        paste.addActionListener(e -> paste());
        load.addActionListener(e -> load());
        remove.addActionListener(e -> remove());
        clear.addActionListener(e -> clear());
        words.addActionListener(e -> compare(true));
        bytes.addActionListener(e -> compare(false));

        JPanel actions = new JPanel(new GridLayout(4, 1, 0, 4));
        actions.setOpaque(false);
        actions.add(paste);
        actions.add(load);
        actions.add(remove);
        actions.add(clear);

        JPanel compares = new JPanel(new GridLayout(2, 1, 0, 4));
        compares.setOpaque(false);
        compares.add(words);
        compares.add(bytes);
        JPanel compareBox = Ui.vbox(Ui.label("Compare ..."), Box.createVerticalStrut(2), compares);

        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setBorder(Ui.pad(22, 8, 0, 0));
        holder.add(actions, BorderLayout.NORTH);
        holder.add(compareBox, BorderLayout.SOUTH);
        return holder;
    }

    private static JButton compareButton(String text) {
        JButton b = Ui.button(text);
        b.setFont(Ui.BOLD);
        b.setEnabled(false);
        return b;
    }

    // ---- items ----

    private void add(byte[] data) {
        if (data == null) return;
        items.add(new Item(nextId++, data));
        int row = items.size() - 1;
        model.fireTableRowsInserted(row, row);
        showCards();
        select(row);
        refreshButtons();
    }

    /**
     * Point a list at the item that just arrived. The first fills item 1 and
     * every later one becomes item 2, so two sends in a row are ready to compare
     * without the user having to click into both lists first.
     */
    private void select(int row) {
        if (firstList.getSelectedRow() < 0) {
            firstList.setRowSelectionInterval(row, row);
        } else if (firstList.getSelectedRow() != row) {
            secondList.setRowSelectionInterval(row, row);
        }
    }

    private void paste() {
        try {
            Object text = Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
            if (text instanceof String s && !s.isEmpty()) add(Bytes.bytes(s));
        } catch (Exception e) {
            problem("cannot read the clipboard: " + e);
        }
    }

    private void load() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("load file");
        if (chooser.showOpenDialog(root) != JFileChooser.APPROVE_OPTION) return;
        try {
            add(Files.readAllBytes(chooser.getSelectedFile().toPath()));
        } catch (IOException e) {
            problem("cannot read " + chooser.getSelectedFile().getName() + ": " + e.getMessage());
        }
    }

    private void remove() {
        int first = firstList.getSelectedRow();
        int second = secondList.getSelectedRow();
        int high = Math.max(first, second);
        int low = Math.min(first, second);
        if (high >= 0) items.remove(high);
        if (low >= 0 && low != high) items.remove(low);
        firstList.clearSelection();
        secondList.clearSelection();
        model.fireTableDataChanged();
        showCards();
        refreshButtons();
    }

    private void clear() {
        items.clear();
        firstList.clearSelection();
        secondList.clearSelection();
        model.fireTableDataChanged();
        showCards();
        refreshButtons();
    }

    private void showCards() {
        String card = items.isEmpty() ? "empty" : "items";
        ((CardLayout) firstCards.getLayout()).show(firstCards, card);
        ((CardLayout) secondCards.getLayout()).show(secondCards, card);
    }

    private void refreshButtons() {
        int first = firstList.getSelectedRow();
        int second = secondList.getSelectedRow();
        boolean ready = first >= 0 && second >= 0 && first != second;
        words.setEnabled(ready);
        bytes.setEnabled(ready);
    }

    private void compare(boolean asWords) {
        int first = firstList.getSelectedRow();
        int second = secondList.getSelectedRow();
        if (first < 0 || second < 0 || first == second) return;
        CompareWindow w = new CompareWindow(items.get(first), items.get(second), asWords,
                ctx == null ? null : ctx.frame());
        w.setVisible(true);
    }

    private void problem(String message) {
        if (ctx != null) ctx.alert(name(), message);
        else JOptionPane.showMessageDialog(root, message, "comparer", JOptionPane.WARNING_MESSAGE);
    }

    /** A printable sketch of an item, enough to tell two of them apart. */
    private static String preview(byte[] data) {
        StringBuilder sb = new StringBuilder(PREVIEW_CHARS + 4);
        int i = 0;
        while (i < data.length && i < PREVIEW_CHARS) {
            int v = data[i++] & 0xff;
            sb.append(v >= 32 && v <= 126 ? (char) v : '.');
        }
        if (i < data.length) sb.append(" ...");
        return sb.toString();
    }

    /** Shared by both lists, so an item added anywhere shows up twice. */
    private final class ItemModel extends AbstractTableModel {

        private final String[] columns = {"#", "length", "data"};

        @Override public int getRowCount() { return items.size(); }

        @Override public int getColumnCount() { return columns.length; }

        @Override public String getColumnName(int column) { return columns[column]; }

        @Override public Class<?> getColumnClass(int column) {
            return column == 2 ? String.class : Integer.class;
        }

        @Override public Object getValueAt(int row, int column) {
            Item item = items.get(row);
            return switch (column) {
                case 0 -> item.id();
                case 1 -> item.data().length;
                default -> preview(item.data());
            };
        }
    }
}
