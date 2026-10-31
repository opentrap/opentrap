package opentrap.proxy;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;

/**
 * One interception rule chain, laid out as the reference draws it: the
 * "intercept based on these rules" switch with a note when master interception
 * is off, an Add / Edit / Remove / Up / Down column at the left of the table,
 * and the chain itself across it.
 */
final class InterceptRulesPanel extends JPanel {

    private final List<ProxyRule> rules;
    private final boolean response;
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JLabel masterNote = new JLabel();

    InterceptRulesPanel(ProxyServer server, boolean response, JCheckBox on) {
        super();
        this.rules = response ? server.responseRules : server.requestRules;
        this.response = response;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        masterNote.setFont(Ui.PLAIN.deriveFont(Font.ITALIC));
        masterNote.setForeground(Ui.ERROR_RED);
        refreshMasterNote(server);
        server.onListenersChanged(() -> refreshMasterNote(server));

        JPanel switchRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        switchRow.setOpaque(false);
        switchRow.setAlignmentX(LEFT_ALIGNMENT);
        switchRow.add(on);
        switchRow.add(masterNote);
        add(switchRow);
        add(Box.createVerticalStrut(8));

        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Ui.setWidths(table, 70, 70, 150, 160, 240);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(760, 120));
        sp.setMaximumSize(new Dimension(1100, 120));

        JButton add = Ui.button("Add", "add a rule to the chain");
        JButton edit = Ui.button("Edit", "edit the selected rule");
        JButton remove = Ui.button("Remove", "delete the selected rule");
        JButton up = Ui.button("Up", "evaluate the selected rule earlier");
        JButton down = Ui.button("Down", "evaluate the selected rule later");
        add.addActionListener(e -> {
            ProxyRule r = InterceptRuleDialog.show(this, null, response, rules.isEmpty());
            if (r != null) { rules.add(r); model.fireTableDataChanged(); }
        });
        edit.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i < 0) return;
            ProxyRule r = InterceptRuleDialog.show(this, rules.get(i), response, i == 0);
            if (r != null) { rules.set(i, r); model.fireTableRowsUpdated(i, i); }
        });
        remove.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i >= 0) { rules.remove(i); model.fireTableDataChanged(); }
        });
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.columnAtPoint(e.getPoint()) != 0) edit.doClick();
            }
        });

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(1100, 120));
        row.add(Ui.buttonColumn(add, edit, remove, up, down), BorderLayout.WEST);
        row.add(sp, BorderLayout.CENTER);
        add(row);
    }

    /** The reference says so in red beside the switch when nothing is being held. */
    private void refreshMasterNote(ProxyServer server) {
        masterNote.setText(server.interceptingEnabled ? "" : "Master interception is turned off");
    }

    private void move(int delta) {
        int i = table.getSelectedRow();
        int to = i + delta;
        if (i < 0 || to < 0 || to >= rules.size()) return;
        rules.add(to, rules.remove(i));
        model.fireTableDataChanged();
        table.setRowSelectionInterval(to, to);
    }

    private class Model extends AbstractTableModel {
        private final String[] cols = {"Enabled", "Operator", "Match type", "Relationship", "Condition"};
        @Override public int getRowCount() { return rules.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        @Override public Object getValueAt(int r, int c) {
            ProxyRule rule = rules.get(r);
            return switch (c) {
                case 0 -> rule.enabled;
                // The first rule starts the chain, so it has no operator.
                case 1 -> r == 0 ? "" : Ui.sentence(rule.booleanOp);
                case 2 -> Ui.sentence(rule.matchItem);
                case 3 -> Ui.sentence(rule.relationship);
                default -> rule.condition;
            };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c == 0) { rules.get(r).enabled = Boolean.TRUE.equals(v); fireTableRowsUpdated(r, r); }
        }
    }
}
