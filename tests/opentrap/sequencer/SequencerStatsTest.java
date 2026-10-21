package opentrap.sequencer;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests the statistical tests added to the Sequencer: chi-square with a p-value,
 * lag-1 serial correlation, the compression ratio, and the erfc used for the
 * p-value.
 */
public final class SequencerStatsTest {

    private static boolean[] alternating(int n) {
        boolean[] b = new boolean[n];
        for (int i = 0; i < n; i++) b[i] = (i % 2 == 0);
        return b;
    }

    public void run() {
        Harness.suite("sequencer stats");

        Harness.test("erfc behaves at the ends and the middle", () -> {
            Expect.isTrue(Math.abs(SequencerTool.erfc(0) - 1.0) < 0.01, "erfc(0) ~ 1");
            Expect.isTrue(SequencerTool.erfc(3) < 0.001, "erfc(3) ~ 0");
            Expect.isTrue(SequencerTool.erfc(-3) > 1.99, "erfc(-3) ~ 2");
        });

        Harness.test("chi-square fails an all-zero stream and passes a balanced one", () -> {
            boolean[] zeros = new boolean[100];
            Expect.isTrue(SequencerTool.chiSquare(zeros).contains("FAIL"), "all zeros fail");
            Expect.isTrue(SequencerTool.chiSquare(alternating(100)).contains("PASS"), "balanced passes");
        });

        Harness.test("serial correlation flags a perfectly alternating stream", () -> {
            Expect.isTrue(SequencerTool.serialCorrelation(alternating(100)).contains("review"),
                    "alternating bits are correlated");
        });

        Harness.test("compression ratio is low for repetitive tokens", () -> {
            List<String> tokens = new ArrayList<>();
            for (int i = 0; i < 300; i++) tokens.add("AAAAAAAA");
            String out = SequencerTool.compressionTest(tokens);
            Expect.isTrue(out.contains("Compression"), "has a heading");
            Expect.isTrue(out.contains("review"), "repetitive data compresses well (low entropy)");
        });
    }
}
