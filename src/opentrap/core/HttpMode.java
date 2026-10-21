package opentrap.core;

/**
 * How a request is put on the wire. HTTP/1.x is the byte-exact raw path and the
 * default; the HTTP/2 modes delegate to the JDK's HTTP client, which negotiates
 * ALPN and manages HPACK.
 */
public enum HttpMode {
    /** Byte-exact HTTP/1.x over a raw socket (default). */
    HTTP_1,
    /** HTTP/2, negotiated over TLS ALPN. */
    HTTP_2,
    /** HTTP/2 without requiring ALPN (prior knowledge; best effort). */
    HTTP_2_IGNORE_ALPN,
    /** Negotiate the best protocol, falling back to HTTP/1.1. */
    AUTO;

    @Override public String toString() {
        return switch (this) {
            case HTTP_1 -> "HTTP/1";
            case HTTP_2 -> "HTTP/2";
            case HTTP_2_IGNORE_ALPN -> "HTTP/2 (no ALPN)";
            case AUTO -> "auto";
        };
    }
}
