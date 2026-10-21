package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests upstream-proxy routing: destination host matching, and that an HTTP
 * proxy rule makes the client open a CONNECT tunnel to the proxy (verified with
 * a local fake proxy, no external network).
 */
public final class UpstreamProxyTest {

    public void run() {
        Harness.suite("upstream proxy");

        Harness.test("destination host matching honours globs and the enabled flag", () -> {
            UpstreamProxy p = new UpstreamProxy();
            p.host = "127.0.0.1"; p.port = 8080; p.hostPattern = "*.example.com";
            Expect.isTrue(p.matches(new HttpService("api.example.com", 443, true)), "subdomain matches");
            Expect.isFalse(p.matches(new HttpService("example.org", 443, true)), "other host does not");
            p.enabled = false;
            Expect.isFalse(p.matches(new HttpService("api.example.com", 443, true)), "disabled never matches");
        });

        Harness.test("an HTTP proxy rule routes the request through a CONNECT tunnel", () -> {
            AtomicReference<String> connectLine = new AtomicReference<>("");
            ServerSocket fakeProxy;
            try { fakeProxy = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()); }
            catch (Exception e) { throw new RuntimeException(e); }

            Thread server = new Thread(() -> {
                try (Socket s = fakeProxy.accept()) {
                    InputStream in = s.getInputStream();
                    OutputStream out = s.getOutputStream();
                    connectLine.set(readHead(in));                 // CONNECT target:80 ...
                    out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    readHead(in);                                  // the tunnelled GET
                    out.write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                } catch (Exception ignored) {}
            }, "fake-proxy");
            server.setDaemon(true);
            server.start();

            UpstreamProxy rule = new UpstreamProxy();
            rule.type = UpstreamProxy.Type.HTTP;
            rule.host = "127.0.0.1";
            rule.port = fakeProxy.getLocalPort();
            HttpClient.setProxyProvider(svc -> rule);
            try {
                HttpService target = new HttpService("target.test", 80, false);
                HttpMessage req = new HttpMessage(Bytes.bytes("GET / HTTP/1.1\r\nHost: target.test\r\n\r\n"));
                HttpClient.Result r = new HttpClient().send(target, req, new HttpClient.Options());
                Expect.isTrue(r.ok(), "response received through the proxy");
                Expect.equal(r.response.bodyText(), "hi", "body came back through the tunnel");
                Expect.isTrue(connectLine.get().startsWith("CONNECT target.test:80"), "proxy saw a CONNECT to the target");
            } finally {
                HttpClient.setProxyProvider(null);
                try { fakeProxy.close(); } catch (Exception ignored) {}
            }
        });
    }

    private static String readHead(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b, state = 0;
        while (state < 4 && (b = in.read()) >= 0) {
            sb.append((char) b);
            if ((state == 0 || state == 2) && b == 13) state++;
            else if ((state == 1 || state == 3) && b == 10) state++;
            else state = 0;
        }
        return sb.toString();
    }
}
