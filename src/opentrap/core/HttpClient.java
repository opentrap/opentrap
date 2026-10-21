package opentrap.core;

import javax.net.ssl.*;
import java.io.*;
import java.net.*;
import java.security.cert.X509Certificate;
import java.util.Locale;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Sends a request exactly as given and reads the response.
 *
 * Deliberately byte-exact: no header reordering, no silent Content-Length
 * correction, no normalisation. Callers that want those must ask.
 */
public class HttpClient {

    public static class Options {
        public int connectTimeoutMs = 15000;
        public int readTimeoutMs = 30000;
        public boolean updateContentLength = false;
        public boolean unpackGzip = true;
        public boolean followRedirects = false;
        public int maxRedirects = 5;
        public boolean keepAlive = false;
        /** Optional session-handling hook; null disables session handling for this send. */
        public SessionHook session;
        /** Which tool is sending, so session rules scoped to a tool can match. */
        public String sessionTool = "";
        /** Protocol to use. HTTP/1.x is the raw byte-exact default. */
        public HttpMode mode = HttpMode.HTTP_1;
        /** Optional upstream override (e.g. the ZAP engine for live passive scanning);
         *  falls back to a direct connection if it cannot be reached. */
        public UpstreamProxy upstream;
    }

    public static class Result {
        public HttpMessage response;
        public String remoteIp = "";
        public long millis;
        public String error = "";
        public int redirectsFollowed;
        public HttpService finalService;
        public HttpMessage finalRequest;
        public boolean ok() { return error.isEmpty() && response != null; }
    }

    /** Resolves an optional upstream proxy per destination; null routes direct. */
    public interface ProxyProvider { UpstreamProxy proxyFor(HttpService svc); }

    private static volatile ProxyProvider proxyProvider;
    public static void setProxyProvider(ProxyProvider p) { proxyProvider = p; }

    private static SSLSocketFactory permissiveFactory;

    /** Testing tools must reach hosts with broken certificates, so trust all. */
    private static synchronized SSLSocketFactory sslFactory() throws Exception {
        if (permissiveFactory == null) {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{ new X509ExtendedTrustManager() {
                public void checkClientTrusted(X509Certificate[] c, String a) {}
                public void checkServerTrusted(X509Certificate[] c, String a) {}
                public void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
                public void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
                public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
                public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            }}, new java.security.SecureRandom());
            permissiveFactory = ctx.getSocketFactory();
        }
        return permissiveFactory;
    }

    /**
     * Send, following redirects per the options, and additionally consult the
     * session hook: cookies are applied before the send and learned from the
     * response, and a rule may re-authenticate and ask for one resend.
     */
    /**
     * Open a connected socket to a service, with the suite's trust-all TLS and
     * SNI when secure. Used by the WebSocket relay, which needs a raw persistent
     * socket rather than the request/response send cycle.
     */
    public static Socket openSocket(HttpService svc, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return connect(svc, connectTimeoutMs, readTimeoutMs);
    }

    /**
     * Connect to a service, routing through an upstream HTTP (CONNECT) or SOCKS
     * proxy when one is configured for the destination, and layering TLS (with
     * SNI) on top when the service is secure.
     */
    private static Socket connect(HttpService svc, int connectTimeoutMs, int readTimeoutMs) throws Exception {
        return connect(svc, connectTimeoutMs, readTimeoutMs, null);
    }

    /**
     * Connect, optionally through an override upstream (used for live passive
     * scanning through the ZAP engine). If the override cannot be reached the
     * connection falls back to direct, so the scan engine being down never breaks
     * the user's traffic.
     */
    private static Socket connect(HttpService svc, int ct, int rt, UpstreamProxy override) throws Exception {
        if (override != null) {
            try { return connectVia(svc, ct, rt, override); }
            catch (Exception e) { return connectVia(svc, ct, rt, null); } // fail-safe: go direct
        }
        return connectVia(svc, ct, rt, proxyProvider == null ? null : proxyProvider.proxyFor(svc));
    }

    private static Socket connectVia(HttpService svc, int connectTimeoutMs, int readTimeoutMs, UpstreamProxy up) throws Exception {
        Socket base;
        if (up == null) {
            base = new Socket();
            base.connect(new InetSocketAddress(svc.host(), svc.port()), connectTimeoutMs);
        } else if (up.type == UpstreamProxy.Type.SOCKS) {
            base = new Socket(new java.net.Proxy(java.net.Proxy.Type.SOCKS, new InetSocketAddress(up.host, up.port)));
            base.connect(InetSocketAddress.createUnresolved(svc.host(), svc.port()), connectTimeoutMs);
        } else {
            base = new Socket();
            base.connect(new InetSocketAddress(up.host, up.port), connectTimeoutMs);
            httpConnectTunnel(base, svc, up);
        }
        base.setSoTimeout(readTimeoutMs);
        if (!svc.secure()) return base;
        SSLSocket s = (SSLSocket) sslFactory().createSocket(base, svc.host(), svc.port(), true);
        try {
            SSLParameters p = s.getSSLParameters();
            p.setServerNames(java.util.List.of(new SNIHostName(svc.host())));
            s.setSSLParameters(p);
        } catch (Exception ignored) {}
        s.startHandshake();
        return s;
    }

    /** Establish an HTTP CONNECT tunnel through the upstream proxy to the target. */
    private static void httpConnectTunnel(Socket s, HttpService target, UpstreamProxy up) throws IOException {
        String hp = target.host() + ":" + target.port();
        StringBuilder sb = new StringBuilder();
        sb.append("CONNECT ").append(hp).append(" HTTP/1.1\r\nHost: ").append(hp).append("\r\n");
        if (up.user != null && !up.user.isEmpty()) {
            String cred = java.util.Base64.getEncoder().encodeToString(
                    (up.user + ":" + up.pass).getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
            sb.append("Proxy-Authorization: Basic ").append(cred).append("\r\n");
        }
        sb.append("\r\n");
        s.getOutputStream().write(Bytes.bytes(sb.toString()));
        s.getOutputStream().flush();
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        InputStream in = s.getInputStream();
        while (state < 4) {
            int b = in.read();
            if (b < 0) break;
            head.write(b);
            if ((state == 0 || state == 2) && b == 13) state++;
            else if ((state == 1 || state == 3) && b == 10) state++;
            else state = 0;
            if (head.size() > 64 * 1024) break;
        }
        HttpMessage resp = new HttpMessage(head.toByteArray());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("upstream proxy refused CONNECT: " + resp.startLine());
        }
    }

    public Result send(HttpService svc, HttpMessage request, Options opt) {
        Result r = null;
        for (int attempt = 0; attempt <= 1; attempt++) {
            HttpMessage start = request.copy();
            if (opt.session != null) {
                try { opt.session.beforeSend(opt.sessionTool, svc, start); } catch (Exception ignored) {}
            }
            r = sendFollowingRedirects(svc, start, opt);
            if (opt.session == null || !r.ok()) break;
            boolean retry;
            try { retry = opt.session.afterResponse(opt.sessionTool, r.finalService, r.finalRequest, r.response); }
            catch (Exception e) { retry = false; }
            if (!retry || attempt >= 1) break;
        }
        return r;
    }

    private Result sendFollowingRedirects(HttpService svc, HttpMessage request, Options opt) {
        Result r = new Result();
        HttpService cur = svc;
        HttpMessage req = request.copy();
        long start = System.currentTimeMillis();

        for (int hop = 0; ; hop++) {
            if (opt.updateContentLength && req.isRequest() && req.body().length > 0) {
                req.updateContentLength();
            }
            Result one = sendOnce(cur, req, opt);
            r.remoteIp = one.remoteIp;
            r.error = one.error;
            r.response = one.response;
            r.finalService = cur;
            r.finalRequest = req;
            if (!one.ok()) break;

            if (!opt.followRedirects || hop >= opt.maxRedirects) break;
            int sc = one.response.statusCode();
            String loc = one.response.header("Location");
            if (sc < 300 || sc > 399 || loc == null || loc.isBlank()) break;

            try {
                URI base = URI.create(cur.url() + req.path());
                URI next = base.resolve(loc.trim());
                boolean sec = "https".equalsIgnoreCase(next.getScheme());
                int port = next.getPort() == -1 ? (sec ? 443 : 80) : next.getPort();
                if (next.getHost() == null) break;
                cur = new HttpService(next.getHost(), port, sec);
                String target = (next.getRawPath() == null || next.getRawPath().isEmpty()) ? "/" : next.getRawPath();
                if (next.getRawQuery() != null) target += "?" + next.getRawQuery();
                HttpMessage nr = req.copy();
                if (sc == 303 || ((sc == 301 || sc == 302) && !nr.method().equalsIgnoreCase("GET"))) {
                    nr.setMethod("GET");
                    nr.setBody(new byte[0]);
                    nr.removeHeader("Content-Length");
                    nr.removeHeader("Content-Type");
                }
                nr.setRequestTarget(target);
                nr.setHeader("Host", cur.defaultPort() ? cur.host() : cur.host() + ":" + cur.port());
                req = nr;
                r.redirectsFollowed++;
            } catch (Exception e) {
                break;
            }
        }
        r.millis = System.currentTimeMillis() - start;
        return r;
    }

    private Result sendOnce(HttpService svc, HttpMessage request, Options opt) {
        if (opt.mode != null && opt.mode != HttpMode.HTTP_1) {
            return Http2Client.send(svc, request, opt);
        }
        Result r = new Result();
        Socket sock = null;
        try {
            sock = connect(svc, opt.connectTimeoutMs, opt.readTimeoutMs, opt.upstream);
            try { r.remoteIp = ((InetSocketAddress) sock.getRemoteSocketAddress()).getAddress().getHostAddress(); }
            catch (Exception ignored) {}

            OutputStream out = sock.getOutputStream();
            out.write(request.raw());
            out.flush();

            byte[] raw = readResponse(sock.getInputStream(), request.method());
            if (raw.length == 0) { r.error = "empty response"; return r; }
            HttpMessage resp = new HttpMessage(raw);
            if (opt.unpackGzip) unpack(resp);
            r.response = resp;
            return r;
        } catch (UnknownHostException e) {
            r.error = "unknown host: " + svc.host();
        } catch (SocketTimeoutException e) {
            r.error = "timeout";
        } catch (ConnectException e) {
            r.error = "failed to connect to " + svc.host() + ":" + svc.port();
        } catch (SSLException e) {
            r.error = "TLS error: " + e.getMessage();
        } catch (Exception e) {
            r.error = String.valueOf(e.getMessage());
        } finally {
            if (sock != null) try { sock.close(); } catch (IOException ignored) {}
        }
        return r;
    }

    /** Read headers, then body framed by Content-Length, chunked, or close. */
    public static byte[] readResponse(InputStream in, String requestMethod) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        while (state < 4) {
            int b = in.read();
            if (b < 0) break;
            head.write(b);
            if ((state == 0 || state == 2) && b == 13) state++;
            else if ((state == 1 || state == 3) && b == 10) state++;
            else if (b == 10) state = (state == 2) ? 4 : 2;
            else state = 0;
            if (head.size() > 512 * 1024) break;
        }
        byte[] headBytes = head.toByteArray();
        if (headBytes.length == 0) return new byte[0];

        HttpMessage probe = new HttpMessage(headBytes);
        int status = probe.statusCode();
        boolean noBody = "HEAD".equalsIgnoreCase(requestMethod)
                || status == 204 || status == 304 || (status >= 100 && status < 200);
        if (noBody) return headBytes;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String te = probe.header("Transfer-Encoding");
        String cl = probe.header("Content-Length");

        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            while (true) {
                String line = readLine(in);
                if (line == null) break;
                String sz = line.trim();
                int semi = sz.indexOf(';');
                if (semi >= 0) sz = sz.substring(0, semi);
                int n;
                try { n = Integer.parseInt(sz.trim(), 16); } catch (Exception e) { break; }
                if (n == 0) { readLine(in); break; }
                byte[] buf = new byte[n];
                int got = 0;
                while (got < n) {
                    int k = in.read(buf, got, n - got);
                    if (k < 0) break;
                    got += k;
                }
                body.write(buf, 0, got);
                readLine(in);
                if (got < n) break;
            }
            byte[] merged = Bytes.concat(headBytes, body.toByteArray());
            HttpMessage m = new HttpMessage(merged);
            m.removeHeader("Transfer-Encoding");
            m.setHeader("Content-Length", String.valueOf(body.size()));
            return m.raw();
        }

        if (cl != null) {
            int n;
            try { n = Integer.parseInt(cl.trim()); } catch (Exception e) { n = -1; }
            if (n >= 0) {
                byte[] buf = new byte[8192];
                int remaining = n;
                while (remaining > 0) {
                    int k = in.read(buf, 0, Math.min(buf.length, remaining));
                    if (k < 0) break;
                    body.write(buf, 0, k);
                    remaining -= k;
                }
                return Bytes.concat(headBytes, body.toByteArray());
            }
        }

        byte[] buf = new byte[8192];
        try {
            int k;
            while ((k = in.read(buf)) > 0) body.write(buf, 0, k);
        } catch (SocketTimeoutException ignored) {
        }
        return Bytes.concat(headBytes, body.toByteArray());
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == 10) break;
            b.write(c);
        }
        if (c < 0 && b.size() == 0) return null;
        return Bytes.str(b.toByteArray());
    }

    /** Replace a compressed body with its plaintext, dropping the header. */
    public static void unpack(HttpMessage m) {
        String ce = m.header("Content-Encoding");
        if (ce == null) return;
        String enc = ce.trim().toLowerCase(Locale.ROOT);
        byte[] body = m.body();
        if (body.length == 0) return;
        try {
            InputStream src = new ByteArrayInputStream(body);
            InputStream dec;
            if (enc.contains("gzip")) dec = new GZIPInputStream(src);
            else if (enc.contains("deflate")) dec = new InflaterInputStream(src);
            else if (enc.contains("br")) dec = new org.brotli.dec.BrotliInputStream(src);
            else return;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int k;
            while ((k = dec.read(buf)) > 0) out.write(buf, 0, k);
            m.removeHeader("Content-Encoding");
            m.setBody(out.toByteArray());
            m.setHeader("Content-Length", String.valueOf(out.size()));
        } catch (Exception ignored) {
        }
    }
}
