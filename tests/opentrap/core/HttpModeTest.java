package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Tests the HTTP mode plumbing without a network: the default stays the raw
 * HTTP/1.x path, and selecting an HTTP/2 mode routes through the JDK transport
 * and fails gracefully (no exception) when the target cannot be reached.
 */
public final class HttpModeTest {

    public void run() {
        Harness.suite("http mode");

        Harness.test("the default send mode is raw HTTP/1", () -> {
            Expect.equal(new HttpClient.Options().mode, HttpMode.HTTP_1, "default mode");
        });

        Harness.test("an HTTP/2 send to a closed port returns an error, not an exception", () -> {
            HttpService svc = new HttpService("127.0.0.1", 1, false); // nothing listening
            HttpMessage req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n"));
            HttpClient.Options opt = new HttpClient.Options();
            opt.mode = HttpMode.HTTP_2;
            HttpClient.Result r = new HttpClient().send(svc, req, opt);
            Expect.isFalse(r.ok(), "send to a closed port is not ok");
            Expect.isTrue(r.error != null && !r.error.isEmpty(), "an error message is set");
        });

        Harness.test("mode labels read cleanly for the UI", () -> {
            Expect.equal(HttpMode.HTTP_2.toString(), "HTTP/2", "http2 label");
            Expect.equal(HttpMode.AUTO.toString(), "auto", "auto label");
        });
    }
}
