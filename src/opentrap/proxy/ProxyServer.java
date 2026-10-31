package opentrap.proxy;

import opentrap.core.*;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/**
 * The interception proxy.
 *
 * One accept loop per listener, one thread per connection. That is what the
 * original did and it stays comprehensible; the thread pool is bounded so a
 * flood of connections cannot exhaust memory.
 */
public class ProxyServer {

    public interface Observer {
        void exchangeLogged(LogEntry entry);
        void statusChanged(String text);
    }

    private final SuiteContext ctx;
    private final CertificateAuthority ca;
    private final InterceptQueue intercept = new InterceptQueue();
    private final ExecutorService pool = new ThreadPoolExecutor(
            4, 400, 60, TimeUnit.SECONDS, new SynchronousQueue<>(),
            r -> { Thread t = new Thread(r, "proxy-conn"); t.setDaemon(true); return t; },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final List<ProxyListener> listeners = new ArrayList<>();
    private final Map<Integer, ServerSocket> sockets = new ConcurrentHashMap<>();

    // Settings surfaced in the options tab.
    public volatile boolean interceptRequests = true;
    public volatile boolean interceptResponses = false;
    public volatile boolean interceptingEnabled = false;
    public volatile boolean updateContentLengthRequests = true;
    public volatile boolean updateContentLengthResponses = true;
    public volatile boolean unpackGzip = true;
    public volatile boolean http10 = false;
    public volatile boolean logOutOfScope = true;
    public volatile boolean stripProxyHeaders = true;

    public final List<ProxyRule> requestRules = new ArrayList<>();
    public final List<ProxyRule> responseRules = new ArrayList<>();
    public final List<MatchReplaceRule> matchReplace = new ArrayList<>(MatchReplaceRule.defaults());
    public final List<MatchReplaceRule> wsMatchReplace = new java.util.concurrent.CopyOnWriteArrayList<>();

    // Response modification toggles.
    public volatile boolean unhideHiddenFields = false;
    public volatile boolean enableDisabledFields = false;
    public volatile boolean removeLengthLimits = false;
    public volatile boolean removeJsValidation = false;
    public volatile boolean removeAllJavaScript = false;
    public volatile boolean removeObjectTags = false;
    // Strip the response headers that stop an injected payload running in the
    // browser (CSP, X-Frame-Options, and friends), so a proof of concept
    // actually fires. The scanner still audits the server's original response,
    // so this never produces a false "header missing" finding.
    public volatile boolean stripSecurityHeaders = false;

    private Observer observer;

    public ProxyServer(SuiteContext ctx, Path caDir) throws Exception {
        this.ctx = ctx;
        this.ca = new CertificateAuthority(caDir);
        requestRules.add(new ProxyRule(true, "or", "file extension", "does not match",
                "(^gif$|^jpg$|^jpeg$|^png$|^css$|^js$|^ico$|^svg$|^woff$|^woff2$|^ttf$)"));
        requestRules.add(new ProxyRule(false, "or", "request", "contains parameters", ""));
        requestRules.add(new ProxyRule(false, "and", "URL", "is in target scope", ""));
        responseRules.add(new ProxyRule(true, "or", "content type", "matches", "text"));
        responseRules.add(new ProxyRule(false, "or", "request", "was modified", ""));
        responseRules.add(new ProxyRule(false, "and", "response code", "does not match", "^304$"));
        listeners.add(new ProxyListener(defaultPort()));
    }

    /**
     * The port of the listener created at startup: {@link ProxyListener#DEFAULT_PORT}
     * unless the {@code opentrap.port} system property names a valid port.
     */
    public static int defaultPort() {
        try {
            int p = Integer.parseInt(System.getProperty("opentrap.port", "").trim());
            if (p >= 1 && p <= 65535) return p;
        } catch (Exception ignored) {}
        return ProxyListener.DEFAULT_PORT;
    }

    public CertificateAuthority ca()       { return ca; }
    public InterceptQueue interceptQueue() { return intercept; }
    public List<ProxyListener> listeners() { return listeners; }
    public void setObserver(Observer o)    { this.observer = o; }

    private void status(String s) { if (observer != null) observer.statusChanged(s); }

    // ---------------- lifecycle ----------------

    public void startAll() {
        for (ProxyListener l : listeners) if (l.running) start(l);
    }

    public synchronized void start(ProxyListener cfg) {
        if (sockets.containsKey(cfg.port)) return;
        try {
            // ALL binds every interface, which is what a null bind address
            // means to ServerSocket; the other two name one address.
            ServerSocket ss = cfg.bind == ProxyListener.Bind.ALL
                    ? new ServerSocket(cfg.port, 100)
                    : new ServerSocket(cfg.port, 100, InetAddress.getByName(cfg.bindHost()));
            ss.setReuseAddress(true);
            sockets.put(cfg.port, ss);
            cfg.running = true;
            Thread t = new Thread(() -> acceptLoop(cfg, ss), "proxy-accept-" + cfg.port);
            t.setDaemon(true);
            t.start();
            ctx.alert("proxy", "proxy service started on port " + cfg.port);
        } catch (IOException e) {
            cfg.running = false;
            ctx.alert("proxy", "failed to start proxy service on port " + cfg.port
                    + ". Check whether another service is already using this port.");
        }
        listenersChanged();
    }

    public synchronized void stop(ProxyListener cfg) {
        ServerSocket ss = sockets.remove(cfg.port);
        cfg.running = false;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
            ctx.alert("proxy", "proxy service stopped on port " + cfg.port);
        }
        listenersChanged();
    }

    public void stopAll() {
        for (ProxyListener l : new ArrayList<>(listeners)) stop(l);
        intercept.releaseAll();
    }

    public boolean anyRunning() { return !sockets.isEmpty(); }

    private final List<Runnable> listenerHooks = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Run {@code r} on the event thread whenever a listener starts or stops. */
    public void onListenersChanged(Runnable r) { listenerHooks.add(r); }

    private void listenersChanged() {
        for (Runnable r : listenerHooks) javax.swing.SwingUtilities.invokeLater(r);
    }

    /** What the proxy is listening on right now, e.g. "listening on 127.0.0.1:8080". */
    public String listeningSummary() { return summary(listeners); }

    /**
     * Describe the running listeners in {@code list}: every address and port, or
     * a clear statement that none is running. Shown on the intercept and options
     * tabs so the port is never in doubt.
     */
    public static String summary(List<ProxyListener> list) {
        StringBuilder sb = new StringBuilder();
        for (ProxyListener l : list) {
            if (!l.running) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(l.interfaceLabel());
        }
        return sb.length() == 0
                ? "no proxy listeners are running - add one on the options tab"
                : "listening on " + sb;
    }

    private void acceptLoop(ProxyListener cfg, ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                Socket client = ss.accept();
                pool.execute(() -> {
                    try { new Connection(cfg, client).run(); }
                    // Per-connection isolation: one broken exchange must not
                    // affect the listener. Anything reaching here is a bug or
                    // resource exhaustion, so it is logged, never swallowed.
                    catch (Throwable t) {
                        Debug.error("proxy connection handling failed for "
                                + client.getRemoteSocketAddress(), t);
                    }
                });
            } catch (IOException e) {
                if (ss.isClosed()) return;
            } catch (OutOfMemoryError e) {
                System.gc();
                ctx.alert("proxy", "failed to allocate memory - consider saving your work");
            }
        }
    }

    // ---------------- per-connection handling ----------------

    private class Connection {
        private final ProxyListener cfg;
        private final Socket client;
        private HttpService tunnelService;

        Connection(ProxyListener cfg, Socket client) {
            this.cfg = cfg; this.client = client;
        }

        void run() {
            try {
                client.setSoTimeout(120000);
                // No read-ahead buffer here: for CONNECT we hand the raw socket to
                // TLS, and a buffer would swallow the ClientHello bytes.
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();

                byte[] head = readHead(in);
                if (head.length == 0) return;
                HttpMessage first = new HttpMessage(head);

                if ("CONNECT".equalsIgnoreCase(first.method())) {
                    handleConnect(first, in, out);
                    return;
                }
                handlePlain(first, in, out, false, null);
            } catch (Exception e) {
                // A protocol-level failure ends this exchange; the client sees a
                // dropped connection. Routine client-side garbage (truncated
                // requests, bogus headers) lands here too, so trace it at debug
                // level rather than reporting every malformed request.
                Debug.log("proxy exchange failed: " + e);
            } finally {
                try { client.close(); } catch (IOException ignored) {}
            }
        }

        /** Establish a TLS tunnel, then read decrypted requests from inside it. */
        private void handleConnect(HttpMessage req, InputStream in, OutputStream out) throws Exception {
            String target = req.requestTarget();
            String host = target;
            int port = 443;
            int c = target.lastIndexOf(':');
            if (c > 0) {
                host = target.substring(0, c);
                try { port = Integer.parseInt(target.substring(c + 1)); } catch (Exception ignored) {}
            }
            tunnelService = new HttpService(host, port, true);
            out.write(Bytes.bytes("HTTP/1.1 200 Connection established\r\n\r\n"));
            out.flush();

            SSLContext sslCtx = ca.contextFor(host);
            SSLSocketFactory f = sslCtx.getSocketFactory();
            SSLSocket tls;
            try {
                tls = (SSLSocket) f.createSocket(client, host, port, true);
                tls.setUseClientMode(false);
                tls.setSoTimeout(120000);
                tls.startHandshake();
            } catch (Exception e) {
                return; // client rejected our certificate, or pinned
            }

            InputStream tin = new BufferedInputStream(tls.getInputStream(), 32768);
            OutputStream tout = new BufferedOutputStream(tls.getOutputStream(), 32768);
            tls.setSoTimeout(120000);
            while (true) {
                byte[] head = readHead(tin);
                if (head.length == 0) break;
                HttpMessage m = new HttpMessage(head);
                if (!handlePlain(m, tin, tout, true, tunnelService)) break;
            }
            try { tls.close(); } catch (IOException ignored) {}
        }

        /** Returns true when the connection may be reused for another request. */
        private boolean handlePlain(HttpMessage headMsg, InputStream in, OutputStream out,
                                    boolean secure, HttpService known) throws Exception {
            HttpMessage request = new HttpMessage(Bytes.concat(headMsg.raw(), readBody(in, headMsg)));

            HttpService svc = known;
            if (svc == null) {
                String t = request.requestTarget();
                if (t.startsWith("http://") || t.startsWith("https://")) {
                    svc = HttpService.parse(t);
                    // An absolute-form target becomes origin-form upstream.
                    try {
                        URI u = URI.create(t);
                        String p = (u.getRawPath() == null || u.getRawPath().isEmpty()) ? "/" : u.getRawPath();
                        if (u.getRawQuery() != null) p += "?" + u.getRawQuery();
                        request.setRequestTarget(p);
                    } catch (Exception ignored) {}
                } else {
                    String hostHeader = request.header("Host");
                    if (hostHeader == null) { writeError(out, "no Host header in request"); return false; }
                    svc = parseHostHeader(hostHeader, secure);
                }
            }
            if (svc == null) { writeError(out, "could not determine target host"); return false; }

            if (cfg.redirectHost != null && !cfg.redirectHost.isBlank()) {
                svc = new HttpService(cfg.redirectHost,
                        cfg.redirectPort > 0 ? cfg.redirectPort : svc.port(), svc.secure());
            }

            // Serve our own CA so the user can install it without a side channel.
            if (isLocalRequest(request, svc)) {
                serveCertificate(out, request.path());
                return false;
            }

            if (stripProxyHeaders) {
                request.removeHeader("Proxy-Connection");
                request.removeHeader("Proxy-Authorization");
            }
            if (http10) {
                String sl = request.startLine().replace("HTTP/1.1", "HTTP/1.0");
                byte[] rest = Bytes.slice(request.raw(),
                        Bytes.indexOf(request.raw(), Bytes.bytes("\n")) + 1, request.raw().length);
                request.setRaw(Bytes.concat(Bytes.bytes(sl + "\r\n"), rest));
            }

            HttpMessage original = request.copy();
            MatchReplaceRule.applyAll(matchReplace, request, true);

            boolean wasIntercepted = false;
            boolean wasModified = !Arrays.equals(original.raw(), request.raw());

            if (interceptingEnabled && interceptRequests
                    && ProxyRule.evaluateChain(requestRules, svc, request, null, "", false, wasModified, ctx.scope())) {
                wasIntercepted = true;
                InterceptQueue.Pending p = new InterceptQueue.Pending(svc, request, true, null);
                InterceptQueue.Action a = intercept.submit(p);
                if (a == InterceptQueue.Action.DROP) {
                    intercept.completed(p);
                    return false;
                }
                if (p.editedBytes() != null) {
                    request.setRaw(p.editedBytes());
                    wasModified = true;
                    if (updateContentLengthRequests) request.updateContentLength();
                }
                intercept.completed(p);
            }

            // A WebSocket upgrade leaves HTTP behind: hand the connection to the
            // relay, which streams and records frames both ways until it closes.
            if (isWebSocketUpgrade(request)) {
                handleWebSocket(request, in, out, svc);
                return false;
            }

            LogEntry entry = new LogEntry("proxy", svc, request);
            // The pre-edit request is kept only when something changed it, as
            // the response side does below: an unmodified original would just
            // duplicate the bytes the entry already holds, for every entry.
            if (wasModified) entry.setOriginalRequest(original);
            entry.setEdited(wasModified);

            HttpClient.Options opt = new HttpClient.Options();
            opt.updateContentLength = false;
            opt.unpackGzip = unpackGzip;
            opt.followRedirects = false;
            opt.session = ctx.sessions();
            opt.sessionTool = "proxy";
            // Live passive scanning: route in-scope traffic through the scan engine.
            if (ctx.liveScanRouter() != null) opt.upstream = ctx.liveScanRouter().apply(svc);
            long t0 = System.currentTimeMillis();
            HttpClient.Result res = new HttpClient().send(svc, request, opt);
            entry.setDurationMillis(System.currentTimeMillis() - t0);
            entry.setRemoteIp(res.remoteIp);

            if (!res.ok()) {
                entry.setError(res.error);
                logEntry(entry, svc, request);
                writeError(out, res.error);
                return false;
            }

            HttpMessage response = res.response;
            // Keep the server's response as it arrived, so the scanner audits
            // the real headers even when response modification or security-header
            // stripping changes what the browser is handed.
            HttpMessage serverResponse = response.copy();
            MatchReplaceRule.applyAll(matchReplace, response, false);
            applyResponseModification(response);
            stripSecurityResponseHeaders(response);
            if (!Arrays.equals(serverResponse.raw(), response.raw())) {
                entry.setOriginalResponse(serverResponse);
            }

            if (interceptingEnabled && interceptResponses
                    && ProxyRule.evaluateChain(responseRules, svc, request, response,
                                               res.remoteIp, wasIntercepted, wasModified, ctx.scope())) {
                InterceptQueue.Pending p = new InterceptQueue.Pending(svc, response, false, request);
                InterceptQueue.Action a = intercept.submit(p);
                if (a == InterceptQueue.Action.DROP) {
                    intercept.completed(p);
                    return false;
                }
                if (p.editedBytes() != null) {
                    response.setRaw(p.editedBytes());
                    if (updateContentLengthResponses) response.updateContentLength();
                }
                intercept.completed(p);
            }

            entry.setResponse(response);
            logEntry(entry, svc, request);

            out.write(response.raw());
            out.flush();

            String conn = response.header("Connection");
            return conn == null || !conn.toLowerCase(Locale.ROOT).contains("close");
        }

        private boolean isLocalRequest(HttpMessage req, HttpService svc) {
            String h = svc.host();
            return h.equals("opentrap") || h.equals("opentrap.proxy")
                || ((h.equals("localhost") || h.equals("127.0.0.1")) && svc.port() == cfg.port);
        }

        private void serveCertificate(OutputStream out, String path) throws Exception {
            if (path.startsWith("/cert/der") || path.startsWith("/cert/download")) {
                byte[] der = ca.caCertificateDer();
                String head = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: application/x-x509-ca-cert\r\n"
                        + "Content-Disposition: attachment; filename=\"opentrap-ca.der\"\r\n"
                        + "Content-Length: " + der.length + "\r\n"
                        + "Connection: close\r\n\r\n";
                out.write(Bytes.bytes(head));
                out.write(der);
            } else {
                String body = "<!doctype html><html><head><title>OpenTRAP</title>"
                        + "<style>body{font:14px system-ui;margin:3em auto;max-width:34em;color:#222}"
                        + "a.btn{display:inline-block;padding:.6em 1.1em;background:#DB612F;color:#fff;"
                        + "text-decoration:none;border-radius:4px}</style></head><body>"
                        + "<h1>OpenTRAP proxy</h1>"
                        + "<p>The proxy is running on port " + cfg.port + ".</p>"
                        + "<p>To intercept TLS traffic, install this certificate authority "
                        + "and trust it for identifying websites.</p>"
                        + "<p><a class=\"btn\" href=\"/cert/download\">Download CA certificate</a></p>"
                        + "<pre style=\"white-space:pre-wrap;font-size:11px;color:#666\">"
                        + ca.caCertificatePem() + "</pre></body></html>";
                byte[] b = Bytes.bytes(body);
                String head = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\n"
                        + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n";
                out.write(Bytes.bytes(head));
                out.write(b);
            }
            out.flush();
        }

        private void writeError(OutputStream out, String message) {
            try {
                String body = "<html><body><h2>OpenTRAP proxy error</h2><p>"
                        + message.replace("<", "&lt;") + "</p></body></html>";
                byte[] b = Bytes.bytes(body);
                out.write(Bytes.bytes("HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/html\r\n"
                        + "Content-Length: " + b.length + "\r\nConnection: close\r\n\r\n"));
                out.write(b);
                out.flush();
            } catch (IOException ignored) {}
        }
    }

    private void applyResponseModification(HttpMessage response) {
        if (!unhideHiddenFields && !enableDisabledFields && !removeLengthLimits
                && !removeJsValidation && !removeAllJavaScript && !removeObjectTags) return;
        if (!"HTML".equals(response.mimeType())) return;
        String b = response.bodyText();
        if (unhideHiddenFields)
            b = b.replaceAll("(?i)type\\s*=\\s*([\"']?)hidden\\1", "type=$1text$1");
        if (enableDisabledFields)
            b = b.replaceAll("(?i)\\s+disabled(\\s*=\\s*[\"']?[^\"'>\\s]*[\"']?)?", "");
        if (removeLengthLimits)
            b = b.replaceAll("(?i)\\s+maxlength\\s*=\\s*[\"']?\\d+[\"']?", "");
        if (removeJsValidation)
            b = b.replaceAll("(?i)\\s+onsubmit\\s*=\\s*\"[^\"]*\"", "");
        if (removeObjectTags)
            b = b.replaceAll("(?is)<object.*?</object>", "").replaceAll("(?i)<embed[^>]*>", "");
        if (removeAllJavaScript) {
            b = b.replaceAll("(?is)<script.*?</script>", "");
            b = b.replaceAll("(?i)\\s+on[a-z]+\\s*=\\s*\"[^\"]*\"", "");
        }
        response.setBody(Bytes.bytes(b));
        response.setHeader("Content-Length", String.valueOf(response.body().length));
    }

    /**
     * Response headers whose only purpose is to make the browser refuse to run
     * or display attacker-influenced content. Stripping them lets a proof of
     * concept fire in a current browser, which is how you demonstrate a
     * reflected cross-site scripting or a clickjacking flaw to the team that
     * has to fix it on the server.
     *
     * <p>The browser's own cross-site-scripting auditor was removed from
     * Chromium in 2019, so for a reflected payload it is the target's
     * Content-Security-Policy that stands in the way, not the browser; removing
     * the header here is what reproduces how a legacy browser, or any browser
     * before the policy was added, would have run the payload.
     *
     * <p>This changes only what the browser receives. The scanner audits the
     * server's original response, so a header removed here is still reported as
     * present, and a header the server never sent is still reported as missing.
     */
    private static final String[] SECURITY_RESPONSE_HEADERS = {
        "Content-Security-Policy",
        "Content-Security-Policy-Report-Only",
        "X-Frame-Options",
        "X-XSS-Protection",
        "Cross-Origin-Opener-Policy",
        "Cross-Origin-Embedder-Policy",
        "Cross-Origin-Resource-Policy",
        "X-WebKit-CSP",
    };

    private void stripSecurityResponseHeaders(HttpMessage response) {
        if (!stripSecurityHeaders) return;
        for (String h : SECURITY_RESPONSE_HEADERS) response.removeHeader(h);
        // Deliberately no change to the CORS headers. A genuine cross-origin
        // misconfiguration is exploitable in a normal browser against the
        // server's real Access-Control-Allow-Origin and -Allow-Credentials
        // headers, so forging those here would only manufacture a finding the
        // target does not actually have.
    }

    private static HttpService parseHostHeader(String hostHeader, boolean secure) {
        String h = hostHeader.trim();
        int port = secure ? 443 : 80;
        int c = h.lastIndexOf(':');
        if (c > 0 && c > h.lastIndexOf(']')) {
            try { port = Integer.parseInt(h.substring(c + 1).trim()); h = h.substring(0, c); }
            catch (Exception ignored) {}
        }
        return new HttpService(h, port, secure);
    }

    /** Read bytes up to and including the header terminator. */
    /**
     * Record an exchange, HTTP or WebSocket handshake alike. With no scope
     * defined nothing is out of scope, so everything is recorded whatever the
     * out-of-scope setting says.
     */
    private void logEntry(LogEntry entry, HttpService svc, HttpMessage request) {
        boolean inScope = ctx.scope().isEmpty() || ctx.scope().isInScope(svc, request.path());
        if (!inScope && !logOutOfScope) return;
        ctx.store().add(entry);
        ctx.siteMapChanged(entry);
        if (observer != null) observer.exchangeLogged(entry);
    }

    private static boolean isWebSocketUpgrade(HttpMessage request) {
        String up = request.header("Upgrade");
        String con = request.header("Connection");
        return up != null && up.toLowerCase(java.util.Locale.ROOT).contains("websocket")
                && con != null && con.toLowerCase(java.util.Locale.ROOT).contains("upgrade");
    }

    /**
     * Relay an upgraded WebSocket: forward the handshake to the upstream server,
     * return its 101 to the browser, then stream frames both ways through the
     * {@link WebSocketConnection} relay until either side closes.
     */
    private void handleWebSocket(HttpMessage request, InputStream clientIn, OutputStream clientOut, HttpService svc) {
        java.net.Socket up = null;
        try {
            up = HttpClient.openSocket(svc, 15000, 0); // no read timeout: WebSockets idle between frames
            OutputStream upOut = up.getOutputStream();
            InputStream upIn = new BufferedInputStream(up.getInputStream(), 32768);
            upOut.write(request.raw());
            upOut.flush();

            byte[] respHead = readHead(upIn);
            if (respHead.length == 0) return;
            HttpMessage resp = new HttpMessage(respHead);
            clientOut.write(resp.raw());
            clientOut.flush();

            LogEntry entry = new LogEntry("proxy", svc, request);
            entry.setResponse(resp);
            logEntry(entry, svc, request);   // same scope rule as every other exchange

            if (resp.statusCode() != 101) return; // server declined the upgrade
            ctx.alert("proxy", "WebSocket connected to " + svc.host());
            new WebSocketConnection(ctx, svc, clientOut, upOut, wsMatchReplace).run(clientIn, upIn);
        } catch (Exception e) {
            ctx.alert("proxy", "WebSocket relay error: " + e.getMessage());
        } finally {
            if (up != null) try { up.close(); } catch (Exception ignored) {}
        }
    }

    private static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int consecutive = 0;
        while (true) {
            int b;
            try { b = in.read(); } catch (SocketTimeoutException e) { return new byte[0]; }
            if (b < 0) break;
            buf.write(b);
            if (b == 10) {
                consecutive++;
                if (consecutive == 2) break;
            } else if (b != 13) {
                consecutive = 0;
            }
            if (buf.size() > 1024 * 1024) break;
        }
        return buf.toByteArray();
    }

    /** Read a request body framed by Content-Length or chunked encoding.
     *  A size claimed in the framing is never trusted for allocation: the
     *  body is streamed through a small buffer, so a bogus Content-Length or
     *  chunk header from a client costs nothing beyond the bytes that
     *  actually arrive. */
    private static byte[] readBody(InputStream in, HttpMessage head) throws IOException {
        String te = head.header("Transfer-Encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (true) {
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                int c;
                while ((c = in.read()) >= 0 && c != 10) line.write(c);
                String sz = Bytes.str(line.toByteArray()).trim();
                int semi = sz.indexOf(';');
                if (semi >= 0) sz = sz.substring(0, semi);
                int n;
                try { n = Integer.parseInt(sz.trim(), 16); } catch (Exception e) { break; }
                if (n < 0) break;
                out.write(Bytes.bytes(Integer.toHexString(n) + "\r\n"));
                if (n == 0) { out.write(Bytes.bytes("\r\n")); break; }
                int remaining = n;
                while (remaining > 0) {
                    int k = in.read(buf, 0, Math.min(buf.length, remaining));
                    if (k < 0) break;
                    out.write(buf, 0, k);
                    remaining -= k;
                }
                out.write(Bytes.bytes("\r\n"));
                in.read(); in.read();
            }
            return out.toByteArray();
        }
        String cl = head.header("Content-Length");
        if (cl == null) return new byte[0];
        int n;
        try { n = Integer.parseInt(cl.trim()); } catch (Exception e) { return new byte[0]; }
        if (n <= 0) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int remaining = n;
        while (remaining > 0) {
            int k = in.read(buf, 0, Math.min(buf.length, remaining));
            if (k < 0) break;
            out.write(buf, 0, k);
            remaining -= k;
        }
        return out.toByteArray();
    }
}
