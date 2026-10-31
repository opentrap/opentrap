package opentrap.ui;

import opentrap.core.ProjectOptions;
import opentrap.core.SuiteContext;

import javax.swing.*;
import java.awt.*;

/**
 * Settings > Project: what the project file keeps, how much audit traffic the
 * session holds in memory, and whether the proxy records out-of-scope traffic.
 * Every control writes a project setting, so the choices travel with the file.
 */
public final class ProjectSettingsPanel extends JPanel {

    private final opentrap.core.Settings settings;
    private java.util.function.Consumer<String> prefListener;

    public ProjectSettingsPanel(SuiteContext ctx) {
        super(new BorderLayout());
        this.settings = ctx.settings();
        OptionsPage page = new OptionsPage();
        var s = settings;

        JCheckBox keepAudit = Ui.check("keep full audit responses in the project",
                ProjectOptions.keepAuditBodies(s));
        keepAudit.addActionListener(e -> s.setBool(ProjectOptions.KEEP_AUDIT_BODIES, keepAudit.isSelected()));
        JTextArea keepNote = Ui.note(
                "An audit sends thousands of probe requests per endpoint. By default the project keeps "
                + "each probe's request and response headers, with the response length, and leaves the "
                + "response body out; every finding already carries its own full request and response. "
                + "Tick this to keep every probe's body as well, at the cost of a far larger file.");

        JSpinner cap = new JSpinner(new SpinnerNumberModel(s.getInt(ProjectOptions.BODY_CAP_KB, 0), 0, 1_000_000, 64));
        cap.setFont(Ui.PLAIN);
        cap.addChangeListener(e -> s.setInt(ProjectOptions.BODY_CAP_KB, (Integer) cap.getValue()));
        JPanel capRow = Ui.form("largest response body kept (KB):", cap);
        JTextArea capNote = Ui.note(
                "0 keeps every body whole. With a cap, a response body larger than this is cut at the "
                + "cap when the project is written and the viewer says so; the full length is kept. "
                + "Useful on sites that serve large downloads or media through the proxy.");
        page.section("project file", keepAudit, keepNote, capRow, capNote);

        JSpinner mem = new JSpinner(new SpinnerNumberModel(
                s.getInt(ProjectOptions.AUDIT_MEMORY_MB, ProjectOptions.DEFAULT_AUDIT_MEMORY_MB), 0, 100_000, 50));
        mem.setFont(Ui.PLAIN);
        mem.addChangeListener(e -> {
            s.setInt(ProjectOptions.AUDIT_MEMORY_MB, (Integer) mem.getValue());
            ctx.store().setAuditCapBytes(ProjectOptions.auditMemoryBytes(s));
        });
        JPanel memRow = Ui.form("audit traffic kept in memory (MB):", mem);
        JTextArea memNote = Ui.note(
                "The Logger shows every probe an audit sends while the session is open. Past this "
                + "amount the oldest probes are dropped as new ones arrive, so a long audit cannot "
                + "exhaust memory. 0 keeps everything. Findings, the crawl and everything done by "
                + "hand are never dropped.");
        page.section("memory", memRow, memNote);

        JCheckBox logoos = Ui.check("log out-of-scope traffic", ProjectOptions.logOutOfScope(s));
        logoos.addActionListener(e -> s.setBool(ProjectOptions.LOG_OUT_OF_SCOPE, logoos.isSelected()));
        // Registered only while the panel is on screen (see addNotify); the
        // Settings dialog builds a fresh panel each time it opens, so a
        // constructor-registered listener would accumulate on the app-lifetime
        // Settings object for every dialog open.
        prefListener = key -> {
            if (ProjectOptions.LOG_OUT_OF_SCOPE.equals(key))
                SwingUtilities.invokeLater(() -> logoos.setSelected(ProjectOptions.logOutOfScope(s)));
        };
        String[] choices = {"ask each time", "stop logging out-of-scope traffic", "keep logging everything"};
        String[] keys = {"ask", "stop", "keep"};
        JComboBox<String> onScope = Ui.combo(choices);
        String current = ProjectOptions.outOfScopeChoice(s);
        for (int i = 0; i < keys.length; i++) if (keys[i].equals(current)) onScope.setSelectedIndex(i);
        onScope.addActionListener(e -> s.set(ProjectOptions.OUT_OF_SCOPE_CHOICE, keys[onScope.getSelectedIndex()]));
        JPanel scopeRow = Ui.form("when an item is added to scope:", onScope);
        JTextArea scopeNote = Ui.note(
                "With out-of-scope logging off, the proxy still passes everything through but records "
                + "only in-scope traffic in the history, the site map and the Logger, so browser noise "
                + "from other sites never lands in the project.");
        page.section("proxy history", logoos, scopeRow, scopeNote);

        add(page, BorderLayout.CENTER);
    }

    /** On screen: start hearing setting changes. */
    @Override public void addNotify() {
        super.addNotify();
        if (prefListener != null) settings.addListener(prefListener);
    }

    /** Off screen: stop hearing them, so a closed Settings dialog takes its
     *  listener with it instead of leaving it on Settings forever. */
    @Override public void removeNotify() {
        if (prefListener != null) settings.removeListener(prefListener);
        super.removeNotify();
    }
}
