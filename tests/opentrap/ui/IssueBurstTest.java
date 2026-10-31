package opentrap.ui;

import opentrap.core.HttpService;
import opentrap.core.Issue;
import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * A scan reports findings one at a time, and the panels that show them must not
 * rebuild themselves once per finding.
 *
 * <p>A scan that found a thousand issues used to stop the window answering for
 * five to ten seconds at a stretch: two listeners rebuilt a whole table and a
 * whole tree for every single finding, on the event thread. The dashboard had
 * always asked through a coalescer; the other two did not. This case counts the
 * rebuilds rather than trusting that they are few.
 */
public final class IssueBurstTest {

    public void run() {
        Harness.suite("issue bursts");

        Harness.test("a burst of findings collapses into few rebuilds", () -> {
            SuiteContext ctx = new SuiteContext();
            final int[] rebuilds = {0};
            Coalescer c = new Coalescer(250, () -> rebuilds[0]++);
            ctx.addIssueListener(new SuiteContext.IssueListener() {
                @Override public void issueAdded(Issue i)  { c.request(); }
                @Override public void issuesReloaded()     { c.request(); }
            });

            int found = 500;
            for (int i = 0; i < found; i++) ctx.addIssue(issue(i));

            // The coalescer defers to the event thread, so let it settle.
            drainEventThread();
            Expect.isTrue(rebuilds[0] <= 5,
                    "a burst of " + found + " findings cost " + rebuilds[0] + " rebuilds");
        });

        Harness.test("a lone finding is still shown at once", () -> {
            // Collapsing a burst must not make a single finding wait.
            final int[] ran = {0};
            Coalescer c = new Coalescer(250, () -> ran[0]++);
            c.request();
            drainEventThread();
            try { Thread.sleep(120); } catch (InterruptedException ignored) { }
            drainEventThread();
            Expect.isTrue(ran[0] >= 1, "a single request was not served promptly");
        });

        Harness.test("a thousand findings are still all recorded", () -> {
            // Collapsing the redraws must not drop any of the data.
            SuiteContext ctx = new SuiteContext();
            for (int i = 0; i < 1000; i++) ctx.addIssue(issue(i));
            Expect.equal(ctx.issues().size(), 1000, "issues recorded");
        });
    }

    private static Issue issue(int n) {
        return new Issue("Finding " + n, Issue.Severity.LOW, Issue.Confidence.CERTAIN,
                new HttpService("example.com", 443, true), "/path/" + n, null,
                "detail", "background", "remediation", null, null);
    }

    private static void drainEventThread() {
        try {
            for (int i = 0; i < 3; i++) javax.swing.SwingUtilities.invokeAndWait(() -> { });
        } catch (Exception ignored) { }
    }
}
