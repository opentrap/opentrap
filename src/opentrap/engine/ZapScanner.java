package opentrap.engine;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import opentrap.core.Bytes;
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

    private final SuiteContext ctx;
    private final ZapApi api;

    public ZapScanner(SuiteContext ctx, ZapApi api) {
        this.ctx = ctx;
        this.api = api;
    }

    /**
     * Crawl the seed, then (when {@code thenAudit}) audit what the crawl found.
     * The audit runs strictly after the crawl has finished, on this one thread;
     * its task card is created only when the audit actually begins, so it does
     * not appear to be running alongside the crawl.
     *
     * @param crawlTask a LIVE_ACTIVE task to report crawl progress on
     */
    public void crawlAndAudit(HttpService seed, String seedUrl, boolean thenAudit,
                              int maxDepth, int maxDurationMin, ScanTask crawlTask) throws Exception {
        String baseurl = seed.url();
        api.accessUrl(seedUrl);

        // ---- crawl -------------------------------------------------------
        try {
            api.setSpiderMaxDepth(maxDepth);
            api.setSpiderMaxDuration(maxDurationMin);
        } catch (Exception ignore) { /* use the engine defaults */ }
        String sid = api.spiderScan(seedUrl, 20);
        int lastFound = -1;
        while (true) {
            int st = api.spiderStatus(sid);
            int found = api.spiderResults(sid).size();
            if (found != lastFound) {
                crawlTask.setItemsDone(found);
                crawlTask.setItemsTotal(found);
                crawlTask.log("Crawled " + found + " url(s)");
                ctx.taskChanged(crawlTask);
                lastFound = found;
            }
            // Stream the crawl's traffic into the logger and the site map as it happens.
            drain("crawl", true, crawlTask);
            ctx.taskChanged(crawlTask);
            if (st >= 100) break;
            Thread.sleep(1000);
        }
        drain("crawl", true, crawlTask);
        crawlTask.log("Crawl finished: " + crawlTask.requests() + " request(s)");
        crawlTask.setState(ScanTask.State.FINISHED);
        ctx.taskChanged(crawlTask);

        if (!thenAudit) return;

        // ---- audit (only now does its task appear) -----------------------
        ScanTask auditTask = new ScanTask(ScanTask.Type.AUDIT,
                "Audit of " + seed.host(), "ZAP active rules, within scope");
        auditTask.setScope(seed.url());
        ctx.addTask(auditTask);
        try {
            auditTask.log("Auditing with the ZAP active rules...");
            ctx.taskChanged(auditTask);
            String aid = api.ascanScan(seedUrl);
            Set<String> seen = new HashSet<>();
            while (true) {
                int st = api.ascanStatus(aid);
                auditTask.setItemsTotal(100);
                auditTask.setItemsDone(Math.max(0, st));
                pullAlerts(baseurl, seed.host(), auditTask, seen);
                drain("audit", false, auditTask);   // stream the attack traffic to the logger
                ctx.taskChanged(auditTask);
                if (st >= 100) break;
                Thread.sleep(2000);
            }
            pullAlerts(baseurl, seed.host(), auditTask, seen);
            drain("audit", false, auditTask);
            auditTask.log("Audit finished: " + seen.size() + " finding(s)");
        } finally {
            auditTask.setState(ScanTask.State.FINISHED);
            ctx.taskChanged(auditTask);
        }
    }

    /** Fetch alerts and add any not yet seen to the issue tree; recount badges. */
    private void pullAlerts(String baseurl, String host, ScanTask task, Set<String> seen) throws Exception {
        for (JsonValue a : api.alerts(baseurl, 0, 100000)) {
            String id = ZapApi.str(a, "id");
            if (id != null && !seen.add(id)) continue;
            Issue is = mapAlert(api, a);
            if (is != null) { is.setSource("Scan " + task.number); ctx.addIssue(is); }
        }
        recount(task, host);
    }

    private void recount(ScanTask task, String host) {
        int h = 0, m = 0, l = 0, i = 0;
        for (Issue is : ctx.issues()) {
            if (is.host() == null || !host.equalsIgnoreCase(is.host())) continue;
            switch (is.severity()) {
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

        if (api != null) {
            String mid = ZapApi.str(a, "messageId");
            if (mid != null && !mid.isEmpty()) {
                try { attachMessage(api, b, mid); } catch (Exception ignore) { }
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

    private static void attachMessage(ZapApi api, Issue.Builder b, String messageId) throws Exception {
        JsonValue m = api.message(messageId);
        if (m == null) return;
        String reqH = ZapApi.str(m, "requestHeader");
        String resH = ZapApi.str(m, "responseHeader");
        if (reqH != null && !reqH.isEmpty()) {
            b.request(new HttpMessage(Bytes.bytes(norm(toOriginForm(reqH)) + nz(ZapApi.str(m, "requestBody")))));
        }
        if (resH != null && !resH.isEmpty()) {
            b.response(new HttpMessage(Bytes.bytes(norm(resH) + nz(ZapApi.str(m, "responseBody")))));
        }
    }

    // ------------------------------------------- live traffic record (logger)

    private int importedMessages = 0;

    /**
     * Stream every new message ZAP has recorded into our store, so the logger
     * holds a complete live record of everything the engine ran. Tagged by phase:
     * {@code crawl} pages also populate the site map; {@code audit} probes do not,
     * to keep the site map to real endpoints rather than every attack variant.
     * Batched so a flood of attack traffic cannot pull thousands of messages in
     * one call.
     */
    private void drain(String tool, boolean toSiteMap, ScanTask task) {
        try {
            int total = api.numberOfMessages();
            int added = 0;
            while (importedMessages < total) {
                int want = Math.min(total - importedMessages, 500);
                java.util.List<JsonValue> batch = api.messages(importedMessages, want);
                if (batch.isEmpty()) break;
                for (JsonValue m : batch) if (importMessage(m, tool, toSiteMap)) added++;
                importedMessages += batch.size();
            }
            if (added > 0 && task != null) task.addRequests(added);
        } catch (Exception ignore) { }
    }

    private boolean importMessage(JsonValue m, String tool, boolean toSiteMap) {
        String reqH = ZapApi.str(m, "requestHeader");
        if (reqH == null || reqH.isEmpty()) return false;
        String target = requestTargetUrl(reqH);
        HttpService s = (target != null) ? HttpService.parse(target) : null;
        if (s == null) return false;
        HttpMessage req = new HttpMessage(Bytes.bytes(norm(toOriginForm(reqH)) + nz(ZapApi.str(m, "requestBody"))));
        LogEntry e = new LogEntry(tool, s, req);
        String resH = ZapApi.str(m, "responseHeader");
        if (resH != null && !resH.isEmpty()) {
            e.setResponse(new HttpMessage(Bytes.bytes(norm(resH) + nz(ZapApi.str(m, "responseBody")))));
        }
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
        Issue sample = null;
        int mapped = 0;
        for (JsonValue a : alerts) {
            Issue is = mapAlert(api, a);
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
