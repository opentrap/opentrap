package opentrap.ui.settings;

import opentrap.core.Settings;
import opentrap.core.SuiteContext;
import opentrap.ui.Ui;

import javax.swing.*;

/** Settings > Tools pages that no tool owns: Comparer, and the browser's data / running / logging blocks. */
public final class ToolPages {
    private ToolPages() {}

    public static final String COMPARER_SYNC = "comparer.syncViews";

    public static SettingsPage comparer(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("Sync Comparer results view", SettingsPage.Kind.USER,
                "Use this setting to control whether the two panes of a new Comparer results window scroll together.",
                () -> s.setBool(COMPARER_SYNC, false),
                SettingsPage.check(s, COMPARER_SYNC, false, "Sync views in new Comparer results windows"));
        return p;
    }

    public static SettingsPage browser(SuiteContext ctx, JComponent existing) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        if (existing != null) p.addSection(existing);
        String def = System.getProperty("user.home") + "/.opentrap/browser";
        p.block("Browser data", SettingsPage.Kind.USER,
                "Choose whether the suite's browser keeps its profile (cookies, local storage, history) between launches, and where.",
                () -> { s.setBool("browser.storeData", true); s.set("browser.dataFolder", def); },
                SettingsPage.check(s, "browser.storeData", true, "Store browser data"),
                Ui.note("Browser data folder (we recommend using a dedicated folder)"),
                SettingsPage.pathRow(s, "browser.dataFolder", def, true, "Select folder ..."),
                Ui.note("Remove all files from the specified folder. The browser must be closed first."),
                clearButton(s));
        p.block("Browser running", SettingsPage.Kind.PROJECT,
                "How the browser process is started.",
                () -> { s.setBool("browser.noSandbox", false); s.setBool("browser.gpu", true); },
                SettingsPage.check(s, "browser.noSandbox", false, "Allow the browser to run without a sandbox"),
                Ui.note("Scanning hostile websites without the sandbox increases the risk to your machine. Leave this off unless the browser cannot start otherwise."),
                SettingsPage.check(s, "browser.gpu", true, "Use the GPU"));
        p.block("Browser logging", SettingsPage.Kind.PROJECT,
                "Keep a log of the communication between the suite and its browser, for diagnosing problems.",
                () -> { s.setBool("browser.log", false); s.set("browser.logFolder", System.getProperty("user.home") + "/.opentrap/logs/browser"); },
                SettingsPage.check(s, "browser.log", false, "Log browser communication"),
                Ui.note("Log folder"),
                SettingsPage.pathRow(s, "browser.logFolder", System.getProperty("user.home") + "/.opentrap/logs/browser", true, "Select folder ..."));
        return p;
    }

    private static JButton clearButton(Settings s) {
        JButton b = Ui.button("Clear all", "delete the browser data folder's contents");
        b.addActionListener(e -> {
            java.io.File dir = new java.io.File(s.get("browser.dataFolder", System.getProperty("user.home") + "/.opentrap/browser"));
            if (!dir.isDirectory()) { JOptionPane.showMessageDialog(b, "There is no browser data folder yet.", "Clear all", JOptionPane.INFORMATION_MESSAGE); return; }
            if (JOptionPane.showConfirmDialog(b, "Delete everything under " + dir + "?", "Clear all", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
            try (var walk = java.nio.file.Files.walk(dir.toPath())) {
                walk.sorted(java.util.Comparator.reverseOrder()).filter(pth -> !pth.equals(dir.toPath())).forEach(pth -> pth.toFile().delete());
            } catch (java.io.IOException ex) { JOptionPane.showMessageDialog(b, "Could not clear: " + ex.getMessage()); }
        });
        return b;
    }
}
