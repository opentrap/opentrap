package opentrap.proxy;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;

/**
 * Holds the certificate authority to being per-installation.
 *
 * <p>An intercepting proxy that shipped one authority in its build would be
 * handing every user the same private key: anyone with a copy of the product
 * could then sign a certificate any other user's browser already trusts, and
 * read their traffic. Nothing is shipped, and this is the case that says so:
 * a fresh directory must produce a fresh key, and an existing one must be
 * reused rather than silently replaced.
 */
public final class CertificateAuthorityIsolationTest {

    private interface Body { void run() throws Exception; }

    private static void check(String name, Body body) {
        Harness.test(name, () -> {
            try { body.run(); }
            catch (AssertionError e) { throw e; }
            catch (Exception e) { throw new AssertionError(e.toString(), e); }
        });
    }

    private static String keyFingerprint(CertificateAuthority ca) throws Exception {
        byte[] spki = ca.caCertificate().getPublicKey().getEncoded();
        byte[] d = MessageDigest.getInstance("SHA-256").digest(spki);
        StringBuilder s = new StringBuilder();
        for (byte b : d) s.append(String.format("%02x", b));
        return s.toString();
    }

    private static void delete(Path dir) {
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }

    public void run() {
        Harness.suite("certificate authority is per installation");

        check("two installations never share a private key", () -> {
            Path a = Files.createTempDirectory("ca-a");
            Path b = Files.createTempDirectory("ca-b");
            try {
                String fa = keyFingerprint(new CertificateAuthority(a));
                String fb = keyFingerprint(new CertificateAuthority(b));
                Expect.isFalse(fa.equals(fb),
                        "two fresh installations were given the same CA key");
            } finally { delete(a); delete(b); }
        });

        check("an existing authority is reused, not replaced", () -> {
            Path dir = Files.createTempDirectory("ca-reuse");
            try {
                String first = keyFingerprint(new CertificateAuthority(dir));
                String second = keyFingerprint(new CertificateAuthority(dir));
                Expect.equal(second, first,
                        "reopening replaced the authority, breaking every client that trusted it");
            } finally { delete(dir); }
        });

        check("the key is readable only by its owner", () -> {
            Path dir = Files.createTempDirectory("ca-perms");
            try {
                CertificateAuthority ca = new CertificateAuthority(dir);
                var perms = Files.getPosixFilePermissions(ca.storePath());
                Expect.isFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ),
                        "the CA key is readable by the group");
                Expect.isFalse(perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ),
                        "the CA key is readable by everyone");
            } finally { delete(dir); }
        });
    }
}
