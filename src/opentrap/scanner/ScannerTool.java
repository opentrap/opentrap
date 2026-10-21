package opentrap.scanner;

import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.engine.ZapApi;
import opentrap.engine.ZapEngine;
import opentrap.spider.Cdp.JsonValue;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The passive scan-rule tab.
 *
 * <p>OpenTRAP no longer runs its own detection; the scan engine is OWASP ZAP.
 * This tab lists ZAP's passive rules and configures them, enable/disable and
 * alert threshold, through ZAP's API, so the changes govern the engine that
 * actually performs the scans. It loads lazily the first time the tab is opened,
 * starting the engine if needed.
 */
public class ScannerTool implements ToolModule {

    private static final String[] THRESHOLDS = {"DEFAULT", "OFF", "LOW", "MEDIUM", "HIGH"};

    private SuiteContext ctx;
    private ZapApi api;
    private RuleTableModel model;
    private JTable table;
    private JTextField filterField;
    private JLabel summary;
    private JLabel status;
    private TableRowSorter<RuleTableModel> sorter;
    private boolean loadStarted;

    @Override public String name() { return "scanner"; }
    @Override public boolean alwaysTabbed() { return true; }

    @Override public void init(SuiteContext ctx) { this.ctx = ctx; }

    @Override public Tab[] tabs() {
        return new Tab[]{
            new Tab("passive", buildRules()),
            new Tab("active policy", new ActiveScanPanel(ctx))
        };
    }

    private JComponent buildRules() {
        model = new RuleTableModel();
        table = new JTable(model);
        Ui.styleTable(table);
        table.setRowHeight(Math.max(table.getRowHeight(), 20));
        sorter = new TableRowSorter<>(model);
        table.setRowSorter(sorter);

        table.getColumnModel().getColumn(0).setCellEditor(new DefaultCellEditor(new JCheckBox()));
        JComboBox<String> thr = new JComboBox<>(THRESHOLDS);
        thr.setFont(Ui.PLAIN);
        table.getColumnModel().getColumn(4).setCellEditor(new DefaultCellEditor(thr));

        // Grey out a rule's row when it is switched off, so state reads at a glance.
        DefaultTableCellRenderer dim = new DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean focus, int row, int col) {
                Component c = super.getTableCellRendererComponent(t, v, sel, focus, row, col);
                boolean on = model.ruleAt(t.convertRowIndexToModel(row)).enabled;
                if (!sel) c.setForeground(on ? Color.BLACK : new Color(0x99, 0x99, 0x99));
                return c;
            }
        };
        for (int col : new int[]{1, 2, 3}) table.getColumnModel().getColumn(col).setCellRenderer(dim);
        Ui.distributeWidths(table, 60, 60, 430, 110, 110);

        filterField = Ui.field("", 24);
        filterField.getDocument().addDocumentListener(new SimpleDoc(this::applyFilter));
        summary = Ui.label("");
        status = Ui.label("");

        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.setBorder(Ui.pad(8, 10, 8, 10));
        JPanel left = Ui.row(Ui.label("filter:"), filterField, Ui.button("clear", "Show every rule again"));
        ((JButton) left.getComponent(2)).addActionListener(e -> { filterField.setText(""); applyFilter(); });
        top.add(left, BorderLayout.WEST);
        top.add(summary, BorderLayout.EAST);

        JButton enableAll  = Ui.button("enable all", "Switch on every rule in the current filter");
        JButton disableAll = Ui.button("disable all", "Switch off every rule in the current filter");
        JButton setLow     = Ui.button("threshold low", "Report on weaker evidence, more false positives");
        JButton setMed     = Ui.button("threshold medium", "The balanced setting");
        JButton setHigh    = Ui.button("threshold high", "Report only on strong evidence, more misses");
        JButton setDef     = Ui.button("threshold default", "Use each rule's own default");
        JButton refresh    = Ui.button("refresh", "Reload the rules from the engine");

        enableAll.addActionListener(e -> bulkEnabled(true));
        disableAll.addActionListener(e -> bulkEnabled(false));
        setLow.addActionListener(e -> bulkThreshold("LOW"));
        setMed.addActionListener(e -> bulkThreshold("MEDIUM"));
        setHigh.addActionListener(e -> bulkThreshold("HIGH"));
        setDef.addActionListener(e -> bulkThreshold("DEFAULT"));
        refresh.addActionListener(e -> load());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        buttons.setOpaque(false);
        buttons.setBorder(Ui.pad(0, 10, 8, 10));
        buttons.add(enableAll);
        buttons.add(disableAll);
        buttons.add(new JSeparator(SwingConstants.VERTICAL));
        buttons.add(setLow);
        buttons.add(setMed);
        buttons.add(setHigh);
        buttons.add(setDef);
        buttons.add(new JSeparator(SwingConstants.VERTICAL));
        buttons.add(refresh);

        JTextArea note = Ui.note(
            "These are the passive rules on the scan engine (OWASP ZAP). Switch one off when it "
            + "is not relevant, or move its alert threshold: low reports on weaker evidence and "
            + "finds more at the cost of false positives, high reports only on strong evidence. "
            + "Changes apply to the engine that runs your scans.");
        note.setBorder(Ui.pad(0, 10, 4, 10));

        JPanel head = new JPanel();
        head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
        head.setOpaque(false);
        head.add(note);
        head.add(top);
        JPanel statusRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 2));
        statusRow.setOpaque(false);
        statusRow.add(status);
        head.add(statusRow);

        JPanel root = new JPanel(new BorderLayout());
        root.add(head, BorderLayout.NORTH);
        root.add(Ui.scroll(table), BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);

        // Load lazily: only when the tab is first shown, so merely launching the
        // app does not spin up the engine.
        root.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                    && root.isShowing() && !loadStarted) {
                load();
            }
        });
        return root;
    }

    /** Ensure the engine is up and load its passive rules, off the EDT. */
    private void load() {
        loadStarted = true;
        ZapEngine ze = ZapEngine.shared();
        if (ze == null || !ze.available()) {
            status.setText("Scan engine (ZAP) not installed. Run ./fetch-zap.sh to add it.");
            return;
        }
        status.setText("Starting scan engine and loading rules...");
        new Thread(() -> {
            try {
                ze.start();
                api = ze.api();
                List<Rule> rules = new ArrayList<>();
                for (JsonValue s : api.passiveScanners()) {
                    rules.add(new Rule(
                            toInt(ZapApi.str(s, "id")),
                            nz(ZapApi.str(s, "name")),
                            nz(ZapApi.str(s, "quality")),
                            "true".equalsIgnoreCase(ZapApi.str(s, "enabled")),
                            nz(ZapApi.str(s, "alertThreshold"))));
                }
                SwingUtilities.invokeLater(() -> {
                    model.setRules(rules);
                    status.setText("");
                    refreshSummary();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        status.setText("Could not load rules from the engine: " + ex.getMessage()));
            }
        }, "pscan-rules-load").start();
    }

    private void bulkEnabled(boolean on) {
        if (api == null) return;
        List<Rule> visible = visibleRules();
        new Thread(() -> {
            for (Rule r : visible) {
                try { api.setPassiveEnabled(String.valueOf(r.id), on); r.enabled = on; } catch (Exception ignore) {}
            }
            SwingUtilities.invokeLater(() -> { model.fireTableDataChanged(); refreshSummary(); });
        }, "pscan-bulk").start();
    }

    private void bulkThreshold(String t) {
        if (api == null) return;
        List<Rule> visible = visibleRules();
        new Thread(() -> {
            for (Rule r : visible) {
                try { api.setPassiveThreshold(String.valueOf(r.id), t); r.threshold = t; } catch (Exception ignore) {}
            }
            SwingUtilities.invokeLater(() -> model.fireTableDataChanged());
        }, "pscan-bulk").start();
    }

    private List<Rule> visibleRules() {
        List<Rule> out = new ArrayList<>();
        if (table == null) return out;
        for (int vr = 0; vr < table.getRowCount(); vr++) {
            out.add(model.ruleAt(table.convertRowIndexToModel(vr)));
        }
        return out;
    }

    private void applyFilter() {
        String text = filterField.getText().trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            sorter.setRowFilter(null);
        } else {
            sorter.setRowFilter(new RowFilter<RuleTableModel, Integer>() {
                @Override public boolean include(Entry<? extends RuleTableModel, ? extends Integer> e) {
                    Rule r = model.ruleAt(e.getIdentifier());
                    return r.name.toLowerCase(Locale.ROOT).contains(text)
                            || String.valueOf(r.id).contains(text)
                            || r.quality.toLowerCase(Locale.ROOT).contains(text);
                }
            });
        }
        refreshSummary();
    }

    private void refreshSummary() {
        if (summary == null) return;
        int total = model.getRowCount();
        int enabled = 0;
        for (int i = 0; i < total; i++) if (model.ruleAt(i).enabled) enabled++;
        int shown = table == null ? total : table.getRowCount();
        summary.setText(enabled + " of " + total + " rules enabled"
                + (shown != total ? ", " + shown + " shown" : ""));
    }

    private static int toInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; } }
    private static String nz(String s) { return s == null ? "" : s; }

    /** One passive rule's current state on the engine. */
    private static final class Rule {
        final int id;
        final String name;
        final String quality;
        boolean enabled;
        String threshold;   // DEFAULT, OFF, LOW, MEDIUM, HIGH
        Rule(int id, String name, String quality, boolean enabled, String threshold) {
            this.id = id; this.name = name; this.quality = quality;
            this.enabled = enabled; this.threshold = threshold;
        }
    }

    private final class RuleTableModel extends AbstractTableModel {
        private final String[] cols = {"enabled", "id", "rule", "quality", "threshold"};
        private List<Rule> rules = new ArrayList<>();

        void setRules(List<Rule> r) { this.rules = r; fireTableDataChanged(); }
        Rule ruleAt(int row) { return rules.get(row); }

        @Override public int getRowCount()    { return rules.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }

        @Override public Class<?> getColumnClass(int c) {
            return switch (c) { case 0 -> Boolean.class; case 1 -> Integer.class; default -> String.class; };
        }

        @Override public boolean isCellEditable(int r, int c) {
            return c == 0 || (c == 4 && rules.get(r).enabled);
        }

        @Override public Object getValueAt(int r, int c) {
            Rule m = rules.get(r);
            return switch (c) {
                case 0 -> m.enabled;
                case 1 -> m.id;
                case 2 -> m.name;
                case 3 -> m.quality;
                default -> m.threshold;
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            Rule m = rules.get(r);
            if (c == 0 && v instanceof Boolean on) {
                m.enabled = on;
                final int id = m.id;
                new Thread(() -> { try { api.setPassiveEnabled(String.valueOf(id), on); } catch (Exception ignore) {} },
                        "pscan-set").start();
                fireTableRowsUpdated(r, r);
                refreshSummary();
            } else if (c == 4 && v instanceof String t) {
                m.threshold = t;
                final int id = m.id;
                new Thread(() -> { try { api.setPassiveThreshold(String.valueOf(id), t); } catch (Exception ignore) {} },
                        "pscan-set").start();
                fireTableRowsUpdated(r, r);
            }
        }
    }

    private static final class SimpleDoc implements javax.swing.event.DocumentListener {
        private final Runnable action;
        SimpleDoc(Runnable action) { this.action = action; }
        @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { action.run(); }
        @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { action.run(); }
        @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { action.run(); }
    }
}
