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
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
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

    /** Start the spider (crawler); returns the scan id. */
    public String spiderScan(String url, int maxChildren) throws Exception {
        return str(call("spider", "action", "scan",
                p("url", url, "maxChildren", String.valueOf(maxChildren), "recurse", "true")), "scan");
    }

    public int spiderStatus(String id) throws Exception {
        return intOf(call("spider", "view", "status", p("scanId", id)), "status");
    }

    public List<JsonValue> spiderResults(String id) throws Exception {
        return arr(call("spider", "view", "results", p("scanId", id)), "results");
    }

    /** Start the active scan (audit) of the subtree under url; returns the scan id. */
    public String ascanScan(String url) throws Exception {
        return str(call("ascan", "action", "scan",
                p("url", url, "recurse", "true", "inScopeOnly", "false")), "scan");
    }

    public int ascanStatus(String id) throws Exception {
        return intOf(call("ascan", "view", "status", p("scanId", id)), "status");
    }

    public int numberOfAlerts(String baseurl) throws Exception {
        return intOf(call("core", "view", "numberOfAlerts", p("baseurl", baseurl)), "numberOfAlerts");
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
