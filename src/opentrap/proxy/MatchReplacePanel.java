package opentrap.proxy;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;

/** Edit the match-and-replace rule list. */
public class MatchReplacePanel extends JPanel {

    private final ProxyServer server;
    private final Model model;
    private final JTable table;

    public MatchReplacePanel(ProxyServer server) {
        super();
        this.server = server;
        this.model = new Model(server.matchReplace);
        this.table = new JTable(model);
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);
        // Laid out as in the reference: a heading and description, the button
        // column to the left of the rules table, and the fields for a new rule
        // beneath it. A second section covers WebSocket rules.
        JLabel h = Ui.heading("HTTP match and replace rules");
        h.setAlignmentX(LEFT_ALIGNMENT);
        add(h);
        add(Box.createVerticalStrut(4));
        JTextArea desc = Ui.note("Use these settings to automatically replace parts of HTTP "
                + "requests and responses passing through the Proxy.");
        desc.setAlignmentX(LEFT_ALIGNMENT);
        desc.setMaximumSize(new Dimension(760, desc.getPreferredSize().height));
        add(desc);
        add(Box.createVerticalStrut(8));

        Ui.styleTable(table);
        Ui.setWidths(table, 60, 120, 170, 170, 150);
        // The type column edits through a dropdown of the four scopes.
        JComboBox<String> typeEditor = new JComboBox<>(MatchReplaceRule.TYPES);
        typeEditor.setFont(Ui.PLAIN);
        table.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(typeEditor));
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(640, 150));

        JButton add = Ui.button("Add", "add a rule from the fields below");
        JButton remove = Ui.button("Remove", "remove the selected rule");
        JButton up = Ui.button("Up", "move the selected rule earlier");
        JButton down = Ui.button("Down", "move the selected rule later");
        JPanel rulesRow = new JPanel(new BorderLayout(8, 0));
        rulesRow.setOpaque(false);
        rulesRow.setAlignmentX(LEFT_ALIGNMENT);
        rulesRow.add(Ui.buttonColumn(add, remove, up, down), BorderLayout.WEST);
        rulesRow.add(sp, BorderLayout.CENTER);
        rulesRow.setMaximumSize(new Dimension(760, 150));
        rulesRow.setPreferredSize(new Dimension(760, 150));
        add(rulesRow);
        add(Box.createVerticalStrut(6));

        JPanel entry = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        entry.setOpaque(false);
        entry.setAlignmentX(LEFT_ALIGNMENT);
        JComboBox<String> type = Ui.combo(MatchReplaceRule.TYPES);
        JTextField match = Ui.field("");
        JTextField replace = Ui.field("");
        JTextField comment = Ui.field("");
        type.setToolTipText("applies to request/response header/body");
        match.setToolTipText("regex to match (leave blank to insert new header)");
        replace.setToolTipText("replacement string (leave blank to remove matched header)");
        comment.setToolTipText("optional note");
        entry.add(Ui.label("New rule:"));
        entry.add(type);
        entry.add(match);
        entry.add(replace);
        entry.add(comment);
        add(entry);
        JLabel hint = Ui.label("Fill in the fields and click Add. Double-click any cell to edit a "
                + "rule in place; rules apply top to bottom.");
        hint.setForeground(opentrap.ui.Theme.MUTED);
        hint.setAlignmentX(LEFT_ALIGNMENT);
        add(Box.createVerticalStrut(2));
        add(hint);

        add(Box.createVerticalStrut(18));
        JLabel wh = Ui.heading("WebSocket match and replace rules");
        wh.setAlignmentX(LEFT_ALIGNMENT);
        add(wh);
        add(Box.createVerticalStrut(4));
        JTextArea wsDesc = Ui.note("Use these settings to automatically replace parts of "
                + "WebSocket messages passing through the Proxy.");
        wsDesc.setAlignmentX(LEFT_ALIGNMENT);
        wsDesc.setMaximumSize(new Dimension(760, wsDesc.getPreferredSize().height));
        add(wsDesc);
        add(Box.createVerticalStrut(8));
        addWebSocketRules();

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
        setBorder(Ui.pad(10, 10, 10, 10));
    }

    /**
     * The WebSocket rules: the same table and entry row as the HTTP rules, with
     * a direction in place of the header/body type. They apply to whole text
     * messages as they pass through the proxy.
     */
    private void addWebSocketRules() {
        final Model wsModel = new Model(server.wsMatchReplace);
        final JTable wsTable = new JTable(wsModel);
        Ui.styleTable(wsTable);
        Ui.setWidths(wsTable, 60, 120, 170, 170, 150);
        JComboBox<String> dirEditor = new JComboBox<>(MatchReplaceRule.WS_TYPES);
        dirEditor.setFont(Ui.PLAIN);
        wsTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(dirEditor));
        JScrollPane sp = Ui.scroll(wsTable);
        sp.setPreferredSize(new Dimension(640, 110));

        JButton add = Ui.button("Add", "add a rule from the fields below");
        JButton remove = Ui.button("Remove", "remove the selected rule");
        JPanel rulesRow = new JPanel(new BorderLayout(8, 0));
        rulesRow.setOpaque(false);
        rulesRow.setAlignmentX(LEFT_ALIGNMENT);
        rulesRow.add(Ui.buttonColumn(add, remove), BorderLayout.WEST);
        rulesRow.add(sp, BorderLayout.CENTER);
        rulesRow.setMaximumSize(new Dimension(760, 110));
        rulesRow.setPreferredSize(new Dimension(760, 110));
        add(rulesRow);
        add(Box.createVerticalStrut(6));

        JPanel entry = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        entry.setOpaque(false);
        entry.setAlignmentX(LEFT_ALIGNMENT);
        JComboBox<String> dir = Ui.combo(MatchReplaceRule.WS_TYPES);
        JTextField match = Ui.field("");
        JTextField replace = Ui.field("");
        JTextField comment = Ui.field("");
        dir.setToolTipText("which direction the message must be travelling in");
        match.setToolTipText("regex to match in the message text");
        replace.setToolTipText("replacement string");
        comment.setToolTipText("optional note");
        entry.add(Ui.label("New rule:"));
        entry.add(dir);
        entry.add(match);
        entry.add(replace);
        entry.add(comment);
        add(entry);

        add.addActionListener(e -> {
            if (match.getText().isBlank()) return;
            server.wsMatchReplace.add(new MatchReplaceRule(true,
                    String.valueOf(dir.getSelectedItem()), match.getText(), replace.getText(), comment.getText()));
            wsModel.fireTableDataChanged();
            match.setText(""); replace.setText(""); comment.setText("");
        });
        remove.addActionListener(e -> {
            int i = wsTable.getSelectedRow();
            if (i >= 0 && i < server.wsMatchReplace.size()) {
                server.wsMatchReplace.remove(i);
                wsModel.fireTableDataChanged();
            }
        });
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

    /** A rule list as a table; the HTTP and WebSocket lists each get one. */
    private static class Model extends AbstractTableModel {
        private final String[] cols = {"Enabled", "Type", "Match", "Replace", "Comment"};
        private final java.util.List<MatchReplaceRule> rules;
        Model(java.util.List<MatchReplaceRule> rules) { this.rules = rules; }
        @Override public int getRowCount() { return rules.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return true; }

        @Override public Object getValueAt(int r, int c) {
            MatchReplaceRule x = rules.get(r);
            return switch (c) {
                case 0 -> x.enabled;
                case 1 -> x.type;
                case 2 -> x.match;
                case 3 -> x.replace;
                default -> x.comment;
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            MatchReplaceRule x = rules.get(r);
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
