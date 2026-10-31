package opentrap.intruder;

import opentrap.core.Settings;
import opentrap.ui.OptionsPage;
import opentrap.ui.Ui;

import javax.swing.*;

/**
 * Suite-wide Intruder defaults, under Settings > Intruder: what a new attack
 * starts with. The per-attack values stay on the Intruder tab's Settings pane.
 */
public class IntruderSettingsPanel extends OptionsPage {

    public static final String THREADS_KEY   = "intruder.threads";
    public static final String THROTTLE_KEY  = "intruder.throttleMs";
    public static final String REDIRECTS_KEY = "intruder.followRedirects";

    public IntruderSettingsPanel(Settings settings) {
        JTextField threads = Ui.shortField(String.valueOf(settings.getInt(THREADS_KEY, 5)));
        JTextField throttle = Ui.shortField(String.valueOf(settings.getInt(THROTTLE_KEY, 0)));
        JCheckBox redirects = Ui.check("follow redirects", settings.getBool(REDIRECTS_KEY, false));

        Runnable save = () -> {
            try { settings.setInt(THREADS_KEY, Math.max(1, Integer.parseInt(threads.getText().trim()))); }
            catch (NumberFormatException ignored) {}
            try { settings.setInt(THROTTLE_KEY, Math.max(0, Integer.parseInt(throttle.getText().trim()))); }
            catch (NumberFormatException ignored) {}
            settings.setBool(REDIRECTS_KEY, redirects.isSelected());
        };
        javax.swing.event.DocumentListener onEdit = new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { save.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { save.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { save.run(); }
        };
        threads.getDocument().addDocumentListener(onEdit);
        throttle.getDocument().addDocumentListener(onEdit);
        redirects.addActionListener(e -> save.run());

        section("Defaults for new attacks",
                Ui.note("The values the Intruder tab starts with. Each attack can still change "
                        + "them on its own Settings pane before it runs."),
                OptionsPage.fieldRow("number of threads:", threads, ""),
                OptionsPage.fieldRow("throttle (ms):", throttle, "between requests"),
                redirects);
    }
}
