package opentrap.ui.settings;

import opentrap.core.Settings;
import opentrap.core.SuiteContext;
import opentrap.ui.OptionsPage;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/** Settings > Suite pages: REST API, Updates, Performance, Temporary files location, Startup behavior, Shutdown behavior. */
public final class SuitePages {
    private SuitePages() {}

    public static final String CONFIRM_CLOSE = "suite.confirmClose";

    public static SettingsPage restApi(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JLabel url = Ui.label("Service URL: " + s.get("rest.url", "http://127.0.0.1:1337"));
        JButton change = Ui.button("Change...");
        change.addActionListener(e -> {
            String v = JOptionPane.showInputDialog(change, "Service URL:", s.get("rest.url", "http://127.0.0.1:1337"));
            if (v != null && !v.isBlank()) { s.set("rest.url", v.strip()); url.setText("Service URL: " + v.strip()); }
        });
        p.block("REST API", SettingsPage.Kind.USER,
                "The REST API lets other tools drive OpenTRAP: start scans, read issues, fetch traffic. It listens on the service URL "
              + "and requires an API key unless you choose otherwise.",
                () -> SettingsPage.clearPrefix(s, "rest."),
                Ui.row(url, change),
                SettingsPage.check(s, "rest.running", false, "Service running"),
                SettingsPage.check(s, "rest.noKey", false, "Allow access without API key (using this option is not secure)"),
                Ui.bold("API Keys"),
                new KeyedTable(s, "rest.keys", new String[]{"Enabled", "Name", "Created"}, 100, "Add", "Edit", "Remove"));
        return p;
    }

    public static SettingsPage updates(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JButton check = Ui.primaryButton("Check for updates", "open the project's releases page");
        check.addActionListener(e -> {
            try { Desktop.getDesktop().browse(java.net.URI.create(opentrap.ui.Unbuilt.PROJECT_URL + "/releases")); } catch (Exception ignore) { }
        });
        p.block("Updates [installer version only]", SettingsPage.Kind.USER,
                "Whether OpenTRAP checks for a newer release when it starts, and from which channel.",
                () -> SettingsPage.clearPrefix(s, "updates."),
                SettingsPage.check(s, "updates.auto", false, "Enable auto-updates (requires restart)"),
                SettingsPage.labelled("Channel:", SettingsPage.combo(s, "updates.channel", new String[]{"No Channel Selected", "Stable", "Early adopter"}, "No Channel Selected")),
                check);
        return p;
    }

    public static SettingsPage performance(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        String id = s.get("perf.debugId", "");
        if (id.isEmpty()) { id = java.util.UUID.randomUUID().toString(); s.set("perf.debugId", id); }
        JTextField debugId = Ui.field(id, 30);
        debugId.setEditable(false);
        JButton copy = Ui.button("Copy");
        copy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(debugId.getText()), null));
        JButton email = Ui.button("Email support to report a bug");
        email.addActionListener(e -> opentrap.ui.Unbuilt.openProject(email));
        p.block("Performance feedback", SettingsPage.Kind.USER,
                "Help improve OpenTRAP by sharing anonymous performance data, or log it locally to attach to a bug report.",
                () -> { s.setBool("perf.submit", false); s.setBool("perf.logLocal", false); },
                SettingsPage.check(s, "perf.submit", false, "Automatically submit feedback about OpenTRAP's performance"),
                Ui.row(Ui.label("Debug ID:"), debugId, copy), email,
                SettingsPage.check(s, "perf.logLocal", false, "Log performance data to a local directory:"),
                SettingsPage.pathRow(s, "perf.logDir", System.getProperty("user.home") + "/.opentrap/logs", true, "Choose folder ..."));
        long maxMb = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        JRadioButton[] r = SettingsPage.radios(s, "perf.memory.mode", "half", new String[]{"half", "custom"}, "Set to half of system memory", "Set custom value:");
        JTextField custom = SettingsPage.number(s, "perf.memory.mb", (int) maxMb, 8);
        p.block("Maximum memory usage", SettingsPage.Kind.USER,
                "The most memory the Java runtime may use. Takes effect on the next launch (the launcher reads it). Currently allowed: " + maxMb + " MB.",
                () -> { s.set("perf.memory.mode", "half"); },
                Ui.note("More memory lets large projects and long audits run without the suite slowing to a crawl; too much starves the rest of the machine."),
                r[0], Ui.row(r[1], custom, Ui.label("MB")));
        return p;
    }

    public static SettingsPage tempFiles(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JRadioButton[] r = SettingsPage.radios(s, "temp.mode", "default", new String[]{"default", "custom"}, "Use default system temp directory", "Use custom location:");
        p.block("Temporary files location", SettingsPage.Kind.USER,
                "Where large temporary files (streaming responses, engine working data) are written. Default: " + System.getProperty("java.io.tmpdir"),
                () -> { s.set("temp.mode", "default"); s.set("temp.dir", ""); },
                r[0], r[1], OptionsPage.indent(SettingsPage.pathRow(s, "temp.dir", "", true, "Choose folder ..."), 24));
        return p;
    }

    public static SettingsPage startup(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("Automated tasks on startup", SettingsPage.Kind.USER,
                "Whether the startup dialog pre-selects \"Pause automated tasks\", so a reopened project does not start scanning at once.",
                () -> s.setBool("startup.pauseTasks", true),
                SettingsPage.check(s, "startup.pauseTasks", true, "Select \"Pause automated tasks\" by default"));
        p.block("Unrecognized project files", SettingsPage.Kind.USER,
                "Project files can carry extension state. Trusting them loads that state without asking.",
                () -> s.setBool("startup.trustProjects", true),
                SettingsPage.check(s, "startup.trustProjects", true, "Always trust project files"));
        return p;
    }

    public static SettingsPage shutdown(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("OpenTRAP's closing behavior", SettingsPage.Kind.USER,
                "Skip the confirmation dialog when closing the suite.",
                () -> s.setBool(CONFIRM_CLOSE, true),
                SettingsPage.check(s, "suite.noConfirmClose", false, "Don't confirm before closing OpenTRAP"));
        return p;
    }
}
