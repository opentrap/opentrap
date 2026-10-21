package opentrap.discovery;

import opentrap.core.HttpService;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests content discovery: candidate building from words + extensions, the hit
 * rule, and a live forced-browse run against a local server that serves a known
 * set of 200s and 404s.
 */
public final class DiscoveryTest {

    public void run() {
        Harness.suite("content discovery");

        Harness.test("candidates add extensions only to extensionless words and dedupe", () -> {
            List<String> c = Wordlist.candidates(List.of("admin", "robots.txt", "#comment", "/api"), List.of("php", ".bak"));
            Expect.isTrue(c.contains("/admin"), "bare word");
            Expect.isTrue(c.contains("/admin.php"), "word + ext");
            Expect.isTrue(c.contains("/admin.bak"), "leading dot in ext handled");
            Expect.isTrue(c.contains("/robots.txt"), "word with its own extension kept");
            Expect.isFalse(c.contains("/robots.txt.php"), "no extension added to a dotted word");
            Expect.isTrue(c.contains("/api"), "leading slash stripped then re-added");
            Expect.isFalse(c.contains("/#comment"), "comment skipped");
        });

        Harness.test("the hit rule accepts everything except 404 and errors", () -> {
            Expect.isTrue(ContentDiscovery.isHit(200), "200 is a hit");
            Expect.isTrue(ContentDiscovery.isHit(302), "302 is a hit");
            Expect.isTrue(ContentDiscovery.isHit(401), "401 is a hit");
            Expect.isFalse(ContentDiscovery.isHit(404), "404 is not");
            Expect.isFalse(ContentDiscovery.isHit(0), "error is not");
        });

        Harness.test("a live run finds the served paths and skips the 404s", () -> {
            ServerSocket server;
            try { server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress()); }
            catch (Exception e) { throw new RuntimeException(e); }
            Thread t = new Thread(() -> serve(server), "disco-fake");
            t.setDaemon(true);
            t.start();

            ContentDiscovery disco = new ContentDiscovery(null); // null ctx: no store side effects
            ConcurrentHashMap<String, Integer> hits = new ConcurrentHashMap<>();
            CountDownLatch done = new CountDownLatch(1);
            List<String> paths = List.of("/admin", "/robots.txt", "/missing", "/nope", "/login");
            disco.start(new HttpService("127.0.0.1", server.getLocalPort(), false), paths, 3, 0,
                    new ContentDiscovery.Listener() {
                        public void hit(String path, int status, int length) { hits.put(path, status); }
                        public void progress(int d, int total) {}
                        public void finished() { done.countDown(); }
                    });
            try { Expect.isTrue(done.await(10, TimeUnit.SECONDS), "run finished in time"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            try { server.close(); } catch (Exception ignored) {}

            Expect.isTrue(hits.containsKey("/admin"), "/admin found");
            Expect.isTrue(hits.containsKey("/login"), "/login found");
            Expect.isTrue(hits.containsKey("/robots.txt"), "/robots.txt found");
            Expect.isFalse(hits.containsKey("/missing"), "/missing (404) not reported");
            Expect.isFalse(hits.containsKey("/nope"), "/nope (404) not reported");
        });
    }

    // A tiny server: 200 for /admin, /login, /robots.txt; 404 for everything else.
    private static void serve(ServerSocket server) {
        while (!server.isClosed()) {
            try {
                Socket s = server.accept();
                new Thread(() -> handle(s)).start();
            } catch (Exception e) { return; }
        }
    }

    private static void handle(Socket s) {
        try (s) {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            StringBuilder head = new StringBuilder();
            int b, state = 0;
            while (state < 4 && (b = in.read()) >= 0) {
                head.append((char) b);
                if ((state == 0 || state == 2) && b == 13) state++;
                else if ((state == 1 || state == 3) && b == 10) state++;
                else state = 0;
            }
            String first = head.toString().split("\r\n", 2)[0];
            String path = first.split(" ").length >= 2 ? first.split(" ")[1] : "/";
            boolean ok = path.equals("/admin") || path.equals("/login") || path.equals("/robots.txt");
            String body = ok ? "found" : "nope";
            String resp = "HTTP/1.1 " + (ok ? "200 OK" : "404 Not Found")
                    + "\r\nContent-Length: " + body.length() + "\r\nConnection: close\r\n\r\n" + body;
            out.write(resp.getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
        } catch (Exception ignored) {}
    }
}
