package opentrap.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import opentrap.core.Bytes;
import opentrap.core.Debug;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.Issue;
import opentrap.core.LogEntry;
import opentrap.core.ScanTask;
import opentrap.core.SuiteContext;
import opentrap.spider.Cdp.JsonValue;

/**
 * Drives a crawl-then-audit against the ZAP engine and maps everything it finds
 * into OpenTRAP's own model: crawled traffic into the site map and live passive
 * audit, and every ZAP alert into an OpenTRAP {@link Issue} in the issue tree.
 *
 * <p>The ZAP engine does the detecting; this class is only the bridge, nothing of
 * ZAP's data model leaks into OpenTRAP beyond what is translated here.
 */
public final class ZapScanner {

    /** The engine context that mirrors the suite's target scope. */
    public static final String SCOPE_CONTEXT = "OpenTRAP scope";

    private final SuiteContext ctx;
    private final ZapApi api;
    private final java.util.concurrent.atomic.AtomicInteger imported;

    /** Drive the shared engine; the message-history cursor is the engine's. */
    public ZapScanner(SuiteContext ctx, ZapEngine engine) {
        this(ctx, engine.api(), engine.importedMessages());
    }

    public ZapScanner(SuiteContext ctx, ZapApi api) {
        this(ctx, api, new java.util.concurrent.atomic.AtomicInteger());
    }

    private ZapScanner(SuiteContext ctx, ZapApi api, java.util.concurrent.atomic.AtomicInteger imported) {
        this.ctx = ctx;
        this.api = api;
        this.imported = imported;
    }

    /**
     * Hand the suite's target scope to the engine as a context, rebuilt from
     * the current rules, so the crawl and the audit are bound by the same
     * includes and excludes as every other tool. Returns the context id.
     * Fails rather than falling back: a scan that cannot be bound to the
     * scope must not run unbounded.
     */
    private String applyScope(java.util.List<Seed> seeds, ScanTask task) throws Exception {
        try { api.removeContext(SCOPE_CONTEXT); } catch (Exception ignore) { /* none yet */ }
        String id = api.newContext(SCOPE_CONTEXT);
        if (id == null || id.isBlank()) throw new Exception("the engine did not create the scope context");
        int inc = 0, exc = 0;
        for (opentrap.core.Scope.Rule r : ctx.scope().include()) {
            String re = r.urlRegex();
            if (re != null) { api.includeInContext(SCOPE_CONTEXT, re); inc++; }
        }
        if (inc == 0) {
            // No usable include rule: bind the scan to the targets themselves.
            for (Seed seed : seeds) {
                opentrap.core.Scope.Rule r = new opentrap.core.Scope.Rule();
                r.prefix = seed.service().host().toLowerCase(java.util.Locale.ROOT);
                String re = r.urlRegex();
                if (re != null) { api.includeInContext(SCOPE_CONTEXT, re); inc++; }
            }
        }
        for (opentrap.core.Scope.Rule r : ctx.scope().exclude()) {
            String re = r.urlRegex();
            if (re != null) { api.excludeFromContext(SCOPE_CONTEXT, re); exc++; }
        }
        api.setContextInScope(SCOPE_CONTEXT, true);
        task.log("Scope applied to the engine: " + inc + " include rule(s), " + exc + " exclude rule(s)");
        return id;
    }

    /** One seed to crawl: the target service and the full seed URL. */
    public record Seed(HttpService service, String url) {}

    /** Single-seed convenience (the Spider button), delegating to the list form. */
    public void crawlAndAudit(HttpService seed, String seedUrl, boolean thenAudit,
                              ScanConfig cfg, ScanTask crawlTask) throws Exception {
        crawlAndAudit(java.util.List.of(new Seed(seed, seedUrl)), thenAudit, cfg, crawlTask);
    }

    /**
     * Crawl every seed, then (when {@code thenAudit}) audit what was found, all
     * as one job: one crawl task and one audit task. The seeds are processed one
     * at a time on this single thread, so the configured thread count applies
     * once to the engine rather than being multiplied across targets.
     *
     * @param crawlTask a LIVE_ACTIVE task to report crawl progress on
     */
    public void crawlAndAudit(java.util.List<Seed> seeds, boolean thenAudit,
                              ScanConfig cfg, ScanTask crawlTask) throws Exception {
        // ---- crawl each seed into the one task ---------------------------
        try {
            api.setSpiderMaxDepth(cfg.crawlDepth());
            api.setSpiderMaxDuration(cfg.crawlDurationMin());
            api.setSpiderProcessForms(cfg.submitForms());
        } catch (Exception ignore) { /* use the engine defaults */ }

        // Everything the engine does in this job stays inside the suite scope.
        final String contextId = applyScope(seeds, crawlTask);
        ctx.taskChanged(crawlTask);

        int lastLoc = -1;
        for (Seed seed : seeds) {
            if (crawlTask.cancelled()) break;
            if (seeds.size() > 1) { crawlTask.log("Crawling " + seed.url()); ctx.taskChanged(crawlTask); }
            api.accessUrl(seed.url());
            // 0 = no per-node child limit: crawl every in-scope link within the
            // depth budget, instead of stopping after 20 links per page.
            final String sid;
            try {
                sid = api.spiderScan(seed.url(), 0, SCOPE_CONTEXT);
            } catch (Exception e) {
                crawlTask.log("The engine would not crawl " + seed.url() + ": " + e.getMessage());
                ctx.taskChanged(crawlTask);
                continue;
            }
            if (sid == null || sid.isBlank()) {
                crawlTask.log("The engine gave no crawl id for " + seed.url());
                continue;
            }
            boolean zapPaused = false;
            while (true) {
                // Stop the moment the task is deleted, so nothing keeps crawling.
                if (crawlTask.cancelled()) { stopQuietly(() -> api.spiderStop(sid)); break; }
                // Honour a paused task by pausing the engine's spider too.
                if (crawlTask.state() == ScanTask.State.PAUSED) {
                    if (!zapPaused) { stopQuietly(() -> api.spiderPause(sid)); zapPaused = true; }
                    Thread.sleep(500);
                    continue;
                }
                if (zapPaused) { stopQuietly(() -> api.spiderResume(sid)); zapPaused = false; }
                int st = api.spiderStatus(sid);
                drain("crawl", true, crawlTask);
                int loc = totalLocations(seeds);
                if (loc >= 0 && loc != lastLoc) {
                    crawlTask.setDiscoveredLocations(loc);
                    crawlTask.log("Discovered " + loc + " location(s)");
                    lastLoc = loc;
                }
                ctx.taskChanged(crawlTask);
                if (st >= 100) break;
                Thread.sleep(1000);
            }
            drain("crawl", true, crawlTask);
            // AJAX crawl (headless Chromium) for JavaScript-rendered routes.
            if (cfg.ajaxSpider() && !crawlTask.cancelled()) runAjaxCrawl(seed.url(), contextId, cfg, crawlTask);
        }

        drain("crawl", true, crawlTask);
        crawlTask.log(crawlTask.cancelled()
                ? "Crawl stopped"
                : "Crawl finished: " + crawlTask.requests() + " request(s)");
        crawlTask.setState(ScanTask.State.FINISHED);
        ctx.taskChanged(crawlTask);

        // A deleted crawl does not go on to start an audit.
        if (crawlTask.cancelled() || !thenAudit) return;

        // ---- audit: one task over every seed -----------------------------
        ScanTask auditTask = new ScanTask(ScanTask.Type.AUDIT,
                "Audit of " + targetLabel(seeds), "Audit - " + cfg.name());
        auditTask.setScope(seeds.size() == 1 ? seeds.get(0).service().url() : seeds.size() + " targets");
        auditTask.setDiscoveredLocations(crawlTask.discoveredLocations());
        try {
            int rules = 0;
            for (JsonValue s : api.activeScanners())
                if ("true".equals(ZapApi.str(s, "enabled"))) rules++;
            auditTask.setAuditItemsTotal(rules);
        } catch (Exception ignore) { /* leave at 0 if ZAP cannot report it */ }
        ctx.addTask(auditTask);
        try {
            // Apply the tier's audit settings once before starting.
            try {
                api.setAllAttackStrength(cfg.attackStrength());
                api.setAllAlertThreshold(cfg.alertThreshold());
                api.setInjectableParams(cfg.injectableMask());
                api.setInjectableRpc(cfg.rpcMask());
                api.setActiveDelayMs(cfg.delayMs());
                api.setActiveMaxScanDuration(cfg.scanDurationMin());
            } catch (Exception ignore) { /* use the engine defaults */ }
            auditTask.log("Auditing with the ZAP active rules (" + cfg.name()
                    + ": strength " + cfg.attackStrength() + ", threshold " + cfg.alertThreshold() + ")...");
            ctx.taskChanged(auditTask);

            Map<String, Issue.Severity> seen = new HashMap<>();
            int doneSeeds = 0;
            for (Seed seed : seeds) {
                if (auditTask.cancelled()) break;
                // The audit is bound to the scope context: only what the crawl
                // found inside it is attacked. Nothing crawled for this seed, or
                // a seed the scope no longer covers, is skipped with a note
                // rather than driving the loop with an id the engine rejected.
                String started;
                try {
                    started = api.ascanScan(seed.url(), contextId);
                } catch (Exception e) {
                    auditTask.log("Nothing to audit for " + seed.service().host() + ": " + e.getMessage());
                    ctx.taskChanged(auditTask);
                    doneSeeds++;
                    continue;
                }
                final String aid = started;
                if (aid == null || aid.isBlank()) {
                    auditTask.log("Nothing to audit for " + seed.service().host()
                            + (crawlTask.discoveredLocations() <= 0 ? " (no crawlable content found)" : ""));
                    doneSeeds++;
                    continue;
                }
                boolean auditPaused = false;
                while (true) {
                    if (auditTask.cancelled()) { stopQuietly(() -> api.ascanStop(aid)); break; }
                    if (auditTask.state() == ScanTask.State.PAUSED) {
                        if (!auditPaused) { stopQuietly(() -> api.ascanPause(aid)); auditPaused = true; }
                        Thread.sleep(500);
                        continue;
                    }
                    if (auditPaused) { stopQuietly(() -> api.ascanResume(aid)); auditPaused = false; }
                    int st = api.ascanStatus(aid);
                    // Overall progress blends finished seeds with the current one.
                    auditTask.setItemsTotal(100);
                    auditTask.setItemsDone(Math.min(100, (doneSeeds * 100 + Math.max(0, st)) / seeds.size()));
                    pullAlerts(seed.service().url(), auditTask, seen);
                    drain("audit", false, auditTask);
                    ctx.taskChanged(auditTask);
                    if (st >= 100) break;
                    Thread.sleep(2000);
                }
                pullAlerts(seed.service().url(), auditTask, seen);
                drain("audit", false, auditTask);
                doneSeeds++;
            }
            auditTask.log(auditTask.cancelled()
                    ? "Audit stopped"
                    : "Audit finished: " + seen.size() + " finding(s)");
        } finally {
            auditTask.setState(ScanTask.State.FINISHED);
            ctx.taskChanged(auditTask);
        }
    }

    /** Short label for a job that may cover several targets. */
    private static String targetLabel(java.util.List<Seed> seeds) {
        if (seeds.isEmpty()) return "targets";
        String first = seeds.get(0).service().host();
        return seeds.size() == 1 ? first : first + " +" + (seeds.size() - 1) + " more";
    }

    /** Unique discovered locations across every seed host, or -1 if none known. */
    private int totalLocations(java.util.List<Seed> seeds) {
        int total = 0; boolean any = false;
        for (Seed seed : seeds) {
            int n = locations(seed.service().url());
            if (n >= 0) { total += n; any = true; }
        }
        return any ? total : -1;
    }

    /** Count issues this task has reported, across every target. */
    /**
     * Run ZAP's AJAX spider (a real headless Chromium) over the seed, so that
     * JavaScript-rendered links and routes are discovered and fed to the audit.
     * Bounded by a time budget; absence of the add-on or a browser is not fatal.
     */
    private void runAjaxCrawl(String seedUrl, String contextId, ScanConfig cfg, ScanTask crawlTask) {
        try {
            api.setAjaxBrowser("chrome-headless");
            api.setAjaxBrowsers(2);   // bound the headless-browser pool
            try {
                api.setAjaxMaxDuration(cfg.ajaxMaxDurationMin());
                api.setAjaxMaxCrawlDepth(cfg.ajaxMaxDepth());
                api.setAjaxMaxCrawlStates(cfg.ajaxMaxStates());
            } catch (Exception ignore) { /* use the engine defaults */ }
            crawlTask.log("Starting AJAX crawl (headless Chromium, executes JavaScript)...");
            ctx.taskChanged(crawlTask);
            // Bound to the scope context like the traditional crawl.
            api.ajaxSpiderScan(seedUrl, contextId == null ? null : SCOPE_CONTEXT);

            // Local wall-clock backstop mirroring the engine's own time budget, so
            // the loop still ends even if the engine does not stop itself. An
            // unlimited budget (0) gets a generous local cap so a runaway SPA crawl
            // cannot block the scan for ever.
            int budgetMin = cfg.ajaxMaxDurationMin() > 0 ? cfg.ajaxMaxDurationMin() : 60;
            long budgetMs = budgetMin * 60_000L;
            long deadline = System.currentTimeMillis() + budgetMs;
            int lastLoc = -1;
            String baseurl = HttpService.parse(seedUrl).url();
            while ("running".equalsIgnoreCase(api.ajaxSpiderStatus())) {
                // A deleted task stops the AJAX spider (the finally block below).
                if (crawlTask.cancelled()) break;
                drain("crawl", true, crawlTask);
                int loc = locations(baseurl);
                if (loc >= 0 && loc != lastLoc) {
                    crawlTask.setDiscoveredLocations(loc);
                    crawlTask.log("Discovered " + loc + " location(s)");
                    lastLoc = loc;
                }
                ctx.taskChanged(crawlTask);
                if (System.currentTimeMillis() > deadline) {
                    crawlTask.log("AJAX crawl time budget reached; stopping");
                    try { api.ajaxSpiderStop(); } catch (Exception ignore) { }
                    break;
                }
                Thread.sleep(1500);
            }
            drain("crawl", true, crawlTask);
            crawlTask.log("AJAX crawl finished");
        } catch (Exception e) {
            // Add-on missing, no browser/driver, or the engine refused: carry on
            // with whatever the traditional spider found.
            crawlTask.log("AJAX crawl unavailable (" + e.getMessage()
                    + "); using traditional crawl results only");
        } finally {
            // Always stop the AJAX spider so ZAP releases its headless browsers,
            // even if the crawl was interrupted or threw.
            try { api.ajaxSpiderStop(); } catch (Exception ignore) { }
        }
        ctx.taskChanged(crawlTask);
    }

    /** A ZAP control call (stop/pause/resume) whose failure must never abort the
     *  scan loop: the engine may have already finished the scan being stopped. */
    private interface ZapCall { void run() throws Exception; }
    private static void stopQuietly(ZapCall c) {
        try { c.run(); } catch (Exception ignore) { }
    }

    /** Unique discovered locations under baseurl, or the last value on error. */
    private int locations(String baseurl) {
        try { return api.uniqueLocations(baseurl); } catch (Exception e) { return -1; }
    }

    /**
     * Fetch the engine's alerts for the target and add any this audit has not
     * seen to the issue list, then recount the task's badges from what it has
     * seen. The badges count every alert the audit found, whether or not the
     * shared issue list already held the same finding: the live passive audit
     * usually raises the header and disclosure findings first, and an audit
     * that found them too still found them.
     */
    private void pullAlerts(String baseurl, ScanTask task, Map<String, Issue.Severity> seen) throws Exception {
        String source = "Scan " + task.number;
        boolean reattributed = false;
        // One evidence cache for this listing: findings on the same message
        // share its request/response pair instead of copying it per alert.
        java.util.Map<String, Evidence> evidence = new java.util.HashMap<>();
        for (JsonValue a : api.alerts(baseurl, 0, 100000)) {
            String id = ZapApi.str(a, "id");
            if (id == null || seen.containsKey(id)) continue;
            Issue is = mapAlert(api, a, evidence);
            if (is == null) continue;
            seen.put(id, is.severity());
            is.setSource(source);
            if (!ctx.addIssue(is)) {
                // The live passive poller reads every engine alert, including the
                // ones this audit raises, and usually gets there first. A finding
                // the audit made belongs to the audit, so take it over.
                Issue held = ctx.existingIssue(is);
                if (held != null && LivePassiveScanner.SOURCE.equals(held.source())) {
                    held.setSource(source);
                    reattributed = true;
                }
            }
        }
        if (reattributed) ctx.issuesChanged();
        int h = 0, m = 0, l = 0, i = 0;
        for (Issue.Severity sev : seen.values()) {
            switch (sev) {
                case HIGH -> h++;
                case MEDIUM -> m++;
                case LOW -> l++;
                case INFORMATION -> i++;
            }
        }
        task.setCounts(h, m, l, i);
    }

    // ------------------------------------------------- alert -> Issue mapping

    /**
     * Translate one ZAP alert JSON object into an OpenTRAP {@link Issue}. When
     * {@code api} is non-null the alert's HTTP message is fetched and attached.
     */
    public static Issue mapAlert(ZapApi api, JsonValue a) {
        return mapAlert(api, a, new java.util.HashMap<String, Evidence>());
    }

    /**
     * As {@link #mapAlert(ZapApi, JsonValue)}, with the evidence cache of one
     * listing of alerts. One response commonly triggers several passive
     * alerts; with a shared cache those findings hold one request/response
     * pair instead of a copy each, which the project file then writes once
     * and a restored project holds once. The cache must not outlive its
     * listing: message ids belong to one engine process, and a restarted
     * engine reuses them for different messages.
     */
    public static Issue mapAlert(ZapApi api, JsonValue a, java.util.Map<String, Evidence> evidence) {
        return mapAlert(a, evidence, api == null ? null : api::message);
    }

    /** The engine's message records, so the mapping can be driven without an engine. */
    interface MessageSource { JsonValue message(String id) throws Exception; }

    static Issue mapAlert(JsonValue a, java.util.Map<String, Evidence> evidence, MessageSource messages) {
        String name = ZapApi.str(a, "name");
        if (name == null || name.isEmpty()) name = ZapApi.str(a, "alert");
        if (name == null || name.isEmpty()) return null;

        String url = ZapApi.str(a, "url");
        HttpService svc = (url != null) ? HttpService.parse(url) : null;
        if (svc == null) return null;

        Issue.Builder b = Issue.builder(name)
                .severity(severity(ZapApi.str(a, "risk")))
                .confidence(confidence(ZapApi.str(a, "confidence")))
                .service(svc)
                .path(pathOf(url))
                .param(nz(ZapApi.str(a, "param")))
                .detail(detail(a))
                .background(nz(ZapApi.str(a, "other")))
                .remediation(nz(ZapApi.str(a, "solution")))
                .references(nz(ZapApi.str(a, "reference")))
                .evidence(nz(ZapApi.str(a, "evidence")))
                .cwe(toInt(ZapApi.str(a, "cweid")))
                .wasc(toInt(ZapApi.str(a, "wascid")))
                .moduleId(toInt(ZapApi.str(a, "pluginId")))
                .dedupe(Issue.Dedupe.PATH);

        JsonValue tags = a.get("tags");
        if (tags != null && tags.isObject() && !tags.keys().isEmpty()) {
            b.tags(new ArrayList<>(tags.keys()));
        }

        if (messages != null) {
            String mid = ZapApi.str(a, "messageId");
            if (mid != null && !mid.isEmpty()) {
                try { attachMessage(b, mid, evidence, messages); } catch (Exception ignore) { }
            }
        }
        return b.build();
    }

    private static String detail(JsonValue a) {
        StringBuilder sb = new StringBuilder(nz(ZapApi.str(a, "description")));
        String attack = ZapApi.str(a, "attack");
        if (attack != null && !attack.isEmpty()) {
            sb.append("\n\nAttack: ").append(attack);
        }
        return sb.toString();
    }

    /** The request/response pair one engine message contributes as issue evidence. */
    static final class Evidence {
        final HttpMessage request;    // null when the engine holds no request
        final HttpMessage response;   // null when it holds no response
        Evidence(HttpMessage request, HttpMessage response) {
            this.request = request;
            this.response = response;
        }
    }

    private static void attachMessage(Issue.Builder b, String messageId,
            java.util.Map<String, Evidence> evidence, MessageSource messages) throws Exception {
        Evidence e = evidence.get(messageId);
        if (e == null) {
            JsonValue m = messages.message(messageId);
            if (m == null) return;   // nothing attached and nothing cached: a later alert retries
            e = buildEvidence(m);
            evidence.put(messageId, e);
        }
        if (e.request != null) b.request(e.request);
        if (e.response != null) b.response(e.response);
    }

    private static Evidence buildEvidence(JsonValue m) {
        return new Evidence(requestOf(m), responseOf(m));
    }

    /** An engine record's request as our message model, or null when it holds none. */
    private static HttpMessage requestOf(JsonValue m) {
        String h = ZapApi.str(m, "requestHeader");
        if (h == null || h.isEmpty()) return null;
        return new HttpMessage(Bytes.bytes(norm(toOriginForm(h)) + nz(ZapApi.str(m, "requestBody"))));
    }

    /** An engine record's response as our message model, or null when it holds none. */
    private static HttpMessage responseOf(JsonValue m) {
        String h = ZapApi.str(m, "responseHeader");
        if (h == null || h.isEmpty()) return null;
        return new HttpMessage(Bytes.bytes(norm(h) + nz(ZapApi.str(m, "responseBody"))));
    }

    // ------------------------------------------- live traffic record (logger)

    /**
     * Stream every new message the engine has recorded into our store, so the
     * logger holds a complete live record of everything the engine ran. Each
     * record is tagged by what the engine says made it: its crawlers' pages
     * become {@code crawl} entries and populate the site map; its scanner's
     * probes become {@code audit} entries and do not, to keep the site map to
     * real endpoints rather than every attack variant. Traffic the engine only
     * relayed for the proxy is left out, because the proxy has already recorded
     * it. The read position is shared across scans, so nothing is imported
     * twice. Batched so a flood of attack traffic cannot pull thousands of
     * messages in one call.
     *
     * @param phase the tool to count the batch against when a record carries
     *              no type, and the one whose entries count as this task's requests
     */
    private void drain(String phase, boolean toSiteMap, ScanTask task) {
        try {
            int total = api.numberOfMessages();
            int added = 0;
            while (imported.get() < total) {
                int from = imported.get();
                int want = Math.min(total - from, 500);
                java.util.List<JsonValue> batch = api.messages(from, want);
                if (batch.isEmpty()) break;
                for (JsonValue m : batch) {
                    String tool = toolFor(ZapApi.str(m, "type"), phase);
                    if (tool == null) continue;
                    if (importMessage(m, tool, "crawl".equals(tool)) && tool.equals(phase)) added++;
                }
                imported.addAndGet(batch.size());
            }
            if (added > 0 && task != null) task.addRequests(added);
        } catch (Exception e) {
            // The scan itself already finished; this only imports the engine's
            // history into the Logger, so a failure degrades the log, not the
            // scan. Trace it so the history is not quietly incomplete.
            Debug.log("engine history import failed: " + e);
        }
    }

    /**
     * Which tool a record of the engine's history belongs to, from the record
     * type the engine stamps on it, or null for one that is not the engine's
     * own work. Proxied traffic is the proxy's, recorded there already.
     */
    static String toolFor(String type, String phase) {
        if (type == null || type.isEmpty()) return phase;
        return switch (type) {
            case "0" -> phase;                                               // the engine's own untyped fetches
            case "2", "9", "10", "15", "18", "19", "24", "25" -> "crawl";   // spiders, and the seed fetch
            case "3", "14" -> opentrap.core.ProjectOptions.AUDIT_TOOL;       // active scanner
            default -> null;                                                 // proxied, hidden, internal
        };
    }

    private boolean importMessage(JsonValue m, String tool, boolean toSiteMap) {
        String reqH = ZapApi.str(m, "requestHeader");
        if (reqH == null || reqH.isEmpty()) return false;
        String target = requestTargetUrl(reqH);
        HttpService s = (target != null) ? HttpService.parse(target) : null;
        if (s == null) return false;
        HttpMessage req = requestOf(m);
        // A scan is within scope, so nothing outside it is ever attributed to
        // one. The JavaScript crawl drives a real browser, which makes its own
        // background requests and loads each page's external resources; the
        // engine records those too, and they are not the crawl.
        if (!ctx.scope().isEmpty() && !ctx.scope().isInScope(s, req.path())) return false;
        LogEntry e = new LogEntry(tool, s, req);
        HttpMessage res = responseOf(m);
        if (res != null) e.setResponse(res);
        ctx.store().add(e);
        if (toSiteMap) ctx.siteMapChanged(e);
        return true;
    }

    /**
     * Rewrite a request header's start line from ZAP's absolute (proxy) form,
     * {@code POST http://host/path?q HTTP/1.1}, to the origin form the rest of the
     * tool uses, {@code POST /path?q HTTP/1.1}. Without this, "copy URL" and other
     * path logic would see an already-absolute target and double the host.
     */
    private static String toOriginForm(String requestHeader) {
        int nl = requestHeader.indexOf('\n');
        if (nl < 0) return requestHeader;
        String rawStart = requestHeader.substring(0, nl);     // may end with '\r'
        String rest = requestHeader.substring(nl + 1);
        boolean cr = rawStart.endsWith("\r");
        String start = cr ? rawStart.substring(0, rawStart.length() - 1) : rawStart;
        String[] parts = start.split(" ", 3);                 // METHOD TARGET VERSION
        if (parts.length == 3
                && (parts[1].startsWith("http://") || parts[1].startsWith("https://"))) {
            try {
                java.net.URI u = java.net.URI.create(parts[1]);
                String pq = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                        + (u.getRawQuery() != null ? "?" + u.getRawQuery() : "");
                start = parts[0] + " " + pq + " " + parts[2];
            } catch (Exception ignore) { /* leave as-is */ }
        }
        return start + (cr ? "\r" : "") + "\n" + rest;
    }

    /** The absolute URL from a request header's start line (ZAP uses proxy form). */
    private static String requestTargetUrl(String requestHeader) {
        int nl = requestHeader.indexOf('\n');
        String start = (nl < 0) ? requestHeader : requestHeader.substring(0, nl);
        String[] parts = start.trim().split("\\s+");
        if (parts.length < 2) return null;
        String target = parts[1];
        return target.startsWith("http://") || target.startsWith("https://") ? target : null;
    }

    // --------------------------------------------------------------- helpers

    private static Issue.Severity severity(String risk) {
        if (risk == null) return Issue.Severity.INFORMATION;
        return switch (risk.trim().toLowerCase()) {
            case "high" -> Issue.Severity.HIGH;
            case "medium" -> Issue.Severity.MEDIUM;
            case "low" -> Issue.Severity.LOW;
            default -> Issue.Severity.INFORMATION;     // "Informational"
        };
    }

    private static Issue.Confidence confidence(String c) {
        if (c == null) return Issue.Confidence.TENTATIVE;
        return switch (c.trim().toLowerCase()) {
            case "high", "confirmed", "user confirmed" -> Issue.Confidence.CERTAIN;
            case "medium" -> Issue.Confidence.FIRM;
            default -> Issue.Confidence.TENTATIVE;     // "Low", "False Positive"
        };
    }

    private static String pathOf(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            String p = u.getRawPath();
            if (p == null || p.isEmpty()) p = "/";
            if (u.getRawQuery() != null) p += "?" + u.getRawQuery();
            return p;
        } catch (Exception e) {
            return "/";
        }
    }

    private static int toInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return -1; }
    }

    private static String nz(String s) { return (s == null) ? "" : s; }

    /** Normalise a header block to end in a single blank line before the body. */
    private static String norm(String header) {
        return header.replaceAll("\\r?\\n+$", "") + "\r\n\r\n";
    }

    // ----------------------------------------------------------- diagnostics

    /**
     * Standalone check: attach to a running ZAP daemon and print how its alerts
     * for a base URL map into OpenTRAP issues. Not part of the app; run with
     *   java -cp build opentrap.engine.ZapScanner http://127.0.0.1:8090 "" http://host/
     */
    public static void main(String[] args) throws Exception {
        String base = args.length > 0 ? args[0] : "http://127.0.0.1:8090";
        String key = args.length > 1 ? args[1] : "";
        String baseurl = args.length > 2 ? args[2] : "http://testaspnet.vulnweb.com/";
        ZapApi api = new ZapApi(base, key);
        System.out.println("engine version: " + api.version());
        List<JsonValue> alerts = api.alerts(baseurl, 0, 100000);
        System.out.println("raw alerts: " + alerts.size());
        java.util.Map<String, int[]> bySev = new java.util.TreeMap<>();
        java.util.Map<String, Evidence> evidence = new java.util.HashMap<>();
        Issue sample = null;
        int mapped = 0;
        for (JsonValue a : alerts) {
            Issue is = mapAlert(api, a, evidence);
            if (is == null) continue;
            mapped++;
            bySev.computeIfAbsent(is.severity().label(), k -> new int[1])[0]++;
            if (sample == null && is.severity() == Issue.Severity.HIGH) sample = is;
        }
        System.out.println("mapped issues: " + mapped);
        bySev.forEach((s, c) -> System.out.println("  " + s + ": " + c[0]));
        if (sample == null && mapped > 0) {
            // fall back to any issue for the sample
            for (JsonValue a : alerts) { Issue is = mapAlert(api, a); if (is != null) { sample = is; break; } }
        }
        if (sample != null) {
            System.out.println("\nsample mapped issue:");
            System.out.println("  title      : " + sample.title());
            System.out.println("  severity   : " + sample.severity().label() + "  confidence: " + sample.confidence().label());
            System.out.println("  host/path  : " + sample.host() + sample.path());
            System.out.println("  param      : " + sample.param());
            System.out.println("  cwe/wasc   : " + sample.cwe() + " / " + sample.wasc() + "  module: " + sample.moduleId());
            System.out.println("  evidence   : " + trunc(sample.evidence(), 80));
            System.out.println("  request?   : " + (sample.request() != null));
            System.out.println("  response?  : " + (sample.response() != null));
            System.out.println("  detail     : " + trunc(sample.detail(), 120));
        }
    }

    private static String trunc(String s, int n) {
        if (s == null) return "";
        s = s.replaceAll("\\s+", " ");
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }
}
