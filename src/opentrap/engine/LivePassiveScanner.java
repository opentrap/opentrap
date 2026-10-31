package opentrap.engine;

import opentrap.core.Debug;
import opentrap.core.HttpService;
import opentrap.core.Issue;
import opentrap.core.SuiteContext;
import opentrap.core.UpstreamProxy;
import opentrap.spider.Cdp.JsonValue;

import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live passive scanning of proxy traffic. When enabled, in-scope
 * requests are routed through the ZAP engine's own proxy, which passively scans
 * everything that flows through it; a background poller turns ZAP's alerts into
 * OpenTRAP issues. Routing is fail-safe: if the engine is not reachable the proxy
 * falls back to a direct connection, so browsing is never broken.
 */
public final class LivePassiveScanner {

    /** The source every finding the live poller raises carries. */
    public static final String SOURCE = "Live audit";

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
        // Disabling stops the poller: without this it kept polling all engine
        // alerts every 3 s for the rest of the session, whether live scanning
        // was on or not.
        if (!on) stopPoller();
    }

    private void stopPoller() {
        Thread p = poller;
        poller = null;
        if (p != null) p.interrupt();
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
            while (enabled) {
                try { Thread.sleep(3000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                ZapEngine ze = engine;
                if (ze == null || ze.api() == null) continue;
                try {
                    // One cache per poll: findings the engine raised on one
                    // message share its evidence, and the cache never outlives
                    // the listing it was built from.
                    java.util.Map<String, ZapScanner.Evidence> evidence = new java.util.HashMap<>();
                    for (JsonValue a : ze.api().alerts("", 0, 100000)) {
                        String id = ZapApi.str(a, "id");
                        if (id != null && !seen.add(id)) continue;   // already mapped
                        Issue is = ZapScanner.mapAlert(ze.api(), a, evidence);
                        if (is != null) { is.setSource(SOURCE); ctx.addIssue(is); }
                    }
                } catch (Exception e) {
                    // A dead or restarting engine must not kill the poller, but
                    // the failure is traced rather than dropped: with the loop
                    // running every 3 s, a permanently failing poll is the only
                    // sign the live audit quietly stopped finding anything.
                    Debug.log("live passive scan poll failed: " + e);
                }
            }
        }, "live-passive-poll");
        poller.setDaemon(true);
        poller.start();
    }
}
