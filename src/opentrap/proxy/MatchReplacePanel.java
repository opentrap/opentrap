package opentrap.proxy;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;

/** Edit the match-and-replace rule list. */
public class MatchReplacePanel extends JPanel {

    private final ProxyServer server;
    private final Model model = new Model();
    private final JTable table = new JTable(model);

    public MatchReplacePanel(ProxyServer server) {
        super();
        this.server = server;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        JLabel h = Ui.heading("match and replace");
        h.setAlignmentX(LEFT_ALIGNMENT);
        add(h);
        add(Box.createVerticalStrut(8));

        Ui.styleTable(table);
        Ui.setWidths(table, 50, 110, 170, 170, 150);
        // The type column edits through a dropdown of the four scopes.
        JComboBox<String> typeEditor = new JComboBox<>(MatchReplaceRule.TYPES);
        typeEditor.setFont(Ui.PLAIN);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(typeEditor));
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(640, 130));
        sp.setMaximumSize(new Dimension(760, 130));
        sp.setAlignmentX(LEFT_ALIGNMENT);
        add(sp);
        add(Box.createVerticalStrut(8));

        JPanel entry = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        entry.setOpaque(false);
        entry.setAlignmentX(LEFT_ALIGNMENT);
        JComboBox<String> type = Ui.combo(MatchReplaceRule.TYPES);
        JTextField match = Ui.field("", 14);
        JTextField replace = Ui.field("", 14);
        JTextField comment = Ui.field("", 10);
        JButton add = Ui.button("add", "add the item");
        JButton remove = Ui.button("remove", "remove the selected item");
        JButton up = Ui.button("up", "move the selected rule earlier");
        JButton down = Ui.button("down", "move the selected rule later");
        type.setToolTipText("applies to request/response header/body");
        match.setToolTipText("regex to match (leave blank to insert new header)");
        replace.setToolTipText("replacement string (leave blank to remove matched header)");
        comment.setToolTipText("optional note");
        entry.add(type);
        entry.add(match);
        entry.add(replace);
        entry.add(comment);
        entry.add(add);
        entry.add(remove);
        entry.add(up);
        entry.add(down);
        add(entry);
        JLabel hint = Ui.label("double-click any cell to edit a rule in place; rules apply top to bottom");
        hint.setAlignmentX(LEFT_ALIGNMENT);
        add(Box.createVerticalStrut(4));
        add(hint);

        add.addActionListener(e -> {
            server.matchReplace.add(new MatchReplaceRule(true,
                    String.valueOf(type.getSelectedItem()), match.getText(), replace.getText(), comment.getText()));
            model.fireTableDataChanged();
            match.setText(""); replace.setText(""); comment.setText("");
        });
        remove.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i >= 0 && i < server.matchReplace.size()) {
                server.matchReplace.remove(i);
                model.fireTableDataChanged();
            }
        });
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));
        setMaximumSize(new Dimension(760, 300));
    }

    /** Swap the selected rule with its neighbour, keeping the selection on it. */
    private void move(int delta) {
        int i = table.getSelectedRow();
        int j = i + delta;
        if (i < 0 || j < 0 || j >= server.matchReplace.size()) return;
        MatchReplaceRule tmp = server.matchReplace.get(i);
        server.matchReplace.set(i, server.matchReplace.get(j));
        server.matchReplace.set(j, tmp);
        model.fireTableDataChanged();
        table.getSelectionModel().setSelectionInterval(j, j);
    }

    private class Model extends AbstractTableModel {
        private final String[] cols = {"enabled", "type", "match", "replace", "comment"};
        @Override public int getRowCount() { return server.matchReplace.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return true; }

        @Override public Object getValueAt(int r, int c) {
            MatchReplaceRule x = server.matchReplace.get(r);
            return switch (c) {
                case 0 -> x.enabled;
                case 1 -> x.type;
                case 2 -> x.match;
                case 3 -> x.replace;
                default -> x.comment;
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            MatchReplaceRule x = server.matchReplace.get(r);
            String s = v == null ? "" : v.toString();
            switch (c) {
                case 0 -> x.enabled = Boolean.TRUE.equals(v);
                case 1 -> x.type = s;
                case 2 -> x.match = s;
                case 3 -> x.replace = s;
                default -> x.comment = s;
            }
            fireTableRowsUpdated(r, r);
        }
    }
}
