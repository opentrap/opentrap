package opentrap.intruder;

import opentrap.core.*;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Runs one Intruder attack on a bounded thread pool. It expands the payload
 * sets into request combinations according to the attack type, substitutes
 * them into the marked request, sends each, records the exchange into history
 * and feeds the result to the {@link ResultsWindow}.
 */
public class AttackEngine {

    /** Upper bound on the number of requests a single attack will issue. */
    static final int MAX_REQUESTS = 50_000;

    private final SuiteContext ctx;
    private final HttpService svc;
    private final String markedText;
    private final AttackType type;
    private final int positions;
    private final List<List<String>> payloadsPerSet;
    private final List<String> grep;
    private final int threads;
    private final boolean followRedirects;
    private final long throttleMs;
    private final HttpMode mode;
    private final ResultsWindow window;

    private volatile ExecutorService pool;
    private volatile boolean stopped;
    private volatile boolean paused;

    public AttackEngine(SuiteContext ctx, HttpService svc, String markedText, AttackType type,
                        int positions, List<List<String>> payloadsPerSet, List<String> grep,
                        int threads, boolean followRedirects, long throttleMs, HttpMode mode,
                        ResultsWindow window) {
        this.ctx = ctx;
        this.svc = svc;
        this.markedText = markedText;
        this.type = type;
        this.positions = positions;
        this.payloadsPerSet = payloadsPerSet;
        this.grep = grep;
        this.threads = Math.max(1, threads);
        this.followRedirects = followRedirects;
        this.throttleMs = Math.max(0, throttleMs);
        this.mode = mode == null ? HttpMode.HTTP_1 : mode;
        this.window = window;
    }

    /** Pause or resume the attack; running threads wait between requests while paused. */
    public void setPaused(boolean p) { this.paused = p; }
    public boolean isPaused() { return paused; }

    /** One request: the per-position payloads (null = keep base) and a label. */
    private record Combo(String[] values, String label) {}

    public void start() {
        List<Combo> combos = buildCombos();
        window.setTotal(combos.size());

        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "intruder-attack");
            t.setDaemon(true);
            return t;
        };
        pool = Executors.newFixedThreadPool(threads, tf);
        AtomicInteger counter = new AtomicInteger(0);
        AtomicInteger done = new AtomicInteger(0);
        int total = combos.size();

        for (Combo combo : combos) {
            pool.submit(() -> {
                try {
                    if (stopped || Thread.currentThread().isInterrupted()) return;
                    int number = counter.incrementAndGet();
                    runOne(number, combo);
                } catch (Exception e) {
                    // The Future is discarded, so without this the failure is
                    // invisible and the done counter below never completes.
                    Debug.log("intruder request: " + e);
                } finally {
                    // Counted on every path: an exception or an early stop must
                    // not leave the window stuck short of "finished".
                    if (done.incrementAndGet() == total) finish();
                }
            });
        }
        if (total == 0) finish();
        pool.shutdown();
    }

    /** Stop the attack and release the pool. */
    public void stop() {
        stopped = true;
        if (pool != null) pool.shutdownNow();
    }

    private void finish() { window.finished(); }

    private void runOne(int number, Combo combo) {
        // Honour pause (and throttle) before issuing the request.
        while (paused && !stopped && !Thread.currentThread().isInterrupted()) {
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
        }
        if (stopped) return;
        if (throttleMs > 0) { try { Thread.sleep(throttleMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; } }

        String substituted = substitute(markedText, combo.values());
        HttpMessage req = new HttpMessage(Bytes.bytes(substituted));
        req.updateContentLength();

        HttpClient.Options opt = new HttpClient.Options();
        opt.updateContentLength = false;   // already applied above
        opt.unpackGzip = true;
        opt.followRedirects = followRedirects;
        opt.session = ctx.sessions();
        opt.sessionTool = "intruder";
        opt.mode = mode;

        long t0 = System.currentTimeMillis();
        HttpClient.Result r = new HttpClient().send(svc, req, opt);
        long ms = System.currentTimeMillis() - t0;

        HttpMessage resp = r.ok() ? r.response : null;
        int status = resp != null ? resp.statusCode() : 0;
        int length = resp != null ? resp.length() : 0;
        String error = r.ok() ? null : r.error;
        boolean[] hits = grepHits(resp);

        window.addResult(new ResultsWindow.Result(number, combo.label(), svc, req, resp,
                status, length, ms, error, hits));
        record(req, resp, r.remoteIp, ms, error);
    }

    private boolean[] grepHits(HttpMessage resp) {
        boolean[] hits = new boolean[grep.size()];
        if (resp == null) return hits;
        String body = Bytes.str(resp.raw()).toLowerCase();
        for (int i = 0; i < grep.size(); i++) {
            hits[i] = body.contains(grep.get(i).toLowerCase());
        }
        return hits;
    }

    private void record(HttpMessage req, HttpMessage resp, String remoteIp, long ms, String error) {
        SwingUtilities.invokeLater(() -> {
            LogEntry entry = new LogEntry("intruder", svc, req);
            if (resp != null) entry.setResponse(resp);
            if (error != null) entry.setError(error);
            if (remoteIp != null) entry.setRemoteIp(remoteIp);
            entry.setDurationMillis(ms);
            ctx.store().add(entry);
            ctx.siteMapChanged(entry);
        });
    }

    // ---------------- combination expansion ----------------

    private List<Combo> buildCombos() {
        List<Combo> out = new ArrayList<>();
        if (positions <= 0 || payloadsPerSet.isEmpty()) return out;

        switch (type) {
            case SNIPER -> {
                List<String> set = payloadsPerSet.get(0);
                for (int p = 0; p < positions && out.size() < MAX_REQUESTS; p++) {
                    for (String payload : set) {
                        if (out.size() >= MAX_REQUESTS) break;
                        String[] values = new String[positions];
                        values[p] = payload;
                        out.add(new Combo(values, payload));
                    }
                }
            }
            case BATTERING_RAM -> {
                for (String payload : payloadsPerSet.get(0)) {
                    if (out.size() >= MAX_REQUESTS) break;
                    String[] values = new String[positions];
                    for (int p = 0; p < positions; p++) values[p] = payload;
                    out.add(new Combo(values, payload));
                }
            }
            case PITCHFORK -> {
                int n = Integer.MAX_VALUE;
                for (int p = 0; p < positions; p++) n = Math.min(n, set(p).size());
                if (n == Integer.MAX_VALUE) n = 0;
                for (int i = 0; i < n && out.size() < MAX_REQUESTS; i++) {
                    String[] values = new String[positions];
                    for (int p = 0; p < positions; p++) values[p] = set(p).get(i);
                    out.add(new Combo(values, String.join(", ", values)));
                }
            }
            case CLUSTER_BOMB -> cluster(out);
        }
        return out;
    }

    private void cluster(List<Combo> out) {
        int[] idx = new int[positions];
        for (int p = 0; p < positions; p++) if (set(p).isEmpty()) return;
        while (out.size() < MAX_REQUESTS) {
            String[] values = new String[positions];
            for (int p = 0; p < positions; p++) values[p] = set(p).get(idx[p]);
            out.add(new Combo(values, String.join(", ", values)));
            // increment the odometer
            int p = positions - 1;
            while (p >= 0) {
                if (++idx[p] < set(p).size()) break;
                idx[p] = 0;
                p--;
            }
            if (p < 0) break;
        }
    }

    private List<String> set(int p) {
        return p < payloadsPerSet.size() ? payloadsPerSet.get(p) : List.of();
    }

    /** Replace each {@code §…§} region with its payload, dropping all markers. */
    static String substitute(String marked, String[] values) {
        StringBuilder out = new StringBuilder(marked.length());
        int pos = 0, i = 0;
        while (i < marked.length()) {
            char c = marked.charAt(i);
            if (c == PositionsPanel.MARK) {
                int end = marked.indexOf(PositionsPanel.MARK, i + 1);
                if (end < 0) { out.append(marked, i + 1, marked.length()); break; }
                String base = marked.substring(i + 1, end);
                String v = (pos < values.length && values[pos] != null) ? values[pos] : base;
                out.append(v);
                pos++;
                i = end + 1;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
