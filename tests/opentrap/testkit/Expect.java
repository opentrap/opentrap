package opentrap.testkit;

import opentrap.core.Issue;

/**
 * Assertions for the test suite.
 *
 * <p>Every failure message says what was expected and what happened, so a
 * failing test does not need the debugger to understand.
 */
public final class Expect {

    private Expect() {}

    public static void isTrue(boolean condition, String what) {
        if (!condition) throw new AssertionError("expected " + what);
    }

    public static void isFalse(boolean condition, String what) {
        if (condition) throw new AssertionError("expected not " + what);
    }

    public static void equal(Object actual, Object expected, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected <" + expected + "> but was <" + actual + ">");
        }
    }

    public static void isNull(Object actual, String what) {
        if (actual != null) throw new AssertionError(what + ": expected null but was <" + actual + ">");
    }

    public static void notNull(Object actual, String what) {
        if (actual == null) throw new AssertionError(what + ": expected a value but was null");
    }

    public static void severity(Issue i, Issue.Severity expected) {
        notNull(i, "finding");
        equal(i.severity(), expected, "severity of \"" + i.title() + "\"");
    }

    public static void confidence(Issue i, Issue.Confidence expected) {
        notNull(i, "finding");
        equal(i.confidence(), expected, "confidence of \"" + i.title() + "\"");
    }

    /** The finding quotes the given text as its evidence. */
    public static void evidence(Issue i, String contains) {
        notNull(i, "finding");
        if (i.evidence() == null || !i.evidence().contains(contains)) {
            throw new AssertionError("expected evidence of \"" + i.title() + "\" to contain <"
                    + contains + "> but was <" + (i.evidence() == null ? "" : i.evidence()) + ">");
        }
    }

    public static void param(Issue i, String expected) {
        notNull(i, "finding");
        equal(i.param(), expected, "parameter of \"" + i.title() + "\"");
    }
}
