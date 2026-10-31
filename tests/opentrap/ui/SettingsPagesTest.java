package opentrap.ui;

import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.util.List;

/**
 * Builds every page in the settings tree.
 *
 * <p>A page that throws while being built takes the whole dialog down, and
 * nothing notices until someone clicks that one entry in the tree. Five pages
 * did exactly that: the tables they carry were handed to a JTable from a field
 * initialiser, which runs before the constructor has set the column names, so
 * the table asked a model with null columns how many it had.
 *
 * <p>Clicking through the tools does not exercise any of this, which is why it
 * went out. This case clicks every entry for us.
 */
public final class SettingsPagesTest {

    public void run() {
        Harness.suite("settings pages");

        Harness.test("every page in the tree builds", () -> {
            SuiteContext ctx = new SuiteContext();
            List<String> failures = SettingsDialog.buildEveryPage(ctx);
            Expect.equal(String.join("\n    ", failures), "",
                    "pages that threw on the way open");
        });

        Harness.test("a page is built fresh each time it is shown", () -> {
            // The dialog releases its panes when it closes, so a second open
            // must be able to build them again rather than reuse dead ones.
            SuiteContext ctx = new SuiteContext();
            Expect.equal(String.join("\n    ", SettingsDialog.buildEveryPage(ctx)), "",
                    "first build");
            Expect.equal(String.join("\n    ", SettingsDialog.buildEveryPage(ctx)), "",
                    "second build");
        });
    }
}
