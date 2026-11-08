package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The disk-backed store (issue #37): recorded bodies move to disk off the
 * recording thread, every history column is still served from the heap, the
 * bytes reload exactly on demand, audit traffic is left in memory, and a save
 * copy carries the full bodies back.
 */
public final class MessageStoreDiskTest {

    private static final HttpService SVC = new HttpService("example.com", 80, false);

    private static LogEntry entry(String tool, String target, String ct, String body) {
        LogEntry e = new LogEntry(tool, SVC,
                new HttpMessage(Bytes.bytes("GET " + target + " HTTP/1.1\r\nHost: example.com\r\n\r\n")));
        e.setResponse(new HttpMessage(Bytes.bytes(
                "HTTP/1.1 200 OK\r\nContent-Type: " + ct + "\r\nSet-Cookie: sid=abc; Path=/\r\n\r\n" + body)));
        return e;
    }

    private static void awaitOffload(LogEntry e) {
        long deadline = System.currentTimeMillis() + 5000;
        while (!e.offloaded() && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static Path tempDir(String tag) throws Exception {
        Path d = Files.createTempDirectory(tag);
        d.toFile().deleteOnExit();
        return d;
    }

    public void run() {
        Harness.suite("disk-backed message store");

        Harness.test("an offloaded entry keeps every column and reloads its bytes exactly", () -> {
            try {
                MessageStore store = new MessageStore();
                store.useDiskStore(tempDir("mstore-cols-"));
                LogEntry e = entry("proxy", "/app/page.html?q=1",
                        "text/html", "<html><head><title>Hi there</title></head>x</html>");
                byte[] reqRaw = e.request().raw().clone();
                byte[] respRaw = e.response().raw().clone();
                store.add(e);
                awaitOffload(e);

                Expect.isTrue(e.offloaded(), "the entry's bodies moved to disk");
                // Every history column, served from the index without a disk load.
                Expect.equal(e.method(), "GET", "method from the index");
                Expect.equal(e.pathAndQuery(), "/app/page.html?q=1", "path and query from the index");
                Expect.equal(e.requestPath(), "/app/page.html", "site-map path from the index");
                Expect.equal(e.requestQuery(), "q=1", "site-map query from the index");
                Expect.isTrue(e.hasParameters(), "a query parameter is still reported");
                Expect.equal(e.fileExtension(), "html", "extension from the index");
                Expect.equal(e.status(), 200, "status from the index");
                Expect.equal(e.mimeType(), "HTML", "MIME from the index");
                Expect.equal(e.title(), "Hi there", "title from the index");
                Expect.equal(e.cookies(), "sid", "cookie name from the index");
                Expect.isTrue(e.hasResponse(), "a response is reported present");
                Expect.equal(e.responseLen(), respRaw.length, "response length from the index");
                Expect.equal(e.url(), "http://example.com/app/page.html?q=1", "URL from the index");

                // The full bytes reload byte-exact on demand (the detail view).
                Expect.isTrue(Arrays.equals(e.request().raw(), reqRaw), "request reloads byte-exact");
                Expect.isTrue(Arrays.equals(e.response().raw(), respRaw), "response reloads byte-exact");
                store.closeStore();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });

        Harness.test("the size breakdown is served from the index, not the bodies", () -> {
            try {
                MessageStore store = new MessageStore();
                store.useDiskStore(tempDir("mstore-size-"));
                LogEntry e = entry("proxy", "/a", "application/json", "{\"k\":\"vvvvvvvvvv\"}");
                int reqHdr = e.request().bodyOffset();
                int respHdr = e.response().bodyOffset();
                int respBody = e.response().length() - respHdr;
                store.add(e);
                awaitOffload(e);
                Expect.equal(e.requestHeaderBytes(), reqHdr, "request header bytes from the index");
                Expect.equal(e.responseHeaderBytes(), respHdr, "response header bytes from the index");
                Expect.equal(e.responseBodyBytes(), respBody, "response body bytes from the index");
                store.closeStore();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });

        Harness.test("audit traffic stays in the heap, never offloaded", () -> {
            try {
                MessageStore store = new MessageStore();
                store.useDiskStore(tempDir("mstore-audit-"));
                LogEntry a = entry(ProjectOptions.AUDIT_TOOL, "/probe", "text/html", "x".repeat(2000));
                store.add(a);
                // Give the writer thread ample time; an audit entry must still not move.
                Thread.sleep(300);
                Expect.isFalse(a.offloaded(), "an audit probe is not written to disk");
                Expect.notNull(a.request(), "its request stays resident");
                Expect.notNull(a.response(), "its response stays resident");
                store.closeStore();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });

        Harness.test("the pre-modification original response offloads and reloads", () -> {
            try {
                MessageStore store = new MessageStore();
                store.useDiskStore(tempDir("mstore-orig-"));
                LogEntry e = entry("proxy", "/x", "text/html", "modified body");
                HttpMessage orig = new HttpMessage(Bytes.bytes(
                        "HTTP/1.1 200 OK\r\nServer: real\r\nSecret: keep\r\n\r\nthe original body"));
                e.setOriginalResponse(orig);
                byte[] origRaw = orig.raw().clone();
                store.add(e);
                awaitOffload(e);
                Expect.isTrue(Arrays.equals(e.originalResponse().raw(), origRaw), "the original reloads byte-exact");
                Expect.isTrue(Arrays.equals(e.responseForScanning().raw(), origRaw), "the scanner audits the original");
                store.closeStore();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });

        Harness.test("a materialised copy carries the bodies back for a save", () -> {
            try {
                MessageStore store = new MessageStore();
                store.useDiskStore(tempDir("mstore-save-"));
                LogEntry e = entry("proxy", "/x", "application/json", "{\"a\":1}");
                byte[] reqRaw = e.request().raw().clone();
                byte[] respRaw = e.response().raw().clone();
                store.add(e);
                awaitOffload(e);
                LogEntry m = e.materialise();
                Expect.isFalse(m.offloaded(), "the save copy is resident, not offloaded");
                Expect.isTrue(Arrays.equals(m.request().raw(), reqRaw), "save copy holds the full request");
                Expect.isTrue(Arrays.equals(m.response().raw(), respRaw), "save copy holds the full response");
                store.closeStore();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }
}
