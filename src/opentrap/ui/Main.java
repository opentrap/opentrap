package opentrap.ui;

import opentrap.core.SuiteContext;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;

public class Main {
    /**
     * The build identifier, a UTC timestamp (YYYYMMDD-HHMMSS) stamped into the jar
     * manifest by release.sh, so every build is uniquely and sortably identified
     * and the running app reports exactly which one it is. A dev build has none,
     * and reports "dev".
     */
    public static final String VERSION = buildVersion();
    public static final String TITLE = "OpenTRAP " + VERSION;

    private static String buildVersion() {
        try {
            var res = Main.class.getClassLoader().getResources("META-INF/MANIFEST.MF");
            while (res.hasMoreElements()) {
                try (var in = res.nextElement().openStream()) {
                    var mf = new java.util.jar.Manifest(in);
                    var at = mf.getMainAttributes();
                    if ("opentrap.ui.Main".equals(at.getValue("Main-Class"))) {
                        String v = at.getValue("Implementation-Version");
                        if (v != null && !v.isBlank()) return v;
                    }
                } catch (Exception ignore) {}
            }
        } catch (Exception ignore) {}
        return "dev";
    }

    public static volatile String[] launchArgs = new String[0];

    public static void main(String[] args) {
        // Headless probe: print the scale this display should use and exit. The
        // AppImage launcher calls this to choose a scale before the real JVM
        // starts, so it never relaunches inside the AppImage (relaunching there
        // would tear down the AppImage mount and kill the app).
        for (String a : args) {
            if ("--print-scale".equals(a)) {
                System.out.println(UiScale.desired());
                return;
            }
        }

        launchArgs = args;

        // If a non-default interface scale is saved and this JVM has not had it
        // applied yet, relaunch with Java's real HiDPI scaling so the whole UI
        // scales uniformly (fonts, columns, borders), then exit.
        if (!UiScale.applied()) {
            double want = UiScale.desired();
            if (Math.abs(want - 1.0) > 0.001) {
                if (UiScale.relaunchWith(want, args)) return;
            }
        }

        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        Theme.install();

        // Scale the whole interface before any window is built. The factor comes
        // from -Dopentrap.scale, else the saved preference, else 1.0.
        UiScale.installDefaults();

        Path home = Path.of(System.getProperty("user.home"), ".opentrap");

        SwingUtilities.invokeLater(() -> {
            // Ask the user to choose a project before the main window is built,
            // the way Burp does. Cancelling here exits the app.
            ProjectStartupDialog startup = new ProjectStartupDialog(TITLE);
            startup.setVisible(true);
            if (!startup.proceeded()) {
                System.exit(0);
                return;
            }

            SuiteContext ctx = new SuiteContext();
            SuiteFrame frame = new SuiteFrame(ctx, TITLE);
            try {
                Bootstrap.registerTools(ctx, frame, home);
            } catch (Exception e) {
                JOptionPane.showMessageDialog(null,
                        "Failed to start: " + e, "error", JOptionPane.ERROR_MESSAGE);
                e.printStackTrace();
                System.exit(1);
            }
            frame.buildUi();

            // Size to the scaled ideal, but never larger than the screen's work
            // area, so the window always fits even on a small laptop display.
            Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds();
            int w = Math.min(UiScale.px(1280), screen.width);
            int h = Math.min(UiScale.px(860), screen.height);
            frame.setSize(w, h);
            frame.setLocationRelativeTo(null);
            // If even the fitted size fills the screen, start maximised.
            if (w >= screen.width - 8 && h >= screen.height - 8) {
                frame.setExtendedState(Frame.MAXIMIZED_BOTH);
            }
            frame.setVisible(true);
            ctx.alert("suite", "OpenTRAP " + VERSION + " started");

            // Resolve the chosen project now that the window and its tools
            // (whose listeners repopulate on restore) are live.
            java.io.File projectFile = startup.file();
            switch (startup.mode()) {
                case NEW -> {
                    try {
                        StateIO.save(ctx, projectFile);
                        ctx.setProjectFile(projectFile);
                        RecentProjects.record(startup.projectName(), projectFile);
                        ctx.alert("suite", "New project: " + projectFile.getName());
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(frame,
                                "Could not create the project file:\n" + e.getMessage(),
                                "New project", JOptionPane.ERROR_MESSAGE);
                    }
                }
                case OPEN -> {
                    try {
                        int n = StateIO.restore(ctx, projectFile);
                        ctx.setProjectFile(projectFile);
                        RecentProjects.record(startup.projectName(), projectFile);
                        ctx.alert("suite", "Opened project " + projectFile.getName() + " (" + n + " items)");
                    } catch (Exception e) {
                        JOptionPane.showMessageDialog(frame,
                                "Could not open the project file:\n" + e.getMessage(),
                                "Open project", JOptionPane.ERROR_MESSAGE);
                    }
                }
                default -> { /* temporary in-memory project: nothing to load */ }
            }
        });
    }
}
