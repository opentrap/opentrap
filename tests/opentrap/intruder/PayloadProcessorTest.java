package opentrap.intruder;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Tests the pure payload-processing helpers added for Intruder depth: the
 * skip-if-matches rule and the decode transforms. The case transform is covered
 * too since the generator relies on it.
 */
public final class PayloadProcessorTest {

    public void run() {
        Harness.suite("payload processing");

        Harness.test("skip-if-matches flags only payloads that match the regex", () -> {
            Expect.isTrue(PayloadProcessor.skips("<script>", "[<>]"), "angle brackets matched");
            Expect.isFalse(PayloadProcessor.skips("plain", "[<>]"), "plain payload not skipped");
            Expect.isFalse(PayloadProcessor.skips("anything", ""), "empty rule never skips");
            Expect.isFalse(PayloadProcessor.skips("anything", "("), "a bad regex never skips");
        });

        Harness.test("URL-decode and base64-decode reverse their encodings", () -> {
            Expect.equal(PayloadProcessor.urlDecode("a%20b%2Fc"), "a b/c", "url decode");
            Expect.equal(PayloadProcessor.b64Decode("aGVsbG8="), "hello", "base64 decode");
            Expect.equal(PayloadProcessor.b64Decode("not base64!!"), "not base64!!", "bad base64 passes through");
        });

        Harness.test("case transforms behave as named", () -> {
            Expect.equal(PayloadProcessor.applyCase("AbC", 1), "abc", "to lower");
            Expect.equal(PayloadProcessor.applyCase("AbC", 2), "ABC", "to upper");
            Expect.equal(PayloadProcessor.applyCase("hello world", 4), "Hello World", "proper case");
        });
    }
}
