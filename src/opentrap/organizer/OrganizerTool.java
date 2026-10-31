package opentrap.organizer;

import opentrap.core.*;
import opentrap.ui.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.List;

/**
 * The Organizer: a shelf for requests worth coming back to. Send items here with
 * "send to organizer", give each a status and notes, and revisit them as a
 * curated set that is saved with the project.
 *
 * <p>Items are filed into collections. Everything lands in the Inbox; further
 * collections are created from the left pane and items are moved between them
 * from the table's context menu.
 */
public class OrganizerTool implements ToolModule {

    private static final String INBOX = "Inbox";

    private SuiteContext ctx;
    private OrganizerStore store;
    private final Model model = new Model();
    private JTable table;
    private MessageEditor reqView, respView;
    private JTextArea notes;
    private final CardLayout tableCards = new CardLayout();
    private JPanel tableArea;

    // The collections pane.
    private final DefaultListModel<String> collectionModel = new DefaultListModel<>();
    private final JList<String> collectionList = new JList<>(collectionModel);
    private final List<String> collections = new ArrayList<>(List.of(INBOX));
    private JTextField collectionSearch;
    private JLabel titleLabel;
    private JButton filterButton;
    private String search = "";

    @Override public String name() { return "organizer"; }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        this.store = ctx.organizer();
        ctx.registerSink("organizer", (svc, req, resp, cap) -> SwingUtilities.invokeLater(() -> {
            OrganizerItem it = new OrganizerItem(svc, req == null ? null : req.copy(),
                    resp == null ? null : resp.copy(), cap, "sent");
            it.collection = INBOX;
            store.add(it);
        }));
        store.addListener(() -> SwingUtilities.invokeLater(() -> {
            syncCollections();
            model.refresh();
            updateEmpty();
        }));
    }

    @Override public Tab[] tabs() {
        table = new JTable(model);
        Ui.styleTable(table);
        // #, time, status, tool, method, host, path, query, param count, status code, length, notes
        Ui.distributeWidths(table, 40, 90, 90, 70, 60, 150, 200, 140, 50, 60, 60, 160);
        Ui.newestFirst(table, 0);
        JComboBox<String> statusEditor = new JComboBox<>(OrganizerItem.STATUSES);
        statusEditor.setFont(Ui.PLAIN);
        table.getColumnModel().getColumn(2).setCellEditor(new DefaultCellEditor(statusEditor));
        table.getSelectionModel().addListSelectionListener(e -> showSelected());
        installRowMenu();

        reqView = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        respView = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
        notes = new JTextArea(3, 40);
        notes.setFont(Ui.PLAIN);
        notes.setLineWrap(true);
        notes.setWrapStyleWord(true);
        notes.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { saveNotes(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { saveNotes(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { saveNotes(); }
        });

        // The table, or a centred empty-state hint when nothing is saved yet.
        tableArea = new JPanel(tableCards);
        tableArea.add(Ui.scroll(table), "table");
        tableArea.add(emptyState(), "empty");

        JPanel notesPanel = new JPanel(new BorderLayout());
        notesPanel.add(Ui.bold(" Notes"), BorderLayout.NORTH);
        notesPanel.add(Ui.scroll(notes), BorderLayout.CENTER);

        JSplitPane viewers = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqView, respView);
        viewers.setDividerSize(5);
        Ui.balance(viewers, 0.5);

        JSplitPane lower = new JSplitPane(JSplitPane.VERTICAL_SPLIT, viewers, notesPanel);
        lower.setDividerSize(5);
        Ui.balance(lower, 0.75);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableArea, lower);
        split.setDividerSize(5);
        Ui.balance(split, 0.4);

        // The chosen collection's contents, under its own heading and a
        // "Contents" sub-tab, as the reference arranges it.
        JPanel contents = new JPanel(new BorderLayout());
        contents.add(buildFilterBar(), BorderLayout.NORTH);
        contents.add(split, BorderLayout.CENTER);

        JTabbedPane sub = new JTabbedPane();
        sub.setFont(Ui.PLAIN);
        sub.addTab("Contents", contents);

        JPanel right = new JPanel(new BorderLayout());
        right.add(buildCollectionHeader(), BorderLayout.NORTH);
        right.add(sub, BorderLayout.CENTER);

        JSplitPane outer = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, buildCollections(), right);
        outer.setDividerSize(5);
        Ui.balance(outer, 0.18);

        syncCollections();
        updateEmpty();
        return new Tab[]{ new Tab("organizer", outer) };
    }

    // ---- the collections pane ----------------------------------------------

    private JComponent buildCollections() {
        JButton add = Ui.button("New", "create a collection");
        add.setIcon(Glyphs.plus(12, Theme.ICON));
        add.setIconTextGap(5);
        add.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(collectionList, "Collection name:", "New collection",
                    JOptionPane.PLAIN_MESSAGE);
            if (name == null || name.isBlank()) return;
            if (!collections.contains(name.strip())) collections.add(name.strip());
            syncCollections();
            collectionList.setSelectedValue(name.strip(), true);
        });

        collectionSearch = Ui.searchField(12);
        collectionSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void go() { syncCollections(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });

        collectionList.setFont(Ui.PLAIN);
        collectionList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        collectionList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i,
                    boolean sel, boolean focus) {
                super.getListCellRendererComponent(l, v, i, sel, focus);
                setIcon(Glyphs.inbox(14, sel ? Theme.CONTROL : Theme.ICON));
                setBorder(Ui.pad(4, 6, 4, 6));
                if (sel) { setBackground(Theme.CONTROL_TINT); setForeground(Theme.TEXT); }
                return this;
            }
        });
        collectionList.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) return;
            if (titleLabel != null) titleLabel.setText(selectedCollection());
            model.refresh();
            updateEmpty();
        });

        JPanel head = new JPanel(new BorderLayout(6, 4));
        head.setOpaque(false);
        head.setBorder(Ui.pad(6, 6, 4, 6));
        head.add(Ui.bold("Collections"), BorderLayout.NORTH);
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(add, BorderLayout.WEST);
        row.add(collectionSearch, BorderLayout.CENTER);
        head.add(row, BorderLayout.SOUTH);

        JPanel p = new JPanel(new BorderLayout());
        p.add(head, BorderLayout.NORTH);
        p.add(Ui.scroll(collectionList), BorderLayout.CENTER);
        return p;
    }

    /** The chosen collection's icon and name above its contents. */
    private JComponent buildCollectionHeader() {
        titleLabel = new JLabel(INBOX, Glyphs.inbox(18, Theme.ICON), SwingConstants.LEFT);
        titleLabel.setFont(Ui.HEADING.deriveFont(15f));
        titleLabel.setIconTextGap(8);
        titleLabel.setBorder(Ui.pad(8, 10, 6, 10));
        JPanel p = new JPanel(new BorderLayout());
        p.add(titleLabel, BorderLayout.WEST);
        return p;
    }

    private String selectedCollection() {
        String v = collectionList.getSelectedValue();
        return v == null ? INBOX : v;
    }

    /** Rebuild the collection list from the stored items plus any created by hand. */
    private void syncCollections() {
        if (store != null) for (OrganizerItem it : store.all()) {
            if (!collections.contains(it.collection())) collections.add(it.collection());
        }
        String keep = selectedCollection();
        String q = collectionSearch == null ? "" : collectionSearch.getText().strip().toLowerCase();
        collectionModel.clear();
        for (String c : collections) {
            if (q.isEmpty() || c.toLowerCase().contains(q)) collectionModel.addElement(c);
        }
        if (collectionModel.isEmpty()) collectionModel.addElement(INBOX);
        int at = collectionModel.indexOf(keep);
        collectionList.setSelectedIndex(at >= 0 ? at : 0);
    }

    // ---- the contents filter bar -------------------------------------------

    private JComponent buildFilterBar() {
        filterButton = Ui.filterButton();
        filterButton.setText("Filter settings: Showing all items");
        filterButton.addActionListener(e -> JOptionPane.showMessageDialog(filterButton,
                "The search box beside this bar filters the collection's contents.",
                "Filter settings", JOptionPane.INFORMATION_MESSAGE));
        JTextField find = Ui.searchField(16);
        find.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void go() { search = find.getText().strip().toLowerCase(); model.refresh(); updateEmpty(); refreshFilterText(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        JButton help = Ui.helpButton("Organizer",
                "Items sent to the Organizer are kept with the project. Give each one a status "
                + "and notes, and file it into a collection.");
        JButton kebab = Glyphs.menuButton("Table layout and custom columns", () -> TableLayoutMenu.build(table));
        return Ui.filterBar(filterButton, find, help, kebab);
    }

    private void refreshFilterText() {
        if (filterButton == null) return;
        filterButton.setText(search.isEmpty()
                ? "Filter settings: Showing all items"
                : "Filter settings: Showing items matching \"" + search + "\"");
    }

    /** Delete, clear and move-to-collection on a row. */
    private void installRowMenu() {
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e)  { maybe(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybe(e); }
            private void maybe(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) table.setRowSelectionInterval(row, row);
                menu().show(table, e.getX(), e.getY());
            }
        });
    }

    private JPopupMenu menu() {
        JPopupMenu m = new JPopupMenu();
        OrganizerItem it = model.at(table.getSelectedRow());
        JMenu move = new JMenu("Move to collection");
        move.setFont(Ui.PLAIN);
        for (String c : collections) {
            JMenuItem mi = new JMenuItem(c);
            mi.setFont(Ui.PLAIN);
            mi.addActionListener(e -> { if (it != null) { it.collection = c; store.changed(); } });
            move.add(mi);
        }
        move.setEnabled(it != null);
        m.add(move);
        m.addSeparator();
        JMenuItem del = new JMenuItem("Delete item", Glyphs.trash(14, Theme.ICON));
        del.setFont(Ui.PLAIN);
        del.setEnabled(it != null);
        del.addActionListener(e -> { if (it != null) store.remove(it); });
        m.add(del);
        JMenuItem clear = new JMenuItem("Clear all items");
        clear.setFont(Ui.PLAIN);
        clear.addActionListener(e -> store.clear());
        m.add(clear);
        return m;
    }

    /** A centred hint shown while the chosen collection has nothing in it. */
    private JComponent emptyState() {
        JPanel p = new JPanel(new GridBagLayout());
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        JLabel mark = new JLabel(Glyphs.inbox(44, Theme.BORDER));
        mark.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel title = new JLabel("This collection is empty");
        title.setFont(Ui.HEADING.deriveFont(16f));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel hint = new JLabel("To get started, send a message to Organizer from anywhere in OpenTRAP.");
        hint.setFont(Ui.PLAIN);
        hint.setForeground(Theme.MUTED);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
        col.add(mark);
        col.add(Box.createVerticalStrut(12));
        col.add(title);
        col.add(Box.createVerticalStrut(8));
        col.add(hint);
        p.add(col);
        return p;
    }

    private void updateEmpty() {
        if (tableArea != null) {
            tableCards.show(tableArea, model.getRowCount() == 0 ? "empty" : "table");
        }
    }

    private void showSelected() {
        OrganizerItem it = model.at(table.getSelectedRow());
        if (it == null) { reqView.setMessage(new HttpService("", 0, false), new HttpMessage(new byte[0])); return; }
        reqView.setMessage(it.service, it.request == null ? new HttpMessage(new byte[0]) : it.request);
        respView.setMessage(it.service, it.response == null ? new HttpMessage(new byte[0]) : it.response, it.request);
        notes.setText(it.notes);
    }

    private void saveNotes() {
        OrganizerItem it = model.at(table.getSelectedRow());
        if (it != null) it.notes = notes.getText();
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"#", "Time", "Status", "Tool", "Method", "Host", "Path",
                                       "Query", "Param count", "Status code", "Length", "Notes"};
        private List<OrganizerItem> rows = new ArrayList<>();
        private final DateFormat fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM);

        Model() { refresh(); }

        /** The items in the chosen collection that pass the contents search. */
        void refresh() {
            List<OrganizerItem> out = new ArrayList<>();
            if (store != null) {
                String want = collectionList == null ? INBOX : selectedCollection();
                for (OrganizerItem it : store.all()) {
                    if (!it.collection().equals(want)) continue;
                    if (!search.isEmpty() && !matches(it)) continue;
                    out.add(it);
                }
            }
            rows = out;
            fireTableDataChanged();
        }

        private boolean matches(OrganizerItem it) {
            return (it.host() + " " + it.path() + " " + it.query() + " " + it.caption + " " + it.notes)
                    .toLowerCase().contains(search);
        }

        OrganizerItem at(int viewRow) {
            if (viewRow < 0) return null;
            int r = table.convertRowIndexToModel(viewRow);
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return (c == 0 || c == 8) ? Integer.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 2 || c == 11; }
        @Override public void setValueAt(Object v, int r, int c) {
            if (r < 0 || r >= rows.size()) return;
            if (c == 2)  rows.get(r).status = String.valueOf(v);
            if (c == 11) rows.get(r).notes  = String.valueOf(v);
            fireTableRowsUpdated(r, r);
        }
        @Override public Object getValueAt(int r, int c) {
            OrganizerItem it = rows.get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> fmt.format(new java.util.Date(it.time));
                case 2 -> it.status;
                case 3 -> it.sourceTool;
                case 4 -> it.request == null ? "" : it.request.method();
                case 5 -> it.host();
                case 6 -> it.path();
                case 7 -> it.query();
                case 8 -> it.request == null ? 0 : it.request.parameters().size();
                case 9 -> it.response == null ? "" : String.valueOf(it.response.statusCode());
                case 10 -> it.response == null ? "" : String.valueOf(it.response.body().length);
                default -> it.notes;
            };
        }
    }
}
