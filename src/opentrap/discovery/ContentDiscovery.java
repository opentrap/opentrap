package opentrap.discovery;

import opentrap.core.*;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Forced browse: request each candidate path and report the ones that exist.
 * Hits flow into the shared store and site map like any other traffic, and the
 * session hook applies so discovery works against an authenticated target.
 */
public final class ContentDiscovery {

    public interface Listener {
        void hit(String path, int status, int length);
        void progress(int done, int total);
        void finished();
    }

    private final SuiteContext ctx;
    private volatile ExecutorService pool;
    private volatile boolean stopped;

    public ContentDiscovery(SuiteContext ctx) { this.ctx = ctx; }

    public void stop() {
        stopped = true;
        if (pool != null) pool.shutdownNow();
    }

    /** A response exists for discovery purposes when it is not a 404 or an error. */
    public static boolean isHit(int status) { return status > 0 && status != 404; }

    public void start(HttpService base, List<String> paths, int threads, long throttleMs, Listener l) {
        stopped = false;
        int total = paths.size();
        l.progress(0, total);
        ThreadFactory tf = r -> { Thread t = new Thread(r, "discovery"); t.setDaemon(true); return t; };
        pool = Executors.newFixedThreadPool(Math.max(1, threads), tf);
        AtomicInteger done = new AtomicInteger(0);
        for (String path : paths) {
            pool.submit(() -> {
                if (stopped || Thread.currentThread().isInterrupted()) return;
                if (throttleMs > 0) { try { Thread.sleep(throttleMs); } catch (InterruptedException e) { return; } }
                probe(base, path, l);
                int d = done.incrementAndGet();
                l.progress(d, total);
                if (d == total) l.finished();
            });
        }
        if (total == 0) l.finished();
        pool.shutdown();
    }

    private void probe(HttpService base, String path, Listener l) {
        HttpMessage req = new HttpMessage(Bytes.bytes(
                "GET " + path + " HTTP/1.1\r\nHost: " + hostHeader(base)
                        + "\r\nUser-Agent: OpenTRAP-discovery\r\nAccept: */*\r\nConnection: close\r\n\r\n"));
        HttpClient.Options opt = new HttpClient.Options();
        opt.followRedirects = false;
        opt.unpackGzip = true;
        if (ctx != null) { opt.session = ctx.sessions(); opt.sessionTool = "discovery"; }
        HttpClient.Result r = new HttpClient().send(base, req, opt);
        if (!r.ok()) return;
        int status = r.response.statusCode();
        if (!isHit(status)) return;

        if (ctx != null) {
            LogEntry e = new LogEntry("discovery", base, req);
            e.setResponse(r.response);
            e.setRemoteIp(r.remoteIp);
            ctx.store().add(e);
            ctx.siteMapChanged(e);
        }
        l.hit(path, status, r.response.length());
    }

    private static String hostHeader(HttpService svc) {
        return svc.defaultPort() ? svc.host() : svc.host() + ":" + svc.port();
    }
}
