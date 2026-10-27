package opentrap.testkit;

import java.util.ArrayList;
import java.util.List;

/**
 * A very small test runner.
 *
 * <p>OpenTRAP vendors only BouncyCastle and a Brotli decoder, and adding a
 * whole test framework to carry the suite is not a trade worth making.
 * This gives the two things the tests actually need: a named case that is
 * isolated from its neighbours, and a report that names what failed.
 *
 * <p>Not thread-safe, and deliberately so. Tests run in order on one thread so
 * that a failure report reads the same way twice.
 */
public final class Harness {

    private Harness() {}

    private static final List<String> failures = new ArrayList<>();
    private static String currentSuite = "";
    private static int passed;
    private static int failed;
    private static int suites;

    /** Begin a group of cases. */
    public static void suite(String name) {
        currentSuite = name;
        suites++;
        System.out.println("  " + name);
    }

    /**
     * Run one case. An {@link AssertionError} fails the case; any other
     * exception fails it too and is reported as an error, because a module that
     * throws is a module that found nothing.
     */
    public static void test(String name, Runnable body) {
        try {
            body.run();
            passed++;
        } catch (AssertionError e) {
            failed++;
            record(name, e.getMessage() == null ? "assertion failed" : e.getMessage());
        } catch (Throwable t) {
            failed++;
            record(name, "threw " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : ": " + t.getMessage()));
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(4, st.length); i++) {
                failures.add("        at " + st[i]);
            }
        }
    }

    private static void record(String name, String detail) {
        failures.add("    " + currentSuite + " / " + name);
        failures.add("        " + detail);
        System.out.println("    FAIL  " + name);
    }

    /** Print the summary and return the process exit code. */
    public static int report() {
        System.out.println();
        if (!failures.isEmpty()) {
            System.out.println("Failures:");
            for (String f : failures) System.out.println(f);
            System.out.println();
        }
        System.out.println(suites + " suites, " + (passed + failed) + " cases, "
                + passed + " passed, " + failed + " failed");
        return failed == 0 ? 0 : 1;
    }

    public static int failedCount() { return failed; }
}
