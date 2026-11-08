package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Issues are de-duplicated into one global set, but each scan must still show
 * the findings it raised. A finding the live passive poller sees first, or that
 * an earlier scan already raised, used to be hidden from a later audit's view:
 * the later audit re-found it as a duplicate and claimed nothing of its own, so
 * its Issues tab was empty even though it had found the vulnerability.
 *
 * <p>The fix records every scan that raises a finding on the issue itself, so a
 * task's view filters by "this scan found it", not by exclusive ownership.
 */
public final class IssueAttributionTest {

    private static final HttpService SVC = new HttpService("example.com", 443, true);

    private static Issue finding(String source) {
        Issue i = new Issue("SQL injection", Issue.Severity.HIGH, Issue.Confidence.CERTAIN,
                SVC, "/login", "user", "detail", "background", "remediation", null, null);
        i.setSource(source);
        return i;
    }

    public void run() {
        Harness.suite("issue attribution");

        Harness.test("a finding re-raised by a later scan is attributed to that scan too", () -> {
            SuiteContext ctx = new SuiteContext();

            // The live passive poller sees it first.
            Expect.isTrue(ctx.addIssue(finding("Live audit")), "the first sighting is a new issue");
            // Then an audit re-raises the same finding: a de-dup hit, not a new issue.
            Expect.isFalse(ctx.addIssue(finding("Scan 2")), "the second sighting de-duplicates");
            // Then a second, later audit of the same target re-raises it again.
            Expect.isFalse(ctx.addIssue(finding("Scan 5")), "the third sighting de-duplicates");

            Expect.equal(ctx.issues().size(), 1, "the store holds one de-duplicated issue");
            Issue held = ctx.issues().get(0);
            Expect.isTrue(held.foundBy("Live audit"), "the passive poller is recorded as a finder");
            Expect.isTrue(held.foundBy("Scan 2"), "the first audit is recorded as a finder");
            Expect.isTrue(held.foundBy("Scan 5"), "the second audit is recorded as a finder, so its view is not empty");
            Expect.isFalse(held.foundBy("Scan 9"), "a scan that never saw it is not a finder");
        });

        Harness.test("the same scan re-raising a finding records it once", () -> {
            SuiteContext ctx = new SuiteContext();
            ctx.addIssue(finding("Scan 2"));
            ctx.addIssue(finding("Scan 2"));
            Issue held = ctx.issues().get(0);
            Expect.equal(held.foundBy().size(), 1, "one finder, recorded once");
            Expect.isTrue(held.foundBy("Scan 2"), "the scan is the finder");
        });

        Harness.test("a finding's primary source and finder set survive a fresh issue", () -> {
            Issue i = finding("Scan 3");
            Expect.equal(i.source(), "Scan 3", "the primary source is the one set");
            Expect.isTrue(i.foundBy("Scan 3"), "setting the source records it as a finder");
        });
    }
}
