package opentrap.csrf;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Tests the CSRF proof-of-concept generator: form output for urlencoded and GET
 * requests, fetch output for JSON bodies, and HTML escaping of values.
 */
public final class CsrfPocTest {

    private static HttpMessage req(String raw) { return new HttpMessage(Bytes.bytes(raw.replace("\n", "\r\n"))); }

    public void run() {
        Harness.suite("csrf poc");

        Harness.test("a urlencoded POST becomes an auto-submitting form", () -> {
            HttpService svc = new HttpService("bank.example.com", 443, true);
            String html = CsrfPoc.generate(svc, req(
                    "POST /transfer HTTP/1.1\nHost: bank.example.com\n"
                            + "Content-Type: application/x-www-form-urlencoded\n\namount=100&to=mallory"));
            Expect.isTrue(html.contains("method=\"POST\""), "POST form");
            Expect.isTrue(html.contains("action=\"https://bank.example.com/transfer\""), "action url");
            Expect.isTrue(html.contains("name=\"amount\" value=\"100\""), "amount input");
            Expect.isTrue(html.contains("name=\"to\" value=\"mallory\""), "to input");
            Expect.isTrue(html.contains("document.forms[0].submit()"), "auto-submit");
        });

        Harness.test("a GET request builds a GET form from the query", () -> {
            String html = CsrfPoc.generate(new HttpService("x.example", 80, false),
                    req("GET /a?p=1&q=two HTTP/1.1\nHost: x.example\n\n"));
            Expect.isTrue(html.contains("method=\"GET\""), "GET form");
            Expect.isTrue(html.contains("name=\"p\" value=\"1\""), "p input");
            Expect.isTrue(html.contains("name=\"q\" value=\"two\""), "q input");
        });

        Harness.test("a JSON body becomes a fetch PoC with the raw body", () -> {
            String html = CsrfPoc.generate(new HttpService("api.example", 443, true), req(
                    "POST /x HTTP/1.1\nHost: api.example\nContent-Type: application/json\n\n{\"a\":1}"));
            Expect.isTrue(html.contains("fetch("), "uses fetch");
            Expect.isTrue(html.contains("application/json"), "content type set");
            Expect.isTrue(html.contains("{\\\"a\\\":1}") || html.contains("{\"a\":1}"), "body included");
        });

        Harness.test("values are HTML-escaped so the PoC cannot be broken out of", () -> {
            String html = CsrfPoc.generate(new HttpService("x.example", 80, false),
                    req("POST /a HTTP/1.1\nHost: x.example\nContent-Type: application/x-www-form-urlencoded\n\nx=%22%3E%3Cb%3E"));
            Expect.isFalse(html.contains("\"><b>"), "raw break-out not present");
            Expect.isTrue(html.contains("&quot;&gt;&lt;b&gt;"), "escaped value");
        });
    }
}
