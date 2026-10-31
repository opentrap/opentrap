package opentrap.proxy;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

/**
 * The proxy must come up on the conventional port the moment the suite starts,
 * so a browser pointed at 127.0.0.1:8080 works without visiting the options tab.
 */
public final class ProxyDefaultsTest {

    public void run() {
        Harness.suite("proxy defaults");

        Harness.test("a new listener is on port 8080 and set to run", () -> {
            ProxyListener l = new ProxyListener();
            Expect.equal(l.port, 8080, "default listener port");
            Expect.isTrue(l.running, "listener enabled by default");
        });

        Harness.test("the startup listener uses 8080 when no port is configured", () -> {
            System.clearProperty("opentrap.port");
            Expect.equal(ProxyServer.defaultPort(), 8080, "startup port");
        });

        Harness.test("the opentrap.port property overrides the default", () -> {
            System.setProperty("opentrap.port", "9090");
            try { Expect.equal(ProxyServer.defaultPort(), 9090, "startup port"); }
            finally { System.clearProperty("opentrap.port"); }
        });

        Harness.test("an invalid opentrap.port falls back to 8080", () -> {
            System.setProperty("opentrap.port", "not-a-port");
            try { Expect.equal(ProxyServer.defaultPort(), 8080, "startup port"); }
            finally { System.clearProperty("opentrap.port"); }
        });

        Harness.test("the status line names the address and port that is listening", () -> {
            ProxyListener l = new ProxyListener();
            Expect.equal(ProxyServer.summary(java.util.List.of(l)),
                    "listening on 127.0.0.1:8080", "status line");
        });

        Harness.test("the status line lists every running listener and skips stopped ones", () -> {
            ProxyListener a = new ProxyListener(8080);
            ProxyListener b = new ProxyListener(8081);
            b.bind = ProxyListener.Bind.ALL;
            ProxyListener c = new ProxyListener(9000);
            c.running = false;
            Expect.equal(ProxyServer.summary(java.util.List.of(a, b, c)),
                    "listening on 127.0.0.1:8080, 0.0.0.0:8081", "status line");
        });

        Harness.test("a listener names the address it is bound to", () -> {
            ProxyListener l = new ProxyListener(8081);
            Expect.equal(l.interfaceLabel(), "127.0.0.1:8081", "loopback is the default");

            l.bind = ProxyListener.Bind.ALL;
            Expect.equal(l.interfaceLabel(), "0.0.0.0:8081", "all interfaces");

            l.bind = ProxyListener.Bind.SPECIFIC;
            l.bindAddress = "192.168.21.227";
            Expect.equal(l.interfaceLabel(), "192.168.21.227:8081", "a specific address");

            // A specific bind with nothing chosen must not produce "null:8081".
            l.bindAddress = "";
            Expect.equal(l.interfaceLabel(), "0.0.0.0:8081", "a specific bind with no address");
        });

        Harness.test("an edit is only kept when the dialog is accepted", () -> {
            // The dialog edits a copy, so a cancel leaves the original alone.
            ProxyListener original = new ProxyListener(8080);
            ProxyListener working = original.copy();
            working.port = 9999;
            working.bind = ProxyListener.Bind.ALL;
            Expect.equal(original.port, 8080, "the original port is untouched");
            Expect.isTrue(original.bind == ProxyListener.Bind.LOOPBACK,
                    "the original bind mode is untouched");
        });

        Harness.test("the machine offers at least loopback to bind to", () -> {
            java.util.List<String> all = LocalAddresses.all();
            Expect.isTrue(all.contains("127.0.0.1"), "loopback is always offered");
            Expect.isTrue(all.indexOf("127.0.0.1") >= 0, "loopback is in the list");
        });

        Harness.test("the status line says plainly when nothing is listening", () -> {
            ProxyListener l = new ProxyListener();
            l.running = false;
            Expect.equal(ProxyServer.summary(java.util.List.of(l)),
                    "no proxy listeners are running - add one on the options tab", "status line");
            Expect.equal(ProxyServer.summary(java.util.List.of()),
                    "no proxy listeners are running - add one on the options tab", "status line");
        });
    }
}
