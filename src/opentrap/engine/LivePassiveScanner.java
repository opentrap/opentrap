package opentrap.engine;

import opentrap.core.HttpService;
import opentrap.core.Issue;
import opentrap.core.SuiteContext;
import opentrap.core.UpstreamProxy;
import opentrap.spider.Cdp.JsonValue;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live passive scanning of proxy traffic, Burp-style. When enabled, in-scope
 * requests are routed through the ZAP engine's own proxy, which passively scans
 * everything that flows through it; a background poller turns ZAP's alerts into
 * OpenTRAP issues. Routing is fail-safe: if the engine is not reachable the proxy
 * falls back to a direct connection, so browsing is never broken.
 */
public final class LivePassiveScanner {

    private final SuiteContext ctx;
    private volatile boolean enabled;
    private volatile ZapEngine engine;
    private volatile UpstreamProxy zapProxy;
    private volatile Thread poller;
    private final Set<String> seen = ConcurrentHashMap.newKeySet();

    public LivePassiveScanner(SuiteContext ctx) { this.ctx = ctx; }

    public boolean isEnabled() { return enabled; }

    /** The upstream to route a request through, or null for a direct connection. */
    public UpstreamProxy routeFor(HttpService svc) {
        if (!enabled || svc == null) return null;
        UpstreamProxy p = zapProxy;
        if (p == null) return null;
        try {
            if (!ctx.scope().isEmpty() && !ctx.scope().isInScope(svc, "/")) return null;
        } catch (Exception ignored) {}
        return p;
    }

    /** Turn live passive scanning on or off. Starts the engine lazily on first enable. */
    public synchronized void setEnabled(boolean on) {
        enabled = on;
        if (on && zapProxy == null) startEngine();
    }

    private void startEngine() {
        Thread t = new Thread(() -> {
            ZapEngine ze = ZapEngine.shared();
            if (ze == null || !ze.available()) {
                ctx.alert("scanner", "Live passive scanning needs the scan engine (run ./fetch-zap.sh)");
                enabled = false;
                return;
            }
            try {
                ze.start();
                engine = ze;
                URI u = URI.create(ze.base());   // http://127.0.0.1:<port>
                UpstreamProxy p = new UpstreamProxy();
                p.type = UpstreamProxy.Type.HTTP;
                p.host = u.getHost();
                p.port = u.getPort();
                zapProxy = p;
                ctx.alert("scanner", "Live passive scanning is active (traffic routed through the engine)");
                startPoller();
            } catch (Exception e) {
                ctx.alert("scanner", "Could not start live passive scanning: " + e.getMessage());
                enabled = false;
            }
        }, "live-passive-start");
        t.setDaemon(true);
        t.start();
    }

    private synchronized void startPoller() {
        if (poller != null) return;
        poller = new Thread(() -> {
            while (true) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { return; }
                ZapEngine ze = engine;
                if (ze == null || ze.api() == null) continue;
                try {
                    for (JsonValue a : ze.api().alerts("", 0, 100000)) {
                        String id = ZapApi.str(a, "id");
                        if (id != null && !seen.add(id)) continue;   // already mapped
                        Issue is = ZapScanner.mapAlert(ze.api(), a);
                        if (is != null) { is.setSource("Live audit"); ctx.addIssue(is); }
                    }
                } catch (Exception ignored) {}
            }
        }, "live-passive-poll");
        poller.setDaemon(true);
        poller.start();
    }
}
