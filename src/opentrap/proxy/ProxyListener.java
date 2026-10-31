package opentrap.proxy;

import java.io.Serializable;

/** Configuration for one listening port. */
public class ProxyListener implements Serializable {
    private static final long serialVersionUID = 2L;

    /** The port the proxy listens on unless told otherwise: the conventional 8080. */
    public static final int DEFAULT_PORT = 8080;

    /** Which addresses a listener accepts connections on. */
    public enum Bind { LOOPBACK, ALL, SPECIFIC }

    /** How the server certificate shown to a TLS client is chosen. */
    public enum Cert { SELF_SIGNED, PER_HOST, SPECIFIC_HOSTNAME, CUSTOM }

    public boolean running = true;
    public int port = DEFAULT_PORT;
    public Bind bind = Bind.LOOPBACK;
    /** The address to bind when {@link Bind#SPECIFIC}. */
    public String bindAddress = "";
    public boolean invisible = false;
    public String redirectHost = "";
    public int redirectPort = 0;
    public boolean forceTls = false;

    public Cert cert = Cert.PER_HOST;
    /** The hostname to put in the certificate when {@link Cert#SPECIFIC_HOSTNAME}. */
    public String certHostname = "";
    /** The PKCS#12 file and its password when {@link Cert#CUSTOM}. */
    public String certFile = "";
    public String certPassword = "";

    /** Empty means the Java installation's default protocols. */
    public String tlsProtocols = "";
    public boolean http2 = true;

    public ProxyListener() {}

    public ProxyListener(int port) { this.port = port; }

    /**
     * Kept so projects saved before listeners grew a bind mode still load: the
     * old field was a plain "loopback only" flag.
     */
    public boolean loopbackOnly() { return bind == Bind.LOOPBACK; }

    /** The address this listener binds, as the interface column shows it. */
    public String bindHost() {
        return switch (bind) {
            case LOOPBACK -> "127.0.0.1";
            case ALL -> "0.0.0.0";
            case SPECIFIC -> bindAddress == null || bindAddress.isBlank() ? "0.0.0.0" : bindAddress;
        };
    }

    /** "127.0.0.1:8080", as the reference labels a listener. */
    public String interfaceLabel() { return bindHost() + ":" + port; }

    public String redirectLabel() {
        if (redirectHost == null || redirectHost.isBlank()) return "";
        return redirectHost + (redirectPort > 0 ? ":" + redirectPort : "");
    }

    /** How the certificate choice reads in the listeners table. */
    public String certLabel() {
        return switch (cert) {
            case SELF_SIGNED -> "Self-signed";
            case PER_HOST -> "Per-host";
            case SPECIFIC_HOSTNAME -> certHostname == null || certHostname.isBlank()
                    ? "Specific hostname" : certHostname;
            case CUSTOM -> "Custom";
        };
    }

    /** How the protocol choice reads in the listeners table. */
    public String tlsLabel() {
        return tlsProtocols == null || tlsProtocols.isBlank() ? "Default" : tlsProtocols;
    }

    public ProxyListener copy() {
        ProxyListener c = new ProxyListener(port);
        c.running = running;
        c.bind = bind;
        c.bindAddress = bindAddress;
        c.invisible = invisible;
        c.redirectHost = redirectHost;
        c.redirectPort = redirectPort;
        c.forceTls = forceTls;
        c.cert = cert;
        c.certHostname = certHostname;
        c.certFile = certFile;
        c.certPassword = certPassword;
        c.tlsProtocols = tlsProtocols;
        c.http2 = http2;
        return c;
    }
}
