package opentrap.core;

import java.util.Arrays;
import java.util.List;

/**
 * A single scanner finding.
 *
 * <p>An issue is a self-contained description of one observed or probed
 * weakness: what it is, how serious it is, how certain the scanner is, and the
 * request/response pair that evidences it. All of the prose carried here
 * (detail, background, remediation) is written for OpenTRAP and is deliberately
 * independent of any other vendor's wording.
 *
 * <p>Instances are immutable. Build one with {@link #builder(String)}; the long
 * constructor is retained for the original checks that predate the builder.
 */
public final class Issue {

    /** Severity bands, most serious first. */
    public enum Severity {
        HIGH("High"), MEDIUM("Medium"), LOW("Low"), INFORMATION("Information");
        private final String label;
        Severity(String label) { this.label = label; }
        public String label() { return label; }
    }

    /** How sure the scanner is that the finding is real. */
    public enum Confidence {
        CERTAIN("Certain"), FIRM("Firm"), TENTATIVE("Tentative");
        private final String label;
        Confidence(String label) { this.label = label; }
        public String label() { return label; }
    }

    /**
     * How widely a finding should be collapsed when the same weakness is seen
     * again.
     *
     * <p>This matters more than it sounds. A cookie set without HttpOnly is one
     * finding about one cookie, not one finding per URL that happens to set it,
     * so a single misconfiguration does not bury the rest of the report under
     * two hundred identical entries.
     */
    public enum Dedupe {
        /** One finding per host. Use for site-wide configuration. */
        SITE,
        /** One finding per host and path. The usual choice. */
        PATH,
        /** One finding per host, path and parameter. Use for injection points. */
        PARAM
    }

    private final String title;
    private final Severity severity;
    private final Confidence confidence;
    private final HttpService service;
    private final String path;
    private final String param;        // optional insertion-point name, may be null
    private final String detail;
    private final String background;
    private final String remediation;
    private final HttpMessage request;
    private final HttpMessage response;

    // Module metadata, absent for the original hand-written checks.
    private final int moduleId;
    private final int cwe;
    private final int wasc;
    private final List<String> tags;
    private final String references;
    private final String evidence;
    private final Dedupe dedupe;

    // Dashboard metadata, set after construction.
    private final long time = System.currentTimeMillis();
    private String source = "";    // which task found it (e.g. "Live audit", "Scan 3")
    private String comment = "";   // user-editable note

    public Issue(String title, Severity severity, Confidence confidence,
                 HttpService service, String path, String param,
                 String detail, String background, String remediation,
                 HttpMessage request, HttpMessage response) {
        this(title, severity, confidence, service, path, param, detail, background,
             remediation, request, response, -1, -1, -1, List.of(), "", "", Dedupe.PARAM);
    }

    private Issue(String title, Severity severity, Confidence confidence,
                  HttpService service, String path, String param,
                  String detail, String background, String remediation,
                  HttpMessage request, HttpMessage response,
                  int moduleId, int cwe, int wasc, List<String> tags,
                  String references, String evidence, Dedupe dedupe) {
        this.title = title;
        this.severity = severity;
        this.confidence = confidence;
        this.service = service;
        this.path = path == null ? "/" : path;
        this.param = param;
        this.detail = detail == null ? "" : detail;
        this.background = background == null ? "" : background;
        this.remediation = remediation == null ? "" : remediation;
        this.request = request;
        this.response = response;
        this.moduleId = moduleId;
        this.cwe = cwe;
        this.wasc = wasc;
        this.tags = tags == null ? List.of() : List.copyOf(tags);
        this.references = references == null ? "" : references;
        this.evidence = evidence == null ? "" : evidence;
        this.dedupe = dedupe == null ? Dedupe.PARAM : dedupe;
    }

    public String title()            { return title; }
    public Severity severity()       { return severity; }
    public Confidence confidence()   { return confidence; }
    public HttpService service()     { return service; }
    public String path()             { return path; }
    public String param()            { return param; }
    public String detail()           { return detail; }
    public String background()       { return background; }
    public String remediation()      { return remediation; }
    public HttpMessage request()     { return request; }
    public HttpMessage response()    { return response; }

    /** The id of the module that raised this, or -1 for the built-in checks. */
    public int moduleId()            { return moduleId; }
    /** CWE identifier, or -1 when none applies. */
    public int cwe()                 { return cwe; }
    /** WASC identifier, or -1 when none applies. */
    public int wasc()                { return wasc; }
    /** Classification tags such as an OWASP Top 10 category. */
    public List<String> tags()       { return tags; }
    /** Newline-separated reference URLs. */
    public String references()       { return references; }
    /** The exact text in the message that triggered the finding. */
    public String evidence()         { return evidence; }
    public Dedupe dedupeScope()      { return dedupe; }
    public long time()               { return time; }
    public String source()           { return source; }
    public void setSource(String s)  { this.source = s == null ? "" : s; }
    public String comment()          { return comment; }
    public void setComment(String c) { this.comment = c == null ? "" : c; }

    public String host() { return service == null ? "" : service.host(); }

    /**
     * Combined severity + confidence ordering, lower being more serious. This
     * reproduces the twelve-entry rank table: it is deliberately not the naive
     * severity-then-confidence sort, because a Medium/Certain finding is more
     * actionable than a High/Tentative one.
     */
    public int rank() {
        return switch (severity) {
            case HIGH -> switch (confidence) {
                case CERTAIN   -> 1;
                case FIRM      -> 2;
                case TENTATIVE -> 4;
            };
            case MEDIUM -> switch (confidence) {
                case CERTAIN   -> 3;
                case FIRM      -> 5;
                case TENTATIVE -> 7;
            };
            case LOW -> switch (confidence) {
                case CERTAIN   -> 6;
                case FIRM      -> 8;
                case TENTATIVE -> 9;
            };
            case INFORMATION -> switch (confidence) {
                case CERTAIN   -> 10;
                case FIRM      -> 11;
                case TENTATIVE -> 12;
            };
        };
    }

    /**
     * De-duplication key, at the granularity the raising module asked for.
     * Protocol and port are always included, so the same weakness on http and
     * https, or on two ports of one host, stays two findings.
     */
    public String dedupeKey() {
        String origin = service == null ? "" : service.protocol() + "://" + host() + ":" + service.port();
        return switch (dedupe) {
            case SITE  -> title + " " + origin;
            case PATH  -> title + " " + origin + " " + path;
            case PARAM -> title + " " + origin + " " + path + " " + (param == null ? "" : param);
        };
    }

    /** Short location label used as a tree leaf caption. */
    @Override public String toString() {
        String loc = (service == null ? "" : service.url()) + path;
        return param == null ? loc : loc + "  [" + param + "]";
    }

    // ---- builder -----------------------------------------------------------

    public static Builder builder(String title) { return new Builder(title); }

    /**
     * Fluent construction, so a module can state only what it cares about.
     * Severity defaults to Information and confidence to Firm.
     */
    public static final class Builder {
        private final String title;
        private Severity severity = Severity.INFORMATION;
        private Confidence confidence = Confidence.FIRM;
        private HttpService service;
        private String path;
        private String param;
        private String detail = "";
        private String background = "";
        private String remediation = "";
        private HttpMessage request;
        private HttpMessage response;
        private int moduleId = -1;
        private int cwe = -1;
        private int wasc = -1;
        private List<String> tags = List.of();
        private String references = "";
        private String evidence = "";
        private Dedupe dedupe = Dedupe.PATH;

        private Builder(String title) { this.title = title; }

        public Builder severity(Severity s)      { this.severity = s; return this; }
        public Builder confidence(Confidence c)  { this.confidence = c; return this; }
        public Builder service(HttpService s)    { this.service = s; return this; }
        public Builder path(String p)            { this.path = p; return this; }
        public Builder param(String p)           { this.param = p; return this; }
        public Builder detail(String s)          { this.detail = s; return this; }
        public Builder background(String s)      { this.background = s; return this; }
        public Builder remediation(String s)     { this.remediation = s; return this; }
        public Builder request(HttpMessage m)    { this.request = m; return this; }
        public Builder response(HttpMessage m)   { this.response = m; return this; }
        public Builder moduleId(int id)          { this.moduleId = id; return this; }
        public Builder cwe(int id)               { this.cwe = id; return this; }
        public Builder wasc(int id)              { this.wasc = id; return this; }
        public Builder tags(String... t)         { this.tags = t == null ? List.of() : Arrays.asList(t); return this; }
        public Builder tags(List<String> t)      { this.tags = t; return this; }
        public Builder references(String s)      { this.references = s; return this; }
        public Builder evidence(String s)        { this.evidence = s; return this; }
        public Builder dedupe(Dedupe d)          { this.dedupe = d; return this; }

        /** High severity with certain confidence, the usual pair for a proven finding. */
        public Builder high()    { return severity(Severity.HIGH); }
        public Builder medium()  { return severity(Severity.MEDIUM); }
        public Builder low()     { return severity(Severity.LOW); }
        public Builder info()    { return severity(Severity.INFORMATION); }
        public Builder certain()   { return confidence(Confidence.CERTAIN); }
        public Builder firm()      { return confidence(Confidence.FIRM); }
        public Builder tentative() { return confidence(Confidence.TENTATIVE); }

        public Issue build() {
            return new Issue(title, severity, confidence, service, path, param,
                             detail, background, remediation, request, response,
                             moduleId, cwe, wasc, tags, references, evidence, dedupe);
        }
    }
}
