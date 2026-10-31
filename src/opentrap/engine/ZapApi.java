package opentrap.engine;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import opentrap.core.Debug;
import opentrap.spider.Cdp.JsonValue;

/**
 * Thin client for the OWASP ZAP JSON API over the loopback.
 *
 * <p>OpenTRAP drives ZAP purely as a headless scan engine: this talks to the
 * daemon's REST API ({@code /JSON/<component>/<view|action>/<name>/}) and parses
 * the JSON replies with the project's own {@link JsonValue} parser. Nothing of
 * ZAP's own code runs inside OpenTRAP; this is an HTTP conversation with a
 * separate process.
 */
public final class ZapApi {

    private final String base;     // e.g. http://127.0.0.1:8090
    private final String apiKey;   // may be null or empty (disabled key)
    private final java.net.http.HttpClient http =
            java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();

    public ZapApi(String base, String apiKey) {
        this.base = base;
        this.apiKey = apiKey;
    }

    public String base() { return base; }

    /** Call a ZAP JSON API endpoint. {@code kind} is "view" or "action". */
    public JsonValue call(String component, String kind, String name, Map<String, String> params)
            throws Exception {
        StringBuilder sb = new StringBuilder(base)
                .append("/JSON/").append(component).append('/')
                .append(kind).append('/').append(name).append("/?");
        if (apiKey != null && !apiKey.isEmpty()) {
            sb.append("apikey=").append(enc(apiKey)).append('&');
        }
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                sb.append(enc(e.getKey())).append('=').append(enc(e.getValue())).append('&');
            }
        }
        HttpRequest req = HttpRequest.newBuilder(URI.create(sb.toString()))
                .timeout(Duration.ofSeconds(120))
                .GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        return JsonValue.parse(r.body());
    }

    private static String enc(String s) {
        // A null parameter value must never crash a scan: URLEncoder.encode(null)
        // throws a NullPointerException, so treat null as the empty string.
        return s == null ? "" : URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static Map<String, String> p(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ----------------------------------------------------------- convenience

    public String version() throws Exception {
        return str(call("core", "view", "version", null), "version");
    }

    public void accessUrl(String url) throws Exception {
        call("core", "action", "accessUrl", p("url", url, "followRedirects", "true"));
    }

    /** How many links deep the spider follows (0 = unlimited). */
    public void setSpiderMaxDepth(int depth) throws Exception {
        call("spider", "action", "setOptionMaxDepth", p("Integer", String.valueOf(depth)));
    }

    /** Spider time budget in minutes (0 = no limit). */
    public void setSpiderMaxDuration(int minutes) throws Exception {
        call("spider", "action", "setOptionMaxDuration", p("Integer", String.valueOf(minutes)));
    }

    /** Concurrent crawler threads (resource pool). */
    public void setSpiderThreads(int n) throws Exception {
        call("spider", "action", "setOptionThreadCount", p("Integer", String.valueOf(Math.max(1, n))));
    }

    /** Concurrent active-scan threads per host (resource pool). */
    public void setActiveThreadPerHost(int n) throws Exception {
        call("ascan", "action", "setOptionThreadPerHost", p("Integer", String.valueOf(Math.max(1, n))));
    }

    /**
     * Start the spider (crawler) inside a context, so it never leaves the scope
     * the context holds; returns the scan id. Fails with the engine's own
     * message when the URL is outside the context.
     */
    public String spiderScan(String url, int maxChildren, String contextName) throws Exception {
        Map<String, String> q = p("url", url, "maxChildren", String.valueOf(maxChildren), "recurse", "true");
        if (contextName != null) q.put("contextName", contextName);
        return str(checked(call("spider", "action", "scan", q)), "scan");
    }

    /**
     * The engine answers an action it refused with an error object instead of
     * the result; turn that into an exception carrying its message, so a
     * refusal cannot pass as an empty result.
     */
    static JsonValue checked(JsonValue v) throws Exception {
        String code = str(v, "code");
        if (code != null && !code.isEmpty()) {
            String msg = str(v, "message");
            String detail = str(v, "detail");
            throw new Exception((msg == null || msg.isEmpty() ? code : msg)
                    + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
        }
        return v;
    }

    // ------------------------------------------------------------ contexts

    /** Create a context; returns its id. */
    public String newContext(String name) throws Exception {
        return str(checked(call("context", "action", "newContext", p("contextName", name))), "contextId");
    }

    /** Remove a context; a context that does not exist is not an error. */
    public void removeContext(String name) throws Exception {
        call("context", "action", "removeContext", p("contextName", name));
    }

    /** A whole-URL regex the context includes. */
    public void includeInContext(String name, String regex) throws Exception {
        checked(call("context", "action", "includeInContext", p("contextName", name, "regex", regex)));
    }

    /** A whole-URL regex the context excludes, even when an include covers it. */
    public void excludeFromContext(String name, String regex) throws Exception {
        checked(call("context", "action", "excludeFromContext", p("contextName", name, "regex", regex)));
    }

    /** Whether the context counts as the engine's scope. */
    public void setContextInScope(String name, boolean inScope) throws Exception {
        checked(call("context", "action", "setContextInScope",
                p("contextName", name, "booleanInScope", String.valueOf(inScope))));
    }

    public int spiderStatus(String id) throws Exception {
        return intOf(call("spider", "view", "status", p("scanId", id)), "status");
    }

    /** Stop the spider scan so the engine releases it and stops crawling. */
    public void spiderStop(String id) throws Exception {
        call("spider", "action", "stop", p("scanId", id));
    }

    public void spiderPause(String id) throws Exception {
        call("spider", "action", "pause", p("scanId", id));
    }

    public void spiderResume(String id) throws Exception {
        call("spider", "action", "resume", p("scanId", id));
    }

    public List<JsonValue> spiderResults(String id) throws Exception {
        return arr(call("spider", "view", "results", p("scanId", id)), "results");
    }

    /**
     * Start the active scan (audit) of the subtree under url, limited to the
     * context when one is given, so nothing outside it is ever attacked;
     * returns the scan id. Fails with the engine's own message when there is
     * nothing under the URL or it lies outside the context.
     */
    public String ascanScan(String url, String contextId) throws Exception {
        Map<String, String> q = p("url", url, "recurse", "true",
                "inScopeOnly", String.valueOf(contextId != null));
        if (contextId != null) q.put("contextId", contextId);
        return str(checked(call("ascan", "action", "scan", q)), "scan");
    }

    public int ascanStatus(String id) throws Exception {
        return intOf(call("ascan", "view", "status", p("scanId", id)), "status");
    }

    /** Stop the active scan so the engine stops attacking and releases it. */
    public void ascanStop(String id) throws Exception {
        call("ascan", "action", "stop", p("scanId", id));
    }

    public void ascanPause(String id) throws Exception {
        call("ascan", "action", "pause", p("scanId", id));
    }

    public void ascanResume(String id) throws Exception {
        call("ascan", "action", "resume", p("scanId", id));
    }

    // ------------------------------------------------- AJAX spider (JavaScript)

    /** Pick the browser the AJAX spider drives, e.g. "chrome-headless". */
    public void setAjaxBrowser(String browserId) throws Exception {
        call("ajaxSpider", "action", "setOptionBrowserId", p("String", browserId));
    }

    /** Bound the AJAX spider's concurrent headless-browser pool. */
    public void setAjaxBrowsers(int n) throws Exception {
        call("ajaxSpider", "action", "setOptionNumberOfBrowsers", p("Integer", String.valueOf(n)));
    }

    /**
     * Start the AJAX spider over the URL, kept inside the context when one is
     * given; it executes JavaScript as it crawls.
     */
    public void ajaxSpiderScan(String url, String contextName) throws Exception {
        Map<String, String> q = p("url", url, "inScope", "false");
        if (contextName != null) q.put("contextName", contextName);
        checked(call("ajaxSpider", "action", "scan", q));
    }

    /** "running" while the AJAX spider is working, "stopped" once it is done. */
    public String ajaxSpiderStatus() throws Exception {
        return str(call("ajaxSpider", "view", "status", null), "status");
    }

    /** How many results (URLs) the AJAX spider has found so far. */
    public int ajaxSpiderResults() throws Exception {
        return intOf(call("ajaxSpider", "view", "numberOfResults", null), "numberOfResults");
    }

    /** Stop the AJAX spider early (e.g. when a time budget is reached). */
    public void ajaxSpiderStop() throws Exception {
        call("ajaxSpider", "action", "stop", null);
    }

    public int numberOfAlerts(String baseurl) throws Exception {
        return intOf(call("core", "view", "numberOfAlerts", p("baseurl", baseurl)), "numberOfAlerts");
    }

    /**
     * The number of unique locations (endpoints) under {@code baseurl} that ZAP
     * has discovered, counted at node granularity: a path plus its set of
     * parameter names, with parameter *values* collapsed. So {@code /p?id=2} and
     * {@code /p?id=3} count once, while {@code /p?d=1} counts separately. This
     * reads ZAP's site tree, so it combines the traditional and AJAX spiders
     * with no double counting.
     */
    public int uniqueLocations(String baseurl) throws Exception {
        java.util.Set<String> nodes = new java.util.HashSet<>();
        for (JsonValue u : arr(call("core", "view", "urls", p("baseurl", baseurl)), "urls")) {
            String url = u.asString();
            if (url != null) nodes.add(nodeKey(url));
        }
        return nodes.size();
    }

    /** host + path + the sorted set of query-parameter names (values dropped). */
    static String nodeKey(String url) {
        try {
            java.net.URI u = java.net.URI.create(url);
            StringBuilder b = new StringBuilder();
            if (u.getHost() != null) b.append(u.getHost());
            b.append(u.getRawPath() == null ? "/" : u.getRawPath());
            String q = u.getRawQuery();
            if (q != null && !q.isEmpty()) {
                java.util.TreeSet<String> names = new java.util.TreeSet<>();
                for (String pair : q.split("&")) {
                    if (pair.isEmpty()) continue;
                    int eq = pair.indexOf('=');
                    names.add(eq >= 0 ? pair.substring(0, eq) : pair);
                }
                b.append('?').append(String.join("&", names));
            }
            return b.toString();
        } catch (Exception e) {
            return url;   // unparseable: treat the raw URL as its own node
        }
    }

    public List<JsonValue> alerts(String baseurl, int start, int count) throws Exception {
        return arr(call("core", "view", "alerts",
                p("baseurl", baseurl, "start", String.valueOf(start), "count", String.valueOf(count))), "alerts");
    }

    public List<JsonValue> messages(String baseurl, int start, int count) throws Exception {
        return arr(call("core", "view", "messages",
                p("baseurl", baseurl, "start", String.valueOf(start), "count", String.valueOf(count))), "messages");
    }

    public JsonValue message(String id) throws Exception {
        JsonValue v = call("core", "view", "message", p("id", id));
        return v == null ? null : v.get("message");
    }

    /** Total messages ZAP has recorded this session (all tools). */
    public int numberOfMessages() throws Exception {
        return intOf(call("core", "view", "numberOfMessages", null), "numberOfMessages");
    }

    /** A slice of ZAP's complete message history, for streaming into the logger. */
    public List<JsonValue> messages(int start, int count) throws Exception {
        return arr(call("core", "view", "messages",
                p("start", String.valueOf(start), "count", String.valueOf(count))), "messages");
    }

    public void shutdown() throws Exception {
        call("core", "action", "shutdown", null);
    }

    // ------------------------------------------------- passive scan-rule config

    /** Every passive rule ZAP has: id, name, enabled, alertThreshold, quality. */
    public List<JsonValue> passiveScanners() throws Exception {
        return arr(call("pscan", "view", "scanners", null), "scanners");
    }

    public void setPassiveEnabled(String id, boolean enabled) throws Exception {
        call("pscan", "action", enabled ? "enableScanners" : "disableScanners", p("ids", id));
    }

    public void setAllPassiveEnabled(boolean enabled) throws Exception {
        call("pscan", "action", enabled ? "enableAllScanners" : "disableAllScanners", null);
    }

    /** threshold is one of OFF, DEFAULT, LOW, MEDIUM, HIGH. */
    public void setPassiveThreshold(String id, String threshold) throws Exception {
        call("pscan", "action", "setScannerAlertThreshold", p("id", id, "alertThreshold", threshold));
    }

    // -------------------------------------------------- active scan-rule config

    /** Every active rule ZAP has: id, name, enabled, attackStrength, alertThreshold. */
    public List<JsonValue> activeScanners() throws Exception {
        return arr(call("ascan", "view", "scanners", null), "scanners");
    }

    public void setActiveEnabled(String id, boolean enabled) throws Exception {
        call("ascan", "action", enabled ? "enableScanners" : "disableScanners", p("ids", id));
    }

    /** strength is one of LOW, MEDIUM, HIGH, INSANE, DEFAULT. */
    public void setActiveStrength(String id, String strength) throws Exception {
        call("ascan", "action", "setScannerAttackStrength", p("id", id, "attackStrength", strength));
    }

    /** threshold is one of OFF, DEFAULT, LOW, MEDIUM, HIGH. */
    public void setActiveThreshold(String id, String threshold) throws Exception {
        call("ascan", "action", "setScannerAlertThreshold", p("id", id, "alertThreshold", threshold));
    }

    /**
     * Set the attack strength (LOW, MEDIUM, HIGH, INSANE) on every active rule, so
     * the whole default scan policy probes at the one intensity. Applied rule by
     * rule because ZAP has no single global strength control; a rule ZAP refuses
     * is skipped rather than failing the whole call.
     */
    public void setAllAttackStrength(String strength) throws Exception {
        for (JsonValue s : activeScanners()) {
            String id = str(s, "id");
            if (id != null) try { setActiveStrength(id, strength); }
            catch (Exception e) { Debug.log("strength for active rule " + id + ": " + e); }
        }
    }

    /** Set the alert threshold (OFF, LOW, MEDIUM, HIGH) on every active rule. */
    public void setAllAlertThreshold(String threshold) throws Exception {
        for (JsonValue s : activeScanners()) {
            String id = str(s, "id");
            if (id != null) try { setActiveThreshold(id, threshold); }
            catch (Exception e) { Debug.log("threshold for active rule " + id + ": " + e); }
        }
    }

    // ----------------------------------------------- active scan engine options

    /**
     * Which request locations the active scan injects payloads into, as a bitmask
     * of query string, POST body, URL path, headers and cookies (see
     * {@link ScanConfig}). More locations means broader coverage and more requests.
     */
    public void setInjectableParams(int mask) throws Exception {
        call("ascan", "action", "setOptionTargetParamsInjectable", p("Integer", String.valueOf(mask)));
    }

    /**
     * Which request-body content types the active scan tests (JSON, XML, multipart
     * and so on), as a bitmask (see {@link ScanConfig}). A type only applies when a
     * request actually carries it.
     */
    public void setInjectableRpc(int mask) throws Exception {
        call("ascan", "action", "setOptionTargetParamsEnabledRPC", p("Integer", String.valueOf(mask)));
    }

    /** Throttle between active-scan requests in milliseconds (0 = fastest). */
    public void setActiveDelayMs(int ms) throws Exception {
        call("ascan", "action", "setOptionDelayInMs", p("Integer", String.valueOf(Math.max(0, ms))));
    }

    /** Active-scan time backstop in minutes (0 = no limit). */
    public void setActiveMaxScanDuration(int minutes) throws Exception {
        call("ascan", "action", "setOptionMaxScanDurationInMins", p("Integer", String.valueOf(Math.max(0, minutes))));
    }

    /** Whether the crawler fills and submits forms to reach more of the site. */
    public void setSpiderProcessForms(boolean on) throws Exception {
        call("spider", "action", "setOptionPostForm", p("Boolean", String.valueOf(on)));
        call("spider", "action", "setOptionProcessForm", p("Boolean", String.valueOf(on)));
    }

    // ------------------------------------------------- AJAX spider budget options

    /** AJAX crawl time budget in minutes (0 = no limit). */
    public void setAjaxMaxDuration(int minutes) throws Exception {
        call("ajaxSpider", "action", "setOptionMaxDuration", p("Integer", String.valueOf(Math.max(0, minutes))));
    }

    /** AJAX crawl click-depth from the seed (0 = unlimited). */
    public void setAjaxMaxCrawlDepth(int depth) throws Exception {
        call("ajaxSpider", "action", "setOptionMaxCrawlDepth", p("Integer", String.valueOf(Math.max(0, depth))));
    }

    /** How many distinct UI states the AJAX crawl explores (0 = unlimited). */
    public void setAjaxMaxCrawlStates(int states) throws Exception {
        call("ajaxSpider", "action", "setOptionMaxCrawlStates", p("Integer", String.valueOf(Math.max(0, states))));
    }

    // ----------------------------------------------------------- json helpers

    public static String str(JsonValue o, String k) {
        JsonValue v = (o == null) ? null : o.get(k);
        return v == null ? null : v.asString();
    }

    public static int intOf(JsonValue o, String k) {
        try { return Integer.parseInt(str(o, k)); } catch (Exception e) { return -1; }
    }

    static List<JsonValue> arr(JsonValue o, String k) {
        JsonValue v = (o == null) ? null : o.get(k);
        return (v == null || !v.isArray()) ? List.of() : v.asArray();
    }
}
