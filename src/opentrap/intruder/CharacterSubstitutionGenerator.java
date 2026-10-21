package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Generates leet-style variants of each base item by substituting single
 * characters according to a table of from &gt; to rules.
 */
class CharacterSubstitutionGenerator extends AbstractGenerator {

    private static final String[][] DEFAULTS = {
            {"a", "4"}, {"b", "8"}, {"e", "3"}, {"g", "6"},
            {"i", "1"}, {"o", "0"}, {"s", "5"}, {"t", "7"}, {"z", "2"}
    };

    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"from", "to"}, 0);
    private final JTable table = new JTable(model);
    private final JPanel panel;

    CharacterSubstitutionGenerator() {
        for (String[] d : DEFAULTS) model.addRow(d);
        Ui.styleTable(table);
        Ui.setWidths(table, 120, 120);
        model.addTableModelListener(e -> fire());

        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(260, 150));

        JButton add    = Ui.button("add");
        JButton delete = Ui.button("delete");
        JButton clear  = Ui.button("clear");
        add.addActionListener(e -> { model.addRow(new Object[]{"", ""}); });
        delete.addActionListener(e -> {
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) model.removeRow(rows[i]);
            fire();
        });
        clear.addActionListener(e -> { model.setRowCount(0); fire(); });

        panel = new JPanel(new BorderLayout(8, 4));
        panel.setOpaque(false);
        panel.add(sp, BorderLayout.CENTER);
        panel.add(Ui.buttonColumn(add, delete, clear), BorderLayout.EAST);
    }

    private Map<Character, Character> rules() {
        Map<Character, Character> m = new HashMap<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            String f = str(model.getValueAt(i, 0));
            String t = str(model.getValueAt(i, 1));
            if (!f.isEmpty() && !t.isEmpty()) m.put(f.charAt(0), t.charAt(0));
        }
        return m;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    @Override public String label() { return " character substitution"; }
    @Override public JComponent config() { return panel; }

    @Override public int count() {
        Map<Character, Character> rules = rules();
        long total = 0;
        for (String item : base) {
            int sub = 0;
            for (int i = 0; i < item.length(); i++) if (rules.containsKey(item.charAt(i))) sub++;
            total += 1L << Math.min(sub, 20);
            if (total >= CAP) return CAP;
        }
        return (int) Math.min(total, CAP);
    }

    @Override public List<String> generate() {
        Map<Character, Character> rules = rules();
        Set<String> out = new LinkedHashSet<>();
        for (String item : base) {
            variants(item, rules, out);
            if (out.size() >= CAP) break;
        }
        return new ArrayList<>(out);
    }

    /** All combinations of applying or not applying each substitutable char. */
    private void variants(String item, Map<Character, Character> rules, Set<String> out) {
        List<Integer> positions = new ArrayList<>();
        for (int i = 0; i < item.length(); i++) if (rules.containsKey(item.charAt(i))) positions.add(i);
        int k = Math.min(positions.size(), 20);
        for (long mask = 0; mask < (1L << k) && out.size() < CAP; mask++) {
            char[] c = item.toCharArray();
            for (int b = 0; b < k; b++) {
                if ((mask & (1L << b)) != 0) {
                    int p = positions.get(b);
                    c[p] = rules.get(c[p]);
                }
            }
            out.add(new String(c));
        }
    }
}
