package opentrap.ui.settings;

import opentrap.core.ResourcePool;
import opentrap.core.Settings;
import opentrap.core.SuiteContext;
import opentrap.oast.Collaborator;
import opentrap.ui.OptionsPage;
import opentrap.ui.ScopeEditor;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;

/** Settings > Project pages: Scope, Collaborator, Tasks, Automatic backup, Logging. */
public final class ProjectPages {
    private ProjectPages() {}

    public static SettingsPage scope(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("Target scope", SettingsPage.Kind.PROJECT,
                "Target scope is a key configuration setting. It controls which hosts and URLs the tools treat as in scope: "
              + "the site map, the proxy history and the scanner can all be limited to it.",
                null, new ScopeEditor(ctx, ctx.scope()));
        JRadioButton[] r = SettingsPage.radios(s, "scope.outOfScope.mode", "suite", new String[]{"suite", "custom"},
                "Use suite scope [defined above]", "Use custom scope");
        p.block("Out-of-scope request handling", SettingsPage.Kind.PROJECT,
                "Use these settings to stop the tools making any requests to out-of-scope hosts, by dropping them at the proxy.",
                () -> { s.setBool("scope.dropOutOfScope", false); s.set("scope.outOfScope.mode", "suite"); },
                SettingsPage.check(s, "scope.dropOutOfScope", false, "Drop all out-of-scope requests"),
                OptionsPage.indent(Ui.vbox(r[0], r[1]), 24));
        return p;
    }

    public static SettingsPage collaborator(SuiteContext ctx) {
        Settings s = ctx.settings();
        Collaborator collab = ctx.collaborator();
        SettingsPage p = new SettingsPage();
        String mode = s.get(Collaborator.SERVER_KEY, "").isEmpty() ? "default" : s.get("oast.mode", "private");
        JRadioButton useDefault = Ui.radio("Use the default Collaborator server", "default".equals(mode));
        JRadioButton none = Ui.radio("Don't use Collaborator", "none".equals(mode));
        JRadioButton priv = Ui.radio("Use a private Collaborator server", "private".equals(mode));
        ButtonGroup g = new ButtonGroup();
        g.add(useDefault); g.add(none); g.add(priv);
        JTextField server = Ui.field("private".equals(mode) ? s.get(Collaborator.SERVER_KEY, "") : "", 34);
        JTextField polling = Ui.field(s.get("oast.polling", ""), 34);
        JTextField token = Ui.field(s.get(Collaborator.TOKEN_KEY, ""), 34);
        JCheckBox unencrypted = SettingsPage.check(s, "oast.pollHttp", false, "Poll over unencrypted HTTP");
        Runnable apply = () -> {
            String m = none.isSelected() ? "none" : priv.isSelected() ? "private" : "default";
            s.set("oast.mode", m);
            s.set(Collaborator.SERVER_KEY, priv.isSelected() ? server.getText().strip() : "");
            s.set("oast.polling", polling.getText().strip());
            s.set(Collaborator.TOKEN_KEY, token.getText().strip());
            boolean on = !none.isSelected();
            server.setEnabled(priv.isSelected()); polling.setEnabled(priv.isSelected()); token.setEnabled(priv.isSelected());
            unencrypted.setEnabled(on);
        };
        for (AbstractButton b : new AbstractButton[]{useDefault, none, priv}) b.addItemListener(e -> apply.run());
        for (JTextField f : new JTextField[]{server, polling, token}) f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { apply.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { apply.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { apply.run(); }
        });
        apply.run();
        JButton health = Ui.button("Run health check ...", "restart the Collaborator client and report whether it registered");
        health.addActionListener(e -> {
            String err = collab.start();
            JOptionPane.showMessageDialog(health, err == null
                    ? "Health check passed. Registered with " + collab.serverUrl() + "\nPayload host: " + collab.payloadHost()
                    : "Health check failed: " + err, "Collaborator health check", err == null ? JOptionPane.INFORMATION_MESSAGE : JOptionPane.WARNING_MESSAGE);
        });
        p.block("Collaborator server", SettingsPage.Kind.BOTH,
                "The Collaborator server catches out-of-band interactions (DNS, HTTP, SMTP) that a target makes to hostnames the "
              + "scanner planted, which is how blind vulnerabilities are confirmed. Use the default public server, or your own.",
                () -> { useDefault.setSelected(true); server.setText(""); polling.setText(""); token.setText(""); s.setBool("oast.pollHttp", false); },
                SettingsPage.overrideRow(s, "oast.overrideProject"),
                useDefault, none, priv,
                OptionsPage.indent(Ui.form("Server location:", server, "Polling location (optional):", polling, "Auth token (optional):", token), 24),
                unencrypted, health);
        return p;
    }

    public static SettingsPage tasks(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("Resource pools", SettingsPage.Kind.PROJECT,
                "A resource pool limits how many requests its tasks have in flight at once and how they are paced. "
              + "Every task picks a pool when it is created.",
                null, new PoolsTable());
        JRadioButton[] r = SettingsPage.radios(s, "tasks.autostart", "start", new String[]{"start", "pause"}, "Start tasks", "Pause tasks");
        p.block("New task auto-start", SettingsPage.Kind.PROJECT,
                "Whether a newly created task starts running at once or waits paused until you resume it.",
                () -> s.set("tasks.autostart", "start"), Ui.row(r[0], r[1]));
        p.block("Schedule tasks", SettingsPage.Kind.PROJECT,
                "Run a task at a set time, once or on a repeat.",
                null, new KeyedTable(s, "tasks.schedule", new String[]{"Time", "Repeat", "Task"}, 120, "Add", "Edit", "Remove"));
        return p;
    }

    /** The project's resource pools with New / Edit / Delete. */
    private static final class PoolsTable extends JPanel {
        private final AbstractTableModel model = new AbstractTableModel() {
            private final String[] cols = {"Resource pool", "Running tasks", "Concurrent requests", "Request delay", "Random delay", "Delay increment", "Auto throttle"};
            @Override public int getRowCount() { return ResourcePool.all().size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Object getValueAt(int r, int c) {
                ResourcePool x = ResourcePool.all().get(r);
                return switch (c) { case 0 -> x.name(); case 1 -> "0"; case 2 -> x.limitConcurrent() ? String.valueOf(x.concurrent()) : "Unlimited";
                    case 3 -> x.requestDelayText(); case 4 -> x.randomDelayText(); case 5 -> x.delayIncrementText(); default -> x.autoThrottleText(); };
            }
        };
        PoolsTable() {
            super(new BorderLayout(8, 0));
            setOpaque(false);
            JTable t = new JTable(model);
            Ui.styleTable(t);
            t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            Ui.distributeWidths(t, 170, 90, 120, 100, 90, 110, 90);
            JButton neu = Ui.button("New..."), edit = Ui.button("Edit"), del = Ui.button("Delete");
            neu.addActionListener(e -> { ResourcePool x = dialog(null); if (x != null) ResourcePool.add(x); });
            edit.addActionListener(e -> { int i = t.getSelectedRow(); if (i >= 0) { ResourcePool x = ResourcePool.all().get(i); if (dialog(x) != null) ResourcePool.changed(); } });
            del.addActionListener(e -> { int i = t.getSelectedRow(); if (i >= 0) ResourcePool.remove(ResourcePool.all().get(i)); });
            ResourcePool.addListener(() -> SwingUtilities.invokeLater(model::fireTableDataChanged));
            add(Ui.buttonColumn(neu, edit, del), BorderLayout.WEST);
            JScrollPane sp = Ui.scroll(t);
            sp.setPreferredSize(new Dimension(600, 110));
            add(sp, BorderLayout.CENTER);
        }
        private ResourcePool dialog(ResourcePool editing) {
            ResourcePool x = editing == null ? new ResourcePool("") : editing;
            JTextField name = Ui.field(x.name(), 24);
            JCheckBox limit = Ui.check("Maximum concurrent requests:", x.limitConcurrent());
            JSpinner conc = new JSpinner(new SpinnerNumberModel(x.concurrent(), 1, 100, 1));
            JCheckBox delayOn = Ui.check("Delay between requests:", x.delayOn());
            JSpinner delay = new JSpinner(new SpinnerNumberModel(x.delayMs(), 0, 60000, 50));
            JRadioButton fixed = Ui.radio("Fixed", x.delayMode() == ResourcePool.DelayMode.FIXED);
            JRadioButton random = Ui.radio("With random variations", x.delayMode() == ResourcePool.DelayMode.RANDOM);
            JRadioButton inc = Ui.radio("Increase delay in increments of:", x.delayMode() == ResourcePool.DelayMode.INCREMENT);
            ButtonGroup g = new ButtonGroup(); g.add(fixed); g.add(random); g.add(inc);
            JSpinner incMs = new JSpinner(new SpinnerNumberModel(x.incrementMs(), 0, 60000, 50));
            JCheckBox throttle = Ui.check("Automatic throttling", x.throttleOn());
            JCheckBox t429 = Ui.check("429", x.throttle429()), t503 = Ui.check("503", x.throttle503());
            JTextField other = Ui.field(x.throttleOther(), 12);
            JPanel form = Ui.vbox(Ui.row(Ui.label("Name:"), name), Ui.row(limit, conc), Ui.row(delayOn, delay, Ui.label("ms")),
                    OptionsPage.indent(Ui.vbox(fixed, random, Ui.row(inc, incMs, Ui.label("ms"))), 24),
                    throttle, OptionsPage.indent(Ui.row(t429, t503, Ui.label("Other:"), other), 24));
            form.setBorder(Ui.pad(10, 10, 10, 10));
            int ok = JOptionPane.showConfirmDialog(this, form, editing == null ? "New resource pool" : "Edit resource pool", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return null;
            if (editing == null || !ResourcePool.DEFAULT_NAME.equals(x.name())) x.setName(name.getText().strip().isEmpty() ? "Resource pool" : name.getText().strip());
            x.setLimitConcurrent(limit.isSelected()); x.setConcurrent((Integer) conc.getValue());
            x.setDelayOn(delayOn.isSelected()); x.setDelayMs((Integer) delay.getValue());
            x.setDelayMode(random.isSelected() ? ResourcePool.DelayMode.RANDOM : inc.isSelected() ? ResourcePool.DelayMode.INCREMENT : ResourcePool.DelayMode.FIXED);
            x.setIncrementMs((Integer) incMs.getValue());
            x.setThrottleOn(throttle.isSelected()); x.setThrottle429(t429.isSelected()); x.setThrottle503(t503.isSelected()); x.setThrottleOther(other.getText().strip());
            return x;
        }
    }

    public static SettingsPage backup(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        boolean disk = ctx.projectFile() != null;
        JCheckBox auto = SettingsPage.check(s, "backup.auto", false, "Automatically back up the project every");
        JTextField mins = SettingsPage.number(s, "backup.minutes", 30, 5);
        JCheckBox inScope = SettingsPage.check(s, "backup.inScopeOnly", false, "Include in-scope items only");
        JCheckBox progress = SettingsPage.check(s, "backup.progress", false, "Show progress dialog during backups");
        JCheckBox delete = SettingsPage.check(s, "backup.deleteOnExit", false, "Delete backup file on clean shutdown of OpenTRAP");
        for (JComponent c : new JComponent[]{auto, mins, inScope, progress, delete}) c.setEnabled(disk);
        p.block("Automatic backup [disk projects only]", SettingsPage.Kind.USER,
                disk ? "Write a copy of the project file on a timer, so a crash loses at most this many minutes of work."
                     : "This is a temporary project, which is never written to disk; open or create a disk project to enable backups.",
                () -> SettingsPage.clearPrefix(s, "backup."),
                Ui.row(auto, mins, Ui.label("minutes")), inScope, progress, delete);
        return p;
    }

    public static SettingsPage logging(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        String[] tools = {"All tools", "Proxy", "Scanner", "Intruder", "Repeater", "Extensions", "Sequencer"};
        JPanel grid = new JPanel(new GridLayout(tools.length + 1, 3, 10, 2));
        grid.setOpaque(false);
        grid.add(Ui.bold("Tool")); grid.add(Ui.bold("Requests")); grid.add(Ui.bold("Responses"));
        for (String t : tools) {
            String k = t.toLowerCase().replace(' ', '-');
            grid.add(Ui.label(t));
            grid.add(SettingsPage.check(s, "logging." + k + ".requests", false, ""));
            grid.add(SettingsPage.check(s, "logging." + k + ".responses", false, ""));
        }
        p.block("Logging", SettingsPage.Kind.PROJECT,
                "Write every request and response a tool makes to a log file, in addition to the history the suite keeps. "
              + "Choose the file under Project > Logging > Select file; nothing is written until a box is ticked.",
                () -> SettingsPage.clearPrefix(s, "logging."),
                grid, SettingsPage.pathRow(s, "logging.file", "", false, "Select file ..."));
        return p;
    }
}
