package opentrap.extensions;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The extension store catalogue. OpenTRAP runs on the OWASP ZAP engine, so the
 * add-ons it can use are ZAP's: the catalogue lists the engine's marketplace
 * add-ons by their real identifiers. A user or a packager can extend or replace
 * the list with {@code ~/.opentrap/extensions-catalogue.txt}, one entry per
 * block, {@code key: value} lines, blank line between entries. Recognised keys:
 * id, name, authors, description, version, updated, rating (0-5), popularity
 * (0-100), source, impact, features (| separated), usage (| separated), note.
 *
 * <p>Nothing here is invented: fields a source does not provide are left empty
 * and the store simply does not show them.
 */
public final class Catalogue {

    /** One store entry. Optional fields are empty strings, lists are empty. */
    public record Entry(String id, String name, String authors, String description,
                        String version, String updated, double rating, int popularity,
                        String source, String impact, List<String> features,
                        List<String> usage, String note) {
        public String page() {
            return "https://www.zaproxy.org/docs/desktop/addons/" + id.replace('_', '-') + "/";
        }
    }

    private static final String TEAM = "ZAP Dev Team";
    private static final String SRC = "https://github.com/zaproxy/zap-extensions";

    private static Entry e(String id, String name, String desc) {
        return new Entry(id, name, TEAM, desc, "", "", 0, 0, SRC, "", List.of(), List.of(), "");
    }

    /** The built-in list: the engine's marketplace add-ons, by their real names. */
    public static List<Entry> builtIn() {
        return List.of(
            e("ascanrules", "Active scanner rules", "The release-quality active scan rules: injection, path traversal, remote file inclusion, command injection, server-side include, XSS and more."),
            e("pscanrules", "Passive scanner rules", "The release-quality passive scan rules that inspect requests and responses without sending anything extra."),
            e("ascanrulesBeta", "Active scanner rules (beta)", "Active scan rules that are complete but still being evaluated for release quality."),
            e("pscanrulesBeta", "Passive scanner rules (beta)", "Passive scan rules that are complete but still being evaluated for release quality."),
            e("ascanrulesAlpha", "Active scanner rules (alpha)", "Experimental active scan rules."),
            e("pscanrulesAlpha", "Passive scanner rules (alpha)", "Experimental passive scan rules."),
            e("domxss", "DOM XSS active scan rule", "Finds DOM-based cross-site scripting by driving a real browser against the target."),
            e("openapi", "OpenAPI support", "Imports OpenAPI (Swagger) definitions so the endpoints they describe can be crawled and scanned."),
            e("soap", "SOAP support", "Imports WSDL definitions and lets the scanner exercise SOAP endpoints."),
            e("graphql", "GraphQL support", "Imports GraphQL schemas and generates queries, mutations and subscriptions for scanning."),
            e("postman", "Postman support", "Imports Postman collections as scan targets."),
            e("retire", "Retire.js", "Flags client-side JavaScript libraries with known vulnerabilities."),
            e("wappalyzer", "Technology detection", "Identifies the technologies a site is built with, from response fingerprints."),
            e("fuzz", "Fuzzer", "Sends many variations of a request with payloads from built-in or custom lists."),
            e("websocket", "WebSockets", "Intercepts, views and fuzzes WebSocket traffic."),
            e("spiderAjax", "AJAX spider", "Crawls JavaScript applications with a real browser."),
            e("portscan", "Port scan", "Scans the target host for open ports."),
            e("replacer", "Replacer", "Match-and-replace rules applied to requests and responses as they pass through."),
            e("scripts", "Script console", "Runs user scripts at defined points: proxy, active/passive scan, authentication, and more."),
            e("zest", "Zest", "Records and replays scripted sequences of requests, with assertions."),
            e("accessControl", "Access control testing", "Tests which users can reach which resources and reports access control gaps."),
            e("authhelper", "Authentication helper", "Detects and configures authentication automatically for common login flows."),
            e("sequence", "Sequence", "Scans multi-step sequences of requests as a unit."),
            e("exim", "Import/Export", "Imports and exports messages and sessions in several formats, including HAR."),
            e("reports", "Report generation", "Generates HTML, Markdown, JSON, XML and PDF reports of findings."),
            e("automation", "Automation framework", "Runs end-to-end scans from a YAML plan, for CI pipelines."),
            e("encoder", "Encoder", "Encodes, decodes and hashes text in many formats."),
            e("jwt", "JWT support", "Finds and tests JSON Web Tokens for weaknesses."),
            e("evalvillain", "Eval Villain", "Hooks sinks in the browser to surface DOM XSS at run time."),
            e("fileupload", "File upload", "Tests file upload functionality for unsafe handling."),
            e("custompayloads", "Custom payloads", "Adds your own payloads to the scan rules that support them."),
            e("formhandler", "Form handler", "Supplies values for form fields the crawler meets."),
            e("imagelocationscanner", "Image location and privacy scanner", "Finds location and privacy metadata in images."),
            e("attacksurfacedetector", "Attack surface detector", "Discovers endpoints and parameters from application source code."),
            e("directorylistv2_3", "Directory list v2.3", "Word lists for forced browsing and content discovery."),
            e("svndigger", "SVN Digger files", "Word lists harvested from open-source repositories for content discovery."),
            e("tokengen", "Token generator", "Collects and analyses session tokens for randomness."),
            e("diff", "Diff", "Compares two requests or responses."),
            e("highlighter", "Highlighter", "Highlights chosen strings in requests and responses."),
            e("regextester", "Regular expression tester", "Tries regular expressions against sample text."),
            e("requester", "Requester", "Edits and resends individual requests."),
            e("reveal", "Reveal", "Shows hidden form fields and enables disabled ones in the browser."),
            e("saverawmessage", "Save raw message", "Saves a request or response to disk exactly as sent."),
            e("treetools", "Tree tools", "Extra actions on the site tree."),
            e("tlsdebug", "TLS debug", "Diagnoses TLS connection problems to a host."),
            e("sse", "Server-sent events", "Views and intercepts server-sent event streams."),
            e("amf", "AMF support", "Decodes Adobe Message Format messages."),
            e("selenium", "Selenium", "Launches and drives browsers for the crawler and DOM scanners."),
            e("hud", "HUD", "An in-browser heads-up display over the application under test."),
            e("quickstart", "Quick start", "One-click automated scan of a URL."),
            e("bugtracker", "Bug tracker", "Raises findings as issues in GitHub or Bugzilla."),
            e("callhome", "Call home", "Checks for engine and add-on updates."),
            e("tips", "Tips and tricks", "Short tips about the engine, shown on request."),
            e("invoke", "Invoke applications", "Runs external tools against the selected request."),
            e("database", "Database", "The engine's session database support.")
        );
    }

    /** The user's own catalogue file, if any. */
    public static Path userFile() {
        return Path.of(System.getProperty("user.home", "."), ".opentrap", "extensions-catalogue.txt");
    }

    /** Built-in entries, then any user entries (a user entry with the same id replaces the built-in one). */
    public static List<Entry> load() {
        Map<String, Entry> byId = new LinkedHashMap<>();
        for (Entry x : builtIn()) byId.put(x.id(), x);
        Path f = userFile();
        if (Files.isRegularFile(f)) {
            try {
                for (Entry x : parse(Files.readString(f))) byId.put(x.id(), x);
            } catch (IOException ignore) {
                // unreadable user file: keep the built-in list
            }
        }
        return new ArrayList<>(byId.values());
    }

    /** Parse the blank-line separated {@code key: value} format. */
    static List<Entry> parse(String text) {
        List<Entry> out = new ArrayList<>();
        Map<String, String> cur = new LinkedHashMap<>();
        for (String raw : (text + "\n\n").split("\n")) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (!cur.isEmpty()) { Entry x = entry(cur); if (x != null) out.add(x); cur = new LinkedHashMap<>(); }
                continue;
            }
            if (line.startsWith("#")) continue;
            int i = line.indexOf(':');
            if (i <= 0) continue;
            cur.put(line.substring(0, i).strip().toLowerCase(), line.substring(i + 1).strip());
        }
        return out;
    }

    private static Entry entry(Map<String, String> m) {
        String id = m.getOrDefault("id", "");
        String name = m.getOrDefault("name", id);
        if (id.isEmpty() && name.isEmpty()) return null;
        if (id.isEmpty()) id = name.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        return new Entry(id, name, m.getOrDefault("authors", ""), m.getOrDefault("description", ""),
                m.getOrDefault("version", ""), m.getOrDefault("updated", ""),
                num(m.get("rating")), (int) num(m.get("popularity")),
                m.getOrDefault("source", ""), m.getOrDefault("impact", ""),
                list(m.get("features")), list(m.get("usage")), m.getOrDefault("note", ""));
    }

    private static double num(String s) {
        if (s == null || s.isBlank()) return 0;
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return 0; }
    }

    private static List<String> list(String s) {
        if (s == null || s.isBlank()) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        for (String p : s.split("\\|")) if (!p.isBlank()) out.add(p.strip());
        return out;
    }
}
