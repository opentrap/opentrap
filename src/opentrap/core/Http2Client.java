package opentrap.core;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Locale;

/**
 * The HTTP/2 send path. Rather than hand-roll HPACK and framing, this delegates
 * to the JDK's own HTTP client, which negotiates HTTP/2 over ALPN and falls back
 * to HTTP/1.1 under {@link HttpMode#AUTO}. Our raw {@link HttpMessage} is
 * translated into a JDK request and the response translated back, so the rest of
 * the suite sees the same model it always has.
 *
 * <p>Known limitation: because HTTP/2 is binary and the JDK manages the header
 * block, this path is not byte-exact the way the HTTP/1.x socket path is, and it
 * does not expose raw HTTP/2 frame editing. Raw HTTP/1.x stays the default.
 */
public final class Http2Client {

    private Http2Client() {}

    static {
        // Let us pass headers the JDK normally reserves; it still manages framing.
        try {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders",
                    "host,content-length,connection,upgrade,transfer-encoding");
        } catch (Exception ignored) {}
    }

    private static volatile java.net.http.HttpClient shared;

    private static java.net.http.HttpClient client(HttpClient.Options opt) throws Exception {
        if (shared == null) {
            synchronized (Http2Client.class) {
                if (shared == null) {
                    SSLContext ssl = SSLContext.getInstance("TLS");
                    ssl.init(null, new TrustManager[]{ new X509TrustManager() {
                        public void checkClientTrusted(X509Certificate[] c, String a) {}
                        public void checkServerTrusted(X509Certificate[] c, String a) {}
                        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }}, new java.security.SecureRandom());
                    shared = java.net.http.HttpClient.newBuilder()
                            .version(java.net.http.HttpClient.Version.HTTP_2)
                            .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofMillis(Math.max(1000, opt.connectTimeoutMs)))
                            .sslContext(ssl)
                            .build();
                }
            }
        }
        return shared;
    }

    public static HttpClient.Result send(HttpService svc, HttpMessage req, HttpClient.Options opt) {
        HttpClient.Result r = new HttpClient.Result();
        long t0 = System.currentTimeMillis();
        try {
            String scheme = svc.secure() ? "https" : "http";
            String authority = svc.defaultPort() ? svc.host() : svc.host() + ":" + svc.port();
            String pq = req.path() + (req.query().isEmpty() ? "" : "?" + req.query());
            if (pq.isEmpty()) pq = "/";
            URI uri = URI.create(scheme + "://" + authority + pq);

            java.net.http.HttpClient.Version ver = opt.mode == HttpMode.HTTP_1
                    ? java.net.http.HttpClient.Version.HTTP_1_1
                    : java.net.http.HttpClient.Version.HTTP_2;

            byte[] body = req.body();
            HttpRequest.Builder b = HttpRequest.newBuilder(uri)
                    .version(ver)
                    .timeout(Duration.ofMillis(Math.max(1000, opt.readTimeoutMs)));
            String method = req.method().isEmpty() ? "GET" : req.method();
            b.method(method, body.length > 0
                    ? HttpRequest.BodyPublishers.ofByteArray(body)
                    : HttpRequest.BodyPublishers.noBody());

            for (String line : req.headerLines()) {
                int c = line.indexOf(':');
                if (c < 0) continue;
                String n = line.substring(0, c).trim();
                String v = line.substring(c + 1).trim();
                String nl = n.toLowerCase(Locale.ROOT);
                // The JDK owns these; passing them risks a conflict or is disallowed.
                if (nl.isEmpty() || nl.charAt(0) == ':' || nl.equals("content-length")
                        || nl.equals("transfer-encoding") || nl.equals("connection")
                        || nl.equals("upgrade") || nl.equals("keep-alive") || nl.equals("host")) continue;
                try { b.header(n, v); } catch (Exception ignored) {}
            }

            HttpResponse<byte[]> resp = client(opt).send(b.build(), HttpResponse.BodyHandlers.ofByteArray());

            StringBuilder sb = new StringBuilder();
            String verStr = resp.version() == java.net.http.HttpClient.Version.HTTP_2 ? "HTTP/2" : "HTTP/1.1";
            sb.append(verStr).append(' ').append(resp.statusCode()).append("\r\n");
            resp.headers().map().forEach((k, vs) -> {
                if (k == null || k.isEmpty() || k.charAt(0) == ':') return;
                for (String v : vs) sb.append(k).append(": ").append(v).append("\r\n");
            });
            sb.append("\r\n");
            HttpMessage out = new HttpMessage(Bytes.concat(Bytes.bytes(sb.toString()), resp.body()));
            if (opt.unpackGzip) HttpClient.unpack(out);
            r.response = out;
            try { r.remoteIp = InetAddress.getByName(svc.host()).getHostAddress(); } catch (Exception ignored) {}
        } catch (Exception e) {
            r.error = e.getMessage() == null ? e.toString() : e.getMessage();
        }
        r.millis = System.currentTimeMillis() - t0;
        return r;
    }
}
