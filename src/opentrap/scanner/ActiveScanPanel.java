package opentrap.scanner;

import opentrap.core.SuiteContext;
import opentrap.engine.ZapApi;
import opentrap.engine.ZapEngine;
import opentrap.spider.Cdp.JsonValue;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The active scan-rule policy tab: lists ZAP's active rules and lets the tester
 * enable/disable them and set each rule's attack strength and alert threshold,
 * through ZAP's API, so the policy governs the audits the dashboard runs. Loads
 * lazily the first time it is shown.
 */
public class ActiveScanPanel extends JPanel {

    private static final String[] STRENGTHS  = {"DEFAULT", "LOW", "MEDIUM", "HIGH", "INSANE"};
    private static final String[] THRESHOLDS = {"DEFAULT", "OFF", "LOW", "MEDIUM", "HIGH"};

    private final Model model = new Model();
    private JTable table;
    private final JLabel status = Ui.label("");
    private final JLabel summary = Ui.label("");
    private ZapApi api;
    private boolean loadStarted;

    public ActiveScanPanel(SuiteContext ctx) {
        super(new BorderLayout());
        table = new JTable(model);
        Ui.styleTable(table);
        JComboBox<String> strengthEd = new JComboBox<>(STRENGTHS);
        strengthEd.setFont(Ui.PLAIN);
        JComboBox<String> thrEd = new JComboBox<>(THRESHOLDS);
        thrEd.setFont(Ui.PLAIN);
        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JCheckBox()));
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(strengthEd));
        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(thrEd));
        Ui.distributeWidths(table, 60, 60, 420, 110, 110);

        JButton enableAll = Ui.button("enable all");
        JButton disableAll = Ui.button("disable all");
        JButton refresh = Ui.button("refresh");
        enableAll.addActionListener(e -> bulkEnabled(true));
        disableAll.addActionListener(e -> bulkEnabled(false));
        refresh.addActionListener(e -> load());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        buttons.add(enableAll); buttons.add(disableAll);
        buttons.add(new JSeparator(SwingConstants.VERTICAL));
        buttons.add(refresh); buttons.add(status);

        JTextArea note = Ui.note(
            "These are the active (attacking) rules on the scan engine (OWASP ZAP). Enable the "
            + "checks you want an audit to run, and set each rule's attack strength (how hard it "
            + "probes) and alert threshold. The policy applies to the scans you launch from the "
            + "dashboard.");
        note.setBorder(Ui.pad(8, 10, 4, 10));

        JPanel head = new JPanel(new BorderLayout());
        head.add(note, BorderLayout.CENTER);
        head.add(summary, BorderLayout.EAST);

        add(head, BorderLayout.NORTH);
        add(Ui.scroll(table), BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                    && isShowing() && !loadStarted) load();
        });
    }

    private void load() {
        loadStarted = true;
        ZapEngine ze = ZapEngine.shared();
        if (ze == null || !ze.available()) {
            status.setText("Scan engine (ZAP) not installed. Run ./fetch-zap.sh to add it.");
            return;
        }
        status.setText("Starting scan engine and loading active rules...");
        new Thread(() -> {
            try {
                ze.start();
                api = ze.api();
                List<Rule> rules = new ArrayList<>();
                for (JsonValue s : api.activeScanners()) {
                    rules.add(new Rule(
                            nz(ZapApi.str(s, "id")),
                            nz(ZapApi.str(s, "name")),
                            "true".equalsIgnoreCase(ZapApi.str(s, "enabled")),
                            nz(ZapApi.str(s, "attackStrength")),
                            nz(ZapApi.str(s, "alertThreshold"))));
                }
                SwingUtilities.invokeLater(() -> { model.setRules(rules); status.setText(""); refreshSummary(); });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> status.setText("Could not load active rules: " + ex.getMessage()));
            }
        }, "ascan-rules-load").start();
    }

    private void bulkEnabled(boolean on) {
        if (api == null) return;
        List<Rule> all = model.rules;
        new Thread(() -> {
            for (Rule r : all) {
                try { api.setActiveEnabled(r.id, on); r.enabled = on; } catch (Exception ignore) {}
            }
            SwingUtilities.invokeLater(() -> { model.fireTableDataChanged(); refreshSummary(); });
        }, "ascan-bulk").start();
    }

    private void refreshSummary() {
        int total = model.getRowCount(), enabled = 0;
        for (Rule r : model.rules) if (r.enabled) enabled++;
        summary.setText(enabled + " of " + total + " active rules enabled   ");
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static final class Rule {
        final String id, name;
        boolean enabled;
        String strength, threshold;
        Rule(String id, String name, boolean enabled, String strength, String threshold) {
            this.id = id; this.name = name; this.enabled = enabled; this.strength = strength; this.threshold = threshold;
        }
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"enabled", "id", "rule", "strength", "threshold"};
        private List<Rule> rules = new ArrayList<>();
        void setRules(List<Rule> r) { rules = r; fireTableDataChanged(); }

        @Override public int getRowCount() { return rules.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) {
            return c == 0 || ((c == 3 || c == 4) && rules.get(r).enabled);
        }
        @Override public Object getValueAt(int r, int c) {
            Rule m = rules.get(r);
            return switch (c) { case 0 -> m.enabled; case 1 -> m.id; case 2 -> m.name; case 3 -> m.strength; default -> m.threshold; };
        }
        @Override public void setValueAt(Object v, int r, int c) {
            Rule m = rules.get(r);
            if (c == 0 && v instanceof Boolean on) {
                m.enabled = on;
                run(() -> api.setActiveEnabled(m.id, on));
                fireTableRowsUpdated(r, r); refreshSummary();
            } else if (c == 3 && v instanceof String s) {
                m.strength = s; run(() -> api.setActiveStrength(m.id, s)); fireTableRowsUpdated(r, r);
            } else if (c == 4 && v instanceof String t) {
                m.threshold = t; run(() -> api.setActiveThreshold(m.id, t)); fireTableRowsUpdated(r, r);
            }
        }
        private void run(ThrowingRunnable action) {
            if (api == null) return;
            new Thread(() -> { try { action.run(); } catch (Exception ignore) {} }, "ascan-set").start();
        }
    }

    private interface ThrowingRunnable { void run() throws Exception; }
}
