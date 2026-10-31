package opentrap.extensions;

import opentrap.core.Settings;
import opentrap.ui.OptionsPage;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/**
 * Settings > Extensions > Core extension settings: startup and load behaviour,
 * and where the Java, Python and Ruby runtimes for extensions live.
 */
public final class CoreExtensionSettingsPanel extends OptionsPage {

    public CoreExtensionSettingsPanel(Settings s) {
        JCheckBox reload = bind(s, "ext.reloadOnStartup", true, "Automatically reload extensions on startup");
        JCheckBox update = bind(s, "ext.updateOnStartup", true, "Automatically update installed extensions on startup");
        section("Startup behavior", reload, update);

        JCheckBox hide = bind(s, "ext.hideReloadDialog", true, "Hide dialog on reload");
        section("Load behavior", hide);

        section("Java environment",
                Ui.note("Folder for loading library JAR files (optional)"),
                folderRow(s, "ext.javaLibs", ""));

        section("Python environment",
                Ui.note("Location of Jython standalone JAR file"),
                fileRow(s, "ext.jython", ""),
                Ui.note("Folder for loading modules (optional)"),
                folderRow(s, "ext.pythonModules", ""));

        section("Ruby environment",
                Ui.note("Location of JRuby JAR file"),
                fileRow(s, "ext.jruby", ""));
    }

    private static JCheckBox bind(Settings s, String key, boolean def, String text) {
        JCheckBox c = Ui.check(text, s.getBool(key, def));
        c.addItemListener(e -> s.setBool(key, c.isSelected()));
        return c;
    }

    private static JPanel folderRow(Settings s, String key, String def) { return pathRow(s, key, def, true); }
    private static JPanel fileRow(Settings s, String key, String def) { return pathRow(s, key, def, false); }

    private static JPanel pathRow(Settings s, String key, String def, boolean folder) {
        JTextField f = Ui.field(s.get(key, def), 40);
        f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { s.set(key, f.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { s.set(key, f.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { s.set(key, f.getText()); }
        });
        JButton pick = Ui.button(folder ? "Select folder ..." : "Select file ...");
        pick.addActionListener(e -> {
            JFileChooser fc = new JFileChooser(f.getText().isBlank() ? System.getProperty("user.home") : f.getText());
            if (folder) fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (fc.showOpenDialog(f) == JFileChooser.APPROVE_OPTION) f.setText(fc.getSelectedFile().getAbsolutePath());
        });
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        p.add(f);
        p.add(pick);
        return p;
    }
}
