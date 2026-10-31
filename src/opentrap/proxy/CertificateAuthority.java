package opentrap.proxy;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import java.io.*;
import java.math.BigInteger;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates a long-lived CA on first run and mints short leaf certificates
 * per host on demand.
 *
 * The 2008 original shipped one fixed certificate in a bundled keystore,
 * which no modern browser will accept. This is the replacement.
 */
public class CertificateAuthority {

    private static final String KEY_ALG = "RSA";
    private static final int CA_KEY_SIZE = 2048;
    private static final int LEAF_KEY_SIZE = 2048;
    private static final String SIG_ALG = "SHA256WithRSA";
    private static final char[] PASSWORD = "opentrap".toCharArray();

    private final Path storePath;
    private X509Certificate caCert;
    private PrivateKey caKey;
    private KeyPair leafKeyPair;

    /**
     * Leaf contexts by host, kept bounded: a long session through many hosts
     * (every third-party domain on every page) would otherwise mint and retain
     * one RSA-backed context per hostname for the whole session. Order is
     * insertion order, for eviction; the hot path is the lock-free
     * {@code cache.get} below, and an evicted host is simply re-minted on its
     * next connection.
     */
    private static final int MAX_CACHED_CONTEXTS = 512;
    private final Map<String, SSLContext> cache = new ConcurrentHashMap<>();
    /** Hosts in insertion order; guarded by {@code this} alongside minting. */
    private final java.util.ArrayDeque<String> cacheOrder = new java.util.ArrayDeque<>();

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    public CertificateAuthority(Path dir) throws Exception {
        Files.createDirectories(dir);
        this.storePath = dir.resolve("ca.p12");
        if (Files.exists(storePath)) loadCa(); else generateCa();
        // One leaf key pair is reused across hosts. Generating RSA keys is the
        // expensive part; reusing the key while varying the certificate keeps
        // first contact with a new host fast.
        KeyPairGenerator g = KeyPairGenerator.getInstance(KEY_ALG);
        g.initialize(LEAF_KEY_SIZE);
        this.leafKeyPair = g.generateKeyPair();
    }

    public X509Certificate caCertificate() { return caCert; }

    /** Where this installation's CA lives on disk, so it can be replaced. */
    public Path storePath() { return storePath; }

    /** DER bytes of the CA certificate, for the browser-facing download. */
    public byte[] caCertificateDer() throws Exception { return caCert.getEncoded(); }

    /**
     * Base64 SHA-256 of the CA's SubjectPublicKeyInfo. This is the value for
     * Chromium's --ignore-certificate-errors-spki-list flag, which makes the
     * browser trust leaf certificates under this one CA and no other.
     */
    public String spkiFingerprint() throws Exception {
        byte[] spki = caCert.getPublicKey().getEncoded();
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(spki);
        return java.util.Base64.getEncoder().encodeToString(hash);
    }

    public String caCertificatePem() throws Exception {
        String b64 = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'})
                .encodeToString(caCert.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
    }

    private void loadCa() throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(storePath)) {
            ks.load(in, PASSWORD);
        }
        caCert = (X509Certificate) ks.getCertificate("ca");
        caKey = (PrivateKey) ks.getKey("ca", PASSWORD);
        if (caCert == null || caKey == null) generateCa();
    }

    private void generateCa() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance(KEY_ALG);
        g.initialize(CA_KEY_SIZE);
        KeyPair kp = g.generateKeyPair();

        X500Name name = new X500Name("CN=OpenTRAP Proxy CA, O=OpenTRAP, OU=Certificate Authority");
        long now = System.currentTimeMillis();
        Date from = new Date(now - 24L * 3600 * 1000);
        Date to = new Date(now + 3650L * 24 * 3600 * 1000);

        JcaX509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                name, BigInteger.valueOf(now), from, to, name, kp.getPublic());
        b.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        b.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign | KeyUsage.digitalSignature));
        JcaX509ExtensionUtils u = new JcaX509ExtensionUtils();
        b.addExtension(Extension.subjectKeyIdentifier, false,
                u.createSubjectKeyIdentifier(kp.getPublic()));

        ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG).build(kp.getPrivate());
        caCert = new JcaX509CertificateConverter().getCertificate(b.build(signer));
        caKey = kp.getPrivate();

        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, PASSWORD);
        ks.setKeyEntry("ca", caKey, PASSWORD, new Certificate[]{caCert});
        try (OutputStream out = Files.newOutputStream(storePath,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ks.store(out, PASSWORD);
        }
        try { Files.setPosixFilePermissions(storePath, PosixFilePermissions.fromString("rw-------")); }
        catch (Exception ignored) {}
    }

    /** A TLS context presenting a CA-signed certificate valid for this host. */
    public SSLContext contextFor(String host) throws Exception {
        return contextFor(host, null);
    }

    /**
     * A TLS context for this host, built the way the listener asks for.
     *
     * <p>Per-host is the default and signs a fresh leaf with this
     * installation's CA. A specific hostname signs one leaf and presents it
     * whatever host is asked for, which is what a client that pins a name
     * wants. Self-signed skips the CA altogether, so a client sees an
     * untrusted certificate unless it was told to accept that one. A custom
     * store presents a PKCS#12 the tester supplies, untouched.
     */
    public SSLContext contextFor(String host, ProxyListener cfg) throws Exception {
        String key = cacheKey(host, cfg);
        SSLContext f = cache.get(key);
        if (f != null) return f;
        synchronized (this) {
            f = cache.get(key);
            if (f != null) return f;
            f = build(host, cfg);
            cache.put(key, f);
            // Bounded, oldest first: keep the cache from growing one context
            // per hostname ever visited.
            cacheOrder.addLast(key);
            while (cacheOrder.size() > MAX_CACHED_CONTEXTS) {
                String evict = cacheOrder.pollFirst();
                if (evict != null) cache.remove(evict);
            }
            return f;
        }
    }

    /**
     * The cache is keyed by the certificate the listener will present, not by
     * host alone: changing the mode on a listener must not keep serving the
     * context minted under the previous one.
     */
    static String cacheKey(String host, ProxyListener cfg) {
        if (cfg == null) return "per-host\u0000" + host;
        return switch (cfg.cert) {
            case PER_HOST -> "per-host\u0000" + host;
            case SELF_SIGNED -> "self\u0000" + host;
            case SPECIFIC_HOSTNAME -> "named\u0000" + cfg.certHostname;
            case CUSTOM -> "custom\u0000" + cfg.certFile;
        };
    }

    private SSLContext build(String host, ProxyListener cfg) throws Exception {
        ProxyListener.Cert mode = cfg == null ? ProxyListener.Cert.PER_HOST : cfg.cert;
        if (mode == ProxyListener.Cert.CUSTOM) return customContext(cfg);

        String subject = mode == ProxyListener.Cert.SPECIFIC_HOSTNAME
                && cfg.certHostname != null && !cfg.certHostname.isBlank()
                ? cfg.certHostname.trim() : host;
        boolean selfSigned = mode == ProxyListener.Cert.SELF_SIGNED;

        X509Certificate leaf = selfSigned ? mintSelfSigned(subject) : mint(subject);
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, PASSWORD);
        // A self-signed leaf stands alone; a CA-signed one carries the CA so
        // the client can build the chain.
        ks.setKeyEntry("leaf", leafKeyPair.getPrivate(), PASSWORD,
                selfSigned ? new Certificate[]{leaf} : new Certificate[]{leaf, caCert});
        return contextOf(ks, PASSWORD);
    }

    /** A PKCS#12 the tester supplied, presented as it is. */
    private SSLContext customContext(ProxyListener cfg) throws Exception {
        if (cfg.certFile == null || cfg.certFile.isBlank()) {
            throw new IOException("No certificate file is set for this listener.");
        }
        char[] pw = (cfg.certPassword == null ? "" : cfg.certPassword).toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(Path.of(cfg.certFile))) {
            ks.load(in, pw);
        }
        return contextOf(ks, pw);
    }

    private static SSLContext contextOf(KeyStore ks, char[] password) throws Exception {
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(
                KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext sc = SSLContext.getInstance("TLS");
        sc.init(kmf.getKeyManagers(), null, new SecureRandom());
        return sc;
    }

    /**
     * A leaf that signs itself. No CA is involved, so a client that trusts
     * this installation's CA still will not trust this: that is the point of
     * the choice, and it is how a server with its own certificate behaves.
     */
    private X509Certificate mintSelfSigned(String host) throws Exception {
        long now = System.currentTimeMillis();
        Date from = new Date(now - 24L * 3600 * 1000);
        Date to = new Date(now + 825L * 24 * 3600 * 1000);
        X500Name subject = new X500Name("CN=" + host.replace(",", "") + ", O=OpenTRAP");

        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(now + host.hashCode()), from, to,
                subject, leafKeyPair.getPublic());
        b.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        b.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
        GeneralName gn = isIpAddress(host)
                ? new GeneralName(GeneralName.iPAddress, host)
                : new GeneralName(GeneralName.dNSName, host);
        b.addExtension(Extension.subjectAlternativeName, false, new GeneralNames(gn));

        ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG).build(leafKeyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(b.build(signer));
    }

    private X509Certificate mint(String host) throws Exception {
        long now = System.currentTimeMillis();
        Date from = new Date(now - 24L * 3600 * 1000);
        Date to = new Date(now + 825L * 24 * 3600 * 1000);

        X500Name subject = new X500Name("CN=" + host.replace(",", "") + ", O=OpenTRAP");
        // Use the CA's exact subject encoding as the issuer, so the chain links
        // byte-for-byte and the keystore accepts {leaf, ca}.
        X500Name issuer = new JcaX509CertificateHolder(caCert).getSubject();
        X509v3CertificateBuilder b = new JcaX509v3CertificateBuilder(
                issuer,
                BigInteger.valueOf(now + host.hashCode()),
                from, to, subject, leafKeyPair.getPublic());

        b.addExtension(Extension.basicConstraints, false, new BasicConstraints(false));
        b.addExtension(Extension.keyUsage, true,
                new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
        b.addExtension(Extension.extendedKeyUsage, false,
                new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

        GeneralName gn = isIpAddress(host)
                ? new GeneralName(GeneralName.iPAddress, host)
                : new GeneralName(GeneralName.dNSName, host);
        b.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(gn));

        ContentSigner signer = new JcaContentSignerBuilder(SIG_ALG).build(caKey);
        return new JcaX509CertificateConverter().getCertificate(b.build(signer));
    }

    private static boolean isIpAddress(String s) {
        return s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || s.contains(":");
    }
}
