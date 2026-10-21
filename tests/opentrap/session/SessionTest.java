package opentrap.session;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * Tests the session-handling engine headless: the cookie jar's parsing and
 * matching, value extraction, rule scoping and triggers, and the manager's
 * learn-cookies / re-authenticate-and-retry decision.
 */
public final class SessionTest {

    private static HttpMessage resp(String raw) { return new HttpMessage(Bytes.bytes(raw.replace("\n", "\r\n"))); }
    private static HttpMessage get(String path, String host) {
        return new HttpMessage(Bytes.bytes("GET " + path + " HTTP/1.1\r\nHost: " + host + "\r\n\r\n"));
    }

    public void run() {
        Harness.suite("session handling");

        Harness.test("Set-Cookie headers are learned and offered back on matching requests", () -> {
            CookieJar jar = new CookieJar();
            HttpService svc = new HttpService("example.com", 443, true);
            jar.updateFrom(svc, resp("HTTP/1.1 200 OK\nSet-Cookie: sid=abc; Path=/\n"
                    + "Set-Cookie: theme=dark; Domain=example.com\nContent-Length: 0\n\n"));
            Expect.equal(jar.size(), 2, "cookies learned");
            String h = jar.headerFor(svc, get("/", "example.com"), null);
            Expect.isTrue(h.contains("sid=abc"), "sid offered");
            Expect.isTrue(h.contains("theme=dark"), "theme offered");
        });

        Harness.test("secure cookies are withheld from plain http", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("sid", "abc", "example.com", "/", true, 0));
            Expect.isNull(jar.headerFor(new HttpService("example.com", 80, false), get("/", "example.com"), null),
                    "no cookie over http");
            Expect.notNull(jar.headerFor(new HttpService("example.com", 443, true), get("/", "example.com"), null),
                    "cookie over https");
        });

        Harness.test("path-scoped cookies only match under their path", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("adm", "1", "example.com", "/admin", false, 0));
            HttpService svc = new HttpService("example.com", 80, false);
            Expect.isNull(jar.headerFor(svc, get("/public", "example.com"), null), "not on /public");
            Expect.notNull(jar.headerFor(svc, get("/admin/panel", "example.com"), null), "on /admin/panel");
        });

        Harness.test("the jar wins over a cookie already on the request", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("sid", "fresh", "example.com", "/", false, 0));
            String h = jar.headerFor(new HttpService("example.com", 80, false), get("/", "example.com"), "sid=stale; other=keep");
            Expect.isTrue(h.contains("sid=fresh"), "jar value used");
            Expect.isTrue(h.contains("other=keep"), "unrelated cookie preserved");
        });

        Harness.test("extraction reads regex, header and cookie sources", () -> {
            CookieJar jar = new CookieJar();
            jar.set(new Cookie("auth", "tok123", "example.com", "/", false, 0));
            HttpMessage r = resp("HTTP/1.1 200 OK\nX-CSRF: hdrtok\nContent-Length: 34\n\n<input name=csrf value=\"body-tok\">");
            Expect.equal(new Extraction("c", Extraction.From.REGEX, "value=\"([^\"]+)\"").extract(r, jar), "body-tok", "regex group");
            Expect.equal(new Extraction("h", Extraction.From.HEADER, "X-CSRF").extract(r, jar), "hdrtok", "header");
            Expect.equal(new Extraction("k", Extraction.From.COOKIE, "auth").extract(r, jar), "tok123", "cookie");
        });

        Harness.test("rule scoping matches host globs and tools", () -> {
            SessionRule r = new SessionRule();
            r.hostPattern = "*.example.com";
            r.tools.add("repeater");
            Expect.isTrue(r.appliesTo("repeater", new HttpService("app.example.com", 443, true)), "subdomain + tool");
            Expect.isFalse(r.appliesTo("intruder", new HttpService("app.example.com", 443, true)), "wrong tool");
            Expect.isFalse(r.appliesTo("repeater", new HttpService("other.org", 443, true)), "wrong host");
        });

        Harness.test("status and content triggers fire on the right responses", () -> {
            SessionRule r = new SessionRule();
            r.macroName = "login";
            r.trigger = SessionRule.Trigger.STATUS;
            r.triggerValue = "401,403";
            Expect.isTrue(r.triggered(resp("HTTP/1.1 401 Unauthorized\nContent-Length: 0\n\n")), "401 fires");
            Expect.isFalse(r.triggered(resp("HTTP/1.1 200 OK\nContent-Length: 0\n\n")), "200 does not");
            r.trigger = SessionRule.Trigger.CONTAINS;
            r.triggerValue = "Please log in";
            Expect.isTrue(r.triggered(resp("HTTP/1.1 200 OK\nContent-Length: 11\n\nPlease log in")), "body text fires");
        });

        Harness.test("the manager learns cookies and asks for a retry only when a rule triggers", () -> {
            SessionManager sm = new SessionManager(null);
            SessionRule r = new SessionRule();
            r.name = "reauth";
            r.hostPattern = "example.com";
            r.macroName = "login";
            r.trigger = SessionRule.Trigger.STATUS;
            r.triggerValue = "401";
            sm.rules().add(r);
            sm.macros().add(new Macro("login")); // empty: re-run does no network in the test

            HttpService svc = new HttpService("example.com", 443, true);
            HttpMessage req = get("/", "example.com");
            boolean retry = sm.afterResponse("repeater", svc, req,
                    resp("HTTP/1.1 401 Unauthorized\nSet-Cookie: sid=learned; Path=/\nContent-Length: 0\n\n"));
            Expect.isTrue(retry, "401 asks for a retry");
            Expect.isTrue(sm.jar().size() >= 1, "cookie learned from the response");

            boolean noRetry = sm.afterResponse("repeater", svc, req, resp("HTTP/1.1 200 OK\nContent-Length: 0\n\n"));
            Expect.isFalse(noRetry, "200 does not retry");
        });

        Harness.test("beforeSend applies jar cookies when a rule governs the request", () -> {
            SessionManager sm = new SessionManager(null);
            SessionRule r = new SessionRule();
            r.hostPattern = "example.com";
            r.useCookieJar = true;
            sm.rules().add(r);
            sm.jar().set(new Cookie("sid", "abc", "example.com", "/", false, 0));

            HttpMessage req = get("/", "example.com");
            sm.beforeSend("repeater", new HttpService("example.com", 80, false), req);
            Expect.isTrue(req.header("Cookie") != null && req.header("Cookie").contains("sid=abc"), "cookie applied");

            HttpMessage untouched = get("/", "other.org");
            sm.beforeSend("repeater", new HttpService("other.org", 80, false), untouched);
            Expect.isNull(untouched.header("Cookie"), "no cookie for an unmatched host");
        });
    }
}
