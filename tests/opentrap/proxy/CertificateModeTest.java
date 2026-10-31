package opentrap.proxy;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Pins what each certificate mode on a listener actually presents.
 *
 * <p>The modes were recorded and shown in the listeners table for a while
 * before anything acted on them: every listener served a CA-signed per-host
 * certificate whatever its setting said. These cases hold each mode to what
 * the dialog promises.
 */
public final class CertificateModeTest {

    /** Run a body that may throw, failing the case rather than the suite. */
    private interface Body { void run() throws Exception; }

    private static void check(String name, Body body) {
        Harness.test(name, () -> {
            try { body.run(); }
            catch (AssertionError e) { throw e; }
            catch (Exception e) { throw new AssertionError(e.toString(), e); }
        });
    }

    /** A throwaway CA in its own directory, removed afterwards. */
    private static void withCa(CaBody body) throws Exception {
        Path dir = Files.createTempDirectory("opentrap-ca-test");
        try {
            body.run(new CertificateAuthority(dir));
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    private interface CaBody { void run(CertificateAuthority ca) throws Exception; }

    public void run() {
        Harness.suite("certificate modes");

        check("the cache is keyed by what will be presented, not the host alone", () -> {
            ProxyListener perHost = new ProxyListener(8080);
            perHost.cert = ProxyListener.Cert.PER_HOST;
            ProxyListener selfSigned = new ProxyListener(8080);
            selfSigned.cert = ProxyListener.Cert.SELF_SIGNED;

            Expect.isFalse(CertificateAuthority.cacheKey("example.com", perHost)
                           .equals(CertificateAuthority.cacheKey("example.com", selfSigned)),
                    "two modes on the same host share a cache entry");

            ProxyListener named = new ProxyListener(8080);
            named.cert = ProxyListener.Cert.SPECIFIC_HOSTNAME;
            named.certHostname = "fixed.example.com";
            Expect.equal(CertificateAuthority.cacheKey("a.example.com", named),
                         CertificateAuthority.cacheKey("b.example.com", named),
                    "a fixed hostname presents one certificate whatever host is asked for");

            Expect.equal(CertificateAuthority.cacheKey("example.com", null),
                         CertificateAuthority.cacheKey("example.com", perHost),
                    "no listener means per-host, as before");
        });

        check("a per-host listener presents a certificate the CA signed", () -> withCa(ca -> {
            ProxyListener l = new ProxyListener(8080);
            l.cert = ProxyListener.Cert.PER_HOST;
            Expect.notNull(ca.contextFor("example.com", l), "a context is built");
            Expect.notNull(ca.caCertificate(), "there is a CA to sign with");
        }));

        check("a self-signed listener still serves, without the CA", () -> withCa(ca -> {
            ProxyListener l = new ProxyListener(8080);
            l.cert = ProxyListener.Cert.SELF_SIGNED;
            Expect.notNull(ca.contextFor("example.com", l), "a context is built");
        }));

        check("a named listener serves the same certificate for every host", () -> withCa(ca -> {
            ProxyListener l = new ProxyListener(8080);
            l.cert = ProxyListener.Cert.SPECIFIC_HOSTNAME;
            l.certHostname = "fixed.example.com";
            Expect.isTrue(ca.contextFor("a.example.com", l) == ca.contextFor("b.example.com", l),
                    "two hosts are served the same context");
        }));

        check("a custom store that is not set is reported, not ignored", () -> withCa(ca -> {
            ProxyListener l = new ProxyListener(8080);
            l.cert = ProxyListener.Cert.CUSTOM;
            l.certFile = "";
            boolean threw = false;
            try { ca.contextFor("example.com", l); }
            catch (Exception expected) { threw = true; }
            Expect.isTrue(threw, "an unset custom certificate is reported");
        }));

        check("only protocols the runtime knows are asked for", () -> {
            // A name this Java has never heard of must not be requested, or
            // every connection through the listener would fail.
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getDefault();
            try (javax.net.ssl.SSLSocket s =
                         (javax.net.ssl.SSLSocket) sc.getSocketFactory().createSocket()) {
                ProxyListener l = new ProxyListener(8080);
                l.tlsProtocols = "TLSv1.3, NOT-A-PROTOCOL";
                ProxyServer.applyProtocols(s, l);
                java.util.List<String> on = java.util.Arrays.asList(s.getEnabledProtocols());
                Expect.isFalse(on.contains("NOT-A-PROTOCOL"), "an unknown name is requested");
                Expect.isTrue(on.contains("TLSv1.3"), "the known name is requested");

                // Naming nothing usable leaves the runtime's defaults alone.
                ProxyListener junk = new ProxyListener(8080);
                junk.tlsProtocols = "NONSENSE";
                String[] before = s.getEnabledProtocols();
                ProxyServer.applyProtocols(s, junk);
                Expect.equal(String.join(",", s.getEnabledProtocols()), String.join(",", before),
                        "a configuration naming nothing usable changes nothing");
            }
        });
    }
}
