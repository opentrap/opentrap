package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.ui.theme.FlatTabbedPaneUI;

import javax.swing.JPanel;
import javax.swing.JTabbedPane;

/**
 * Pins the tab UI's listeners to its own lifetime.
 *
 * <p>The hover highlight is driven by a mouse listener the UI adds to the pane.
 * Swing calls uninstallUI whenever a pane's UI is replaced, and that nulls the
 * UI's tabPane field. A listener left attached then fired against a null
 * tabPane and threw on the event thread once per mouse move, which filled the
 * log and stopped the interface repainting.
 */
public final class TabbedPaneListenerTest {

    public void run() {
        Harness.suite("tab UI listeners");

        Harness.test("replacing the UI takes its listeners off the pane", () -> {
            JTabbedPane pane = new JTabbedPane();
            pane.addTab("one", new JPanel());
            pane.setUI(new FlatTabbedPaneUI());
            int withUi = pane.getMouseListeners().length + pane.getMouseMotionListeners().length;

            pane.setUI(new FlatTabbedPaneUI());
            int afterSwap = pane.getMouseListeners().length + pane.getMouseMotionListeners().length;
            Expect.equal(afterSwap, withUi, "listeners after the UI is replaced once");

            for (int i = 0; i < 5; i++) pane.setUI(new FlatTabbedPaneUI());
            int afterMany = pane.getMouseListeners().length + pane.getMouseMotionListeners().length;
            Expect.equal(afterMany, withUi, "listeners after the UI is replaced repeatedly");
        });

        Harness.test("retiring the UI leaves nothing attached to fire later", () -> {
            JTabbedPane pane = new JTabbedPane();
            pane.addTab("one", new JPanel());

            FlatTabbedPaneUI ui = new FlatTabbedPaneUI();
            pane.setUI(ui);
            Expect.isTrue(pane.getMouseListeners().length + pane.getMouseMotionListeners().length > 0,
                    "the UI attaches listeners while it is installed");

            // Every mouse listener on the pane came from a UI, so once the UI
            // is retired none may remain: a leftover one would fire against the
            // null tabPane it still points at.
            ui.uninstallUI(pane);
            Expect.equal(pane.getMouseListeners().length, 0, "mouse listeners left after the UI is retired");
            Expect.equal(pane.getMouseMotionListeners().length, 0, "motion listeners left after the UI is retired");
        });

    }
}
