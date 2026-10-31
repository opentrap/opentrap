package opentrap.organizer;

import opentrap.core.*;
import opentrap.ui.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.text.DateFormat;
import java.util.List;

/**
 * The Organizer: a shelf for requests worth coming back to. Send items here with
 * "send to organizer", give each a status and notes, and revisit them as a
 * curated set that is saved with the project.
 */
public class OrganizerTool implements ToolModule {

    private SuiteContext ctx;
    private OrganizerStore store;
    private final Model model = new Model();
    private JTable table;
    private MessageEditor reqView, respView;
    private JTextArea notes;
    private final CardLayout tableCards = new CardLayout();
    private JPanel tableArea;

    @Override public String name() { return "organizer"; }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        this.store = ctx.organizer();
        ctx.registerSink("organizer", (svc, req, resp, cap) -> SwingUtilities.invokeLater(() -> {
            store.add(new OrganizerItem(svc, req == null ? null : req.copy(),
                    resp == null ? null : resp.copy(), cap, "sent"));
        }));
        store.addListener(() -> SwingUtilities.invokeLater(() -> { model.refresh(); updateEmpty(); }));
    }

    @Override public Tab[] tabs() {
        table = new JTable(model);
        Ui.styleTable(table);
        Ui.distributeWidths(table, 90, 220, 150, 200, 110);
        JComboBox<String> statusEditor = new JComboBox<>(OrganizerItem.STATUSES);
        statusEditor.setFont(Ui.PLAIN);
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(statusEditor));
        table.getSelectionModel().addListSelectionListener(e -> showSelected());

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

        JButton delete = Ui.button("Delete");
        JButton clear = Ui.button("Clear all");
        delete.addActionListener(e -> {
            OrganizerItem it = model.at(table.getSelectedRow());
            if (it != null) store.remove(it);
        });
        clear.addActionListener(e -> store.clear());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(delete);
        bar.add(opentrap.ui.Glyphs.menuButton("Table layout and custom columns", () -> opentrap.ui.TableLayoutMenu.build(table)));
        bar.add(clear);

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

        JPanel root = new JPanel(new BorderLayout());
        root.add(bar, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        updateEmpty();
        return new Tab[]{ new Tab("organizer", root) };
    }

    /** A centred hint shown while the Organizer has no saved items. */
    private JComponent emptyState() {
        JPanel p = new JPanel(new GridBagLayout());
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        JLabel title = new JLabel("Organizer inbox is empty");
        title.setFont(Ui.HEADING.deriveFont(16f));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel hint = new JLabel("To get started, send a message to Organizer from anywhere in OpenTRAP.");
        hint.setFont(Ui.PLAIN);
        hint.setForeground(Theme.MUTED);
        hint.setAlignmentX(Component.CENTER_ALIGNMENT);
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
        private final String[] cols = {"Status", "Caption", "Host", "Path", "Time"};
        private List<OrganizerItem> rows = store == null ? java.util.List.of() : store.all();
        private final DateFormat fmt = DateFormat.getTimeInstance(DateFormat.MEDIUM);

        void refresh() { rows = store.all(); fireTableDataChanged(); }
        OrganizerItem at(int viewRow) {
            if (viewRow < 0) return null;
            int r = table.convertRowIndexToModel(viewRow);
            return r >= 0 && r < rows.size() ? rows.get(r) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c == 0 && r >= 0 && r < rows.size()) { rows.get(r).status = String.valueOf(v); fireTableRowsUpdated(r, r); }
        }
        @Override public Object getValueAt(int r, int c) {
            OrganizerItem it = rows.get(r);
            return switch (c) {
                case 0 -> it.status;
                case 1 -> it.caption == null || it.caption.isEmpty() ? it.path() : it.caption;
                case 2 -> it.host();
                case 3 -> it.path();
                default -> fmt.format(new java.util.Date(it.time));
            };
        }
    }
}
