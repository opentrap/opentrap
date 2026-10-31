package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import java.awt.Dimension;

/**
 * Holds text inputs to one shape.
 *
 * <p>The suite used to pick a column count per call site, twenty different
 * ones across a hundred-odd fields, laid out by two different form helpers.
 * No two screens lined up: fields started at different places and ran to
 * different lengths on the same page. These cases pin the standard so it
 * cannot drift back one call site at a time.
 */
public final class FieldUniformityTest {

    public void run() {
        Harness.suite("field uniformity");

        Harness.test("a plain field is the standard width", () -> {
            Expect.equal(Ui.field("").getColumns(), Ui.FIELD_COLUMNS, "the standard width");
            Expect.equal(Ui.passwordField("").getColumns(), Ui.FIELD_COLUMNS,
                    "a password field matches a text field");
        });

        Harness.test("a number field is narrow and keeps its own width", () -> {
            JTextField n = Ui.shortField("8080");
            Expect.equal(n.getColumns(), Ui.SHORT_COLUMNS, "the narrow width");
            // Marked natural, so a form leaves it alone rather than stretching
            // a port box across the page.
            JPanel form = Ui.form("Port:", n);
            Expect.equal(form.getComponentCount(), 2, "the row has a label and a field");
        });

        Harness.test("every field in a form starts at the same place", () -> {
            JTextField a = Ui.field("");
            JTextField b = Ui.field("");
            JTextField c = Ui.field("");
            JPanel form = Ui.form("Short:", a, "A much longer label:", b, "Mid:", c);
            form.setSize(600, 200);
            form.doLayout();
            layoutDeep(form);
            Expect.equal(a.getX(), b.getX(), "two fields start at different places");
            Expect.equal(b.getX(), c.getX(), "two fields start at different places");
        });

        Harness.test("every stretched field in a form ends at the same place", () -> {
            JTextField a = Ui.field("");
            JTextField b = Ui.field("");
            JPanel form = Ui.form("Short:", a, "A much longer label:", b);
            form.setSize(600, 200);
            form.doLayout();
            layoutDeep(form);
            Expect.equal(a.getX() + a.getWidth(), b.getX() + b.getWidth(),
                    "two fields end at different places");
        });

        Harness.test("a full-width row does not disturb the field column", () -> {
            // A radio button owning its row must not shift the fields beneath.
            JTextField a = Ui.field("");
            JTextField b = Ui.field("");
            JPanel form = Ui.form(new JLabel("A choice that owns its row"), null,
                                  "File:", a,
                                  "Password:", b);
            form.setSize(600, 200);
            form.doLayout();
            layoutDeep(form);
            Expect.equal(a.getX(), b.getX(), "the fields drifted apart");
            Expect.equal(a.getWidth(), b.getWidth(), "the fields are different widths");
        });
    }

    private static void layoutDeep(java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component ch : k.getComponents()) layoutDeep(ch);
        }
    }
}
