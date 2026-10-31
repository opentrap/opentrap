package opentrap.dashboard;

import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JSpinner;

/**
 * Builds the scan configuration panel and drives it the way the wizard does.
 *
 * <p>New scan did nothing at all in a shipped build. The panel pulled the text
 * field out of each spinner's editor and put it on a row, which takes it out of
 * the editor, since a component has one parent. The editor was left childless
 * and the next setValue threw, so the wizard died on the way up and no window
 * ever appeared.
 *
 * <p>Nothing caught it because the tests and the manual sweeps opened tabs and
 * pages; neither pressed the button. These cases press it.
 */
public final class ScanWizardTest {

    private interface Body { void run() throws Exception; }

    private static void check(String name, Body body) {
        Harness.test(name, () -> {
            try { body.run(); }
            catch (AssertionError e) { throw e; }
            catch (Exception e) { throw new AssertionError(e.toString(), e); }
        });
    }

    public void run() {
        Harness.suite("scan wizard");

        check("the configuration panel builds in every mode", () -> {
            for (ScanConfigPanel.Mode m : ScanConfigPanel.Mode.values()) {
                ScanConfigPanel panel = new ScanConfigPanel(m);
                Expect.notNull(panel.config(), m + " produced no configuration");
            }
        });

        check("every spinner keeps its own editor", () -> {
            // A spinner whose editor has been emptied throws on setValue, and
            // the failure surfaces far from the cause.
            ScanConfigPanel panel = new ScanConfigPanel(ScanConfigPanel.Mode.CRAWL_AND_AUDIT);
            int checked = 0;
            for (JSpinner s : spinnersIn(panel)) {
                javax.swing.JComponent ed = (javax.swing.JComponent) s.getEditor();
                Expect.isTrue(ed.getComponentCount() > 0,
                        "a spinner was left with an empty editor");
                s.setValue(s.getValue());   // the operation that used to throw
                checked++;
            }
            Expect.isTrue(checked > 0, "the panel has spinners to check");
        });

        check("the wizard itself can be constructed", () -> {
            SuiteContext ctx = new SuiteContext();
            NewScanWizard w = new NewScanWizard(ctx, null);
            Expect.isFalse(w.started(), "a wizard that was never run reports started");
            w.dispose();
        });
    }

    private static java.util.List<JSpinner> spinnersIn(java.awt.Component c) {
        java.util.List<JSpinner> out = new java.util.ArrayList<>();
        collect(c, out);
        return out;
    }

    private static void collect(java.awt.Component c, java.util.List<JSpinner> out) {
        if (c instanceof JSpinner s) out.add(s);
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component ch : k.getComponents()) collect(ch, out);
        }
    }
}
