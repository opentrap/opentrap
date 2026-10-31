package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;
import opentrap.testkit.SmallStack;

import java.util.List;

/**
 * Tests structured-body parameter parsing (JSON, XML, multipart) in
 * {@link HttpMessage}. The key invariant is that every parsed parameter's
 * reported value offsets slice the exact same bytes back out of the raw
 * message, because Intruder auto-marking and the inspector both rely on it.
 */
public final class ParameterParsingTest {

    private static HttpMessage req(String headers, String body) {
        String raw = headers.replace("\n", "\r\n") + "\r\n\r\n" + body;
        return new HttpMessage(Bytes.bytes(raw));
    }

    /** The parameter named {@code name} whose value equals {@code value}, or null. */
    private static Param find(List<Param> ps, Param.Type type, String name, String value) {
        for (Param p : ps) {
            if (p.type() == type && p.name().equals(name) && p.value().equals(value)) return p;
        }
        return null;
    }

    /** Assert the parameter exists and its value offsets slice back the value. */
    private static void present(HttpMessage m, Param.Type type, String name, String value) {
        Param p = find(m.parameters(), type, name, value);
        Expect.notNull(p, type + " parameter " + name + "=" + value);
        String sliced = Bytes.str(Bytes.slice(m.raw(), p.valueStart(), p.valueEnd()));
        Expect.equal(sliced, value, "value offsets of " + name + " slice the raw bytes");
    }

    public void run() {
        Harness.suite("parameter parsing");

        Harness.test("JSON objects, nesting and scalar types are parsed with exact offsets", () -> {
            HttpMessage m = req(
                    "POST /api HTTP/1.1\nHost: t.example\nContent-Type: application/json",
                    "{\"user\":\"admin\",\"age\":30,\"active\":true,\"nested\":{\"token\":\"abc\"}}");
            present(m, Param.Type.JSON, "user", "admin");
            present(m, Param.Type.JSON, "age", "30");
            present(m, Param.Type.JSON, "active", "true");
            present(m, Param.Type.JSON, "token", "abc"); // nested object key
        });

        Harness.test("JSON array elements share the array's key", () -> {
            HttpMessage m = req(
                    "POST /api HTTP/1.1\nHost: t.example\nContent-Type: application/json",
                    "{\"ids\":[\"one\",\"two\"]}");
            present(m, Param.Type.JSON, "ids", "one");
            present(m, Param.Type.JSON, "ids", "two");
        });

        Harness.test("hostile nesting depth cannot overflow the parse", () -> {
            // The recursive JSON walk used to recurse once per nesting level
            // with no bound, so a proxied body of nested brackets could
            // overflow the stack the inspector parses on. The walk is now
            // depth-bounded; a small stack proves no overflow escapes.
            String deep = "[[".repeat(50_000) + "1" + "]]".repeat(50_000);
            HttpMessage m = req(
                    "POST /api HTTP/1.1\nHost: t.example\nContent-Type: application/json",
                    deep);
            Throwable escaped = SmallStack.probe(m::parameters);
            Expect.isTrue(escaped == null, "nothing escaped the bounded walk: " + escaped);
            // The bound also truncates gracefully: shallow content before the
            // cutoff is still reported.
            HttpMessage shallow = req(
                    "POST /api HTTP/1.1\nHost: t.example\nContent-Type: application/json",
                    "{\"user\":\"admin\",\"deep\":" + "[[".repeat(50_000) + "1" + "]]".repeat(50_000) + "}");
            present(shallow, Param.Type.JSON, "user", "admin");
        });

        Harness.test("JSON is detected by shape when the content type is absent", () -> {
            HttpMessage m = req(
                    "POST /api HTTP/1.1\nHost: t.example",
                    "{\"k\":\"v\"}");
            present(m, Param.Type.JSON, "k", "v");
        });

        Harness.test("XML element text and attributes are parsed with exact offsets", () -> {
            HttpMessage m = req(
                    "POST /api HTTP/1.1\nHost: t.example\nContent-Type: text/xml",
                    "<root><id>42</id><user name=\"bob\">hello</user></root>");
            present(m, Param.Type.XML, "id", "42");
            present(m, Param.Type.XML, "user", "hello");
            present(m, Param.Type.XML, "name", "bob"); // attribute
        });

        Harness.test("XML comments and declarations are skipped", () -> {
            HttpMessage m = req(
                    "POST /api HTTP/1.1\nHost: t.example\nContent-Type: application/xml",
                    "<?xml version=\"1.0\"?><!-- x=\"y\" --><a>v</a>");
            Expect.notNull(find(m.parameters(), Param.Type.XML, "a", "v"), "element a");
            Expect.isNull(find(m.parameters(), Param.Type.XML, "version", "1.0"), "PI pseudo-attr not parsed");
        });

        Harness.test("multipart form fields are parsed with exact offsets", () -> {
            String body = "--B\r\nContent-Disposition: form-data; name=\"username\"\r\n\r\nalice\r\n"
                    + "--B\r\nContent-Disposition: form-data; name=\"role\"\r\n\r\nadmin\r\n--B--\r\n";
            HttpMessage m = req(
                    "POST /api HTTP/1.1\nHost: t.example\nContent-Type: multipart/form-data; boundary=B",
                    body);
            present(m, Param.Type.MULTIPART, "username", "alice");
            present(m, Param.Type.MULTIPART, "role", "admin");
        });

        Harness.test("existing URL, cookie and form parsing is unchanged", () -> {
            HttpMessage m = req(
                    "POST /s?q=hi HTTP/1.1\nHost: t.example\nCookie: sid=xyz\nContent-Type: application/x-www-form-urlencoded",
                    "a=1&b=2");
            present(m, Param.Type.URL, "q", "hi");
            present(m, Param.Type.COOKIE, "sid", "xyz");
            present(m, Param.Type.BODY, "a", "1");
            present(m, Param.Type.BODY, "b", "2");
        });

        Harness.test("a malformed JSON body does not hang or throw", () -> {
            HttpMessage m = req(
                    "POST /api HTTP/1.1\nHost: t.example\nContent-Type: application/json",
                    "{\"user\":\"admin\",\"broken\":");
            present(m, Param.Type.JSON, "user", "admin"); // what parsed before the break still shows
        });
    }
}
