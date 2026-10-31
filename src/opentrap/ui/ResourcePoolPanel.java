package opentrap.ui;

import opentrap.core.ResourcePool;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;

/**
 * The resource pool chooser every task uses: pick an existing pool from the
 * table, or describe a new one (concurrency, pacing, automatic throttling).
 * One component, three homes: the Intruder side panel, the New scan wizard
 * and the live task dialog. {@link #selected()} returns the pool the task
 * should use, creating and registering a new one when that was chosen.
 */
public final class ResourcePoolPanel extends OptionsPage {

    private final JRadioButton useExisting = Ui.radio("Use existing resource pool", true);
    private final JRadioButton createNew = Ui.radio("Create new resource pool", false);
    private final PoolModel model = new PoolModel();
    private final JTable table = new JTable(model);

    private final JTextField name = Ui.field("", 24);
    private final JCheckBox limitConcurrent = Ui.check("Maximum concurrent requests:", true);
    private final JSpinner concurrent = spinner(10, 1, 100, 1);
    private final JCheckBox delayOn = Ui.check("Delay between requests:", false);
    private final JSpinner delayMs = spinner(0, 0, 60000, 50);
    private final JRadioButton fixed = Ui.radio("Fixed", true);
    private final JRadioButton random = Ui.radio("With random variations", false);
    private final JRadioButton increment = Ui.radio("Increase delay in increments of:", false);
    private final JSpinner incrementMs = spinner(0, 0, 60000, 50);
    private final JCheckBox throttleOn = Ui.check("Automatic throttling", false);
    private final JCheckBox t429 = Ui.check("429", true);
    private final JCheckBox t503 = Ui.check("503", false);
    private final JCheckBox tOther = Ui.check("Other", false);
    private final JTextField tOtherCodes = Ui.field("", 14);

    public ResourcePoolPanel() {
        ButtonGroup mode = new ButtonGroup();
        mode.add(useExisting); mode.add(createNew);
        ButtonGroup delay = new ButtonGroup();
        delay.add(fixed); delay.add(random); delay.add(increment);

        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Ui.distributeWidths(table, 60, 180, 110, 100, 90, 100, 90);
        table.getColumnModel().getColumn(0).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
                super.getTableCellRendererComponent(t, "", sel, f, r, c);
                setIcon(Boolean.TRUE.equals(v) ? Glyphs.tick(14, Ui.ACCENT) : null);
                setHorizontalAlignment(CENTER);
                return this;
            }
        });
        if (model.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(620, 110));
        ResourcePool.addListener(() -> SwingUtilities.invokeLater(() -> {
            int sel = table.getSelectedRow();
            model.fireTableDataChanged();
            if (sel >= 0 && sel < model.getRowCount()) table.setRowSelectionInterval(sel, sel);
            else if (model.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
        }));

        tOtherCodes.putClientProperty("JTextField.placeholderText", "CSV format");
        JPanel delayModes = Ui.vbox(fixed, random, Ui.row(increment, incrementMs, Ui.label("ms")));
        JPanel throttle = Ui.vbox(throttleOn, OptionsPage.indent(Ui.row(t429, t503, tOther, tOtherCodes), 24));
        JPanel form = Ui.vbox(
                Ui.row(Ui.label("Name:"), name),
                Ui.row(limitConcurrent, concurrent),
                Ui.row(delayOn, delayMs, Ui.label("ms")),
                OptionsPage.indent(delayModes, 24),
                throttle);

        section("Resource pool",
                Ui.note("A resource pool limits how many requests the task has in flight at once and how it paces them. "
                      + "Pools are shared between tasks; manage them under Settings > Project > Tasks."),
                useExisting, OptionsPage.indent(sp, 24),
                createNew, OptionsPage.indent(form, 24));

        Runnable sync = () -> {
            boolean n = createNew.isSelected();
            table.setEnabled(!n);
            for (JComponent c : new JComponent[]{name, limitConcurrent, concurrent, delayOn, delayMs, fixed, random,
                    increment, incrementMs, throttleOn, t429, t503, tOther, tOtherCodes}) c.setEnabled(n);
            if (n) {
                concurrent.setEnabled(limitConcurrent.isSelected());
                boolean d = delayOn.isSelected();
                delayMs.setEnabled(d); fixed.setEnabled(d); random.setEnabled(d); increment.setEnabled(d);
                incrementMs.setEnabled(d && increment.isSelected());
                boolean t = throttleOn.isSelected();
                t429.setEnabled(t); t503.setEnabled(t); tOther.setEnabled(t);
                tOtherCodes.setEnabled(t && tOther.isSelected());
            }
        };
        for (AbstractButton b : new AbstractButton[]{useExisting, createNew, limitConcurrent, delayOn, fixed, random,
                increment, throttleOn, tOther}) b.addItemListener(e -> sync.run());
        sync.run();
    }

    private static JSpinner spinner(int val, int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(val, min, max, step));
        s.setFont(Ui.PLAIN);
        s.setPreferredSize(new Dimension(80, s.getPreferredSize().height));
        return s;
    }

    /** The pool to use. Creating a new one registers it so other tasks can reuse it. */
    public ResourcePool selected() {
        if (useExisting.isSelected()) {
            int r = table.getSelectedRow();
            java.util.List<ResourcePool> all = ResourcePool.all();
            return r >= 0 && r < all.size() ? all.get(r) : ResourcePool.byName(ResourcePool.DEFAULT_NAME);
        }
        ResourcePool p = new ResourcePool(name.getText().strip());
        p.setLimitConcurrent(limitConcurrent.isSelected());
        p.setConcurrent((Integer) concurrent.getValue());
        p.setDelayOn(delayOn.isSelected());
        p.setDelayMs((Integer) delayMs.getValue());
        p.setDelayMode(random.isSelected() ? ResourcePool.DelayMode.RANDOM
                : increment.isSelected() ? ResourcePool.DelayMode.INCREMENT : ResourcePool.DelayMode.FIXED);
        p.setIncrementMs((Integer) incrementMs.getValue());
        p.setThrottleOn(throttleOn.isSelected());
        p.setThrottle429(t429.isSelected());
        p.setThrottle503(t503.isSelected());
        p.setThrottleOther(tOther.isSelected() ? tOtherCodes.getText().strip() : "");
        ResourcePool.add(p);
        // After creating it, the panel shows it as the chosen existing pool.
        useExisting.setSelected(true);
        int idx = ResourcePool.all().indexOf(p);
        if (idx >= 0) table.setRowSelectionInterval(idx, idx);
        return p;
    }

    /** The concurrency the chosen pool allows, for engines that take a thread count. */
    public int concurrency() {
        if (useExisting.isSelected()) {
            int r = table.getSelectedRow();
            java.util.List<ResourcePool> all = ResourcePool.all();
            return (r >= 0 && r < all.size() ? all.get(r) : all.get(0)).effectiveConcurrent();
        }
        return limitConcurrent.isSelected() ? (Integer) concurrent.getValue() : 50;
    }

    /** Preselect a pool by name (a task being edited shows the pool it uses). */
    public void select(String poolName) {
        java.util.List<ResourcePool> all = ResourcePool.all();
        for (int i = 0; i < all.size(); i++) if (all.get(i).name().equals(poolName)) {
            useExisting.setSelected(true);
            table.setRowSelectionInterval(i, i);
            return;
        }
    }

    private final class PoolModel extends AbstractTableModel {
        private final String[] cols = {"Selected", "Resource pool", "Concurrent requests", "Request delay",
                "Random delay", "Delay increment", "Auto throttle"};
        @Override public int getRowCount() { return ResourcePool.all().size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            ResourcePool p = ResourcePool.all().get(r);
            return switch (c) {
                case 0 -> table.getSelectedRow() == r;
                case 1 -> p.name();
                case 2 -> p.limitConcurrent() ? String.valueOf(p.concurrent()) : "Unlimited";
                case 3 -> p.requestDelayText();
                case 4 -> p.randomDelayText();
                case 5 -> p.delayIncrementText();
                default -> p.autoThrottleText();
            };
        }
    }
}
