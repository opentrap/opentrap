package opentrap.engine;

/**
 * An immutable bundle of the real engine settings a scan runs with. OpenTRAP
 * offers a handful of named configurations ("Lightweight", "Fast", "Balanced",
 * "Deep") so a user picks a tier instead of hand-tuning the engine; each tier is
 * one of these records, built by {@link #preset(String)}.
 *
 * <p>The design principle is deliberate: <em>every tier tests the same things</em>
 * - all input vectors, all rules, the same reporting threshold - so a scan never
 * reports a target clean merely because a vector was switched off. Tiers differ
 * only in how much attack surface they discover (crawl depth and AJAX budget) and
 * how hard each rule probes it (attack strength). The active audit itself is never
 * time-capped by default, so it always runs every test it starts to completion
 * rather than being cut off mid-way with lopsided, partial results. Discovery is
 * what a tier bounds; testing of whatever was discovered is always complete.
 */
public record ScanConfig(
        String name,
        int crawlDepth,          // traditional spider: links deep (0 = unlimited)
        int crawlDurationMin,    // traditional spider time budget (0 = none)
        boolean submitForms,     // fill and submit forms while crawling
        boolean ajaxSpider,      // drive a headless browser for JavaScript sites
        int ajaxMaxDurationMin,  // AJAX crawl time budget (0 = none)
        int ajaxMaxDepth,        // AJAX crawl click-depth (0 = unlimited)
        int ajaxMaxStates,       // AJAX distinct UI states explored (0 = unlimited)
        String attackStrength,   // LOW, MEDIUM, HIGH, INSANE
        String alertThreshold,   // OFF, LOW, MEDIUM, HIGH
        int injectableMask,      // where payloads are injected (see INJECT_* bits)
        int rpcMask,             // request-body content types tested (see RPC_* bits)
        int delayMs,             // throttle between active-scan requests (0 = fastest)
        int scanDurationMin      // active-scan time backstop (0 = none; not a tier dial)
) {

    // Injection points (ZAP ScannerParam injectable bitmask).
    public static final int INJECT_QUERY   = 1;
    public static final int INJECT_BODY    = 2;
    public static final int INJECT_PATH    = 4;
    public static final int INJECT_HEADERS = 8;
    public static final int INJECT_COOKIE  = 16;
    /** Every injection point: query, body, URL path, headers and cookies. */
    public static final int INJECT_ALL =
            INJECT_QUERY | INJECT_BODY | INJECT_PATH | INJECT_HEADERS | INJECT_COOKIE;

    // Request-body content types (ZAP ScannerParam enabled-RPC bitmask). These
    // only fire when a request actually carries that content type, so enabling
    // them everywhere costs nothing on a traditional form-based site but makes a
    // scan test JSON/XML API parameters rather than silently skipping them.
    public static final int RPC_MULTIPART = 1;
    public static final int RPC_XML       = 2;
    public static final int RPC_JSON      = 4;
    public static final int RPC_GWT       = 8;
    public static final int RPC_ODATA     = 16;
    /** Every request-body content type we can test. */
    public static final int RPC_ALL =
            RPC_MULTIPART | RPC_XML | RPC_JSON | RPC_GWT | RPC_ODATA;

    /**
     * The configuration for a named tier. Coverage (every vector, every rule) and
     * the reporting threshold are held constant across all tiers; only crawl
     * depth, the AJAX discovery budget and the per-rule attack strength vary.
     * An unknown name falls back to "Balanced".
     */
    public static ScanConfig preset(String name) {
        return switch (name == null ? "" : name) {
            case "Lightweight" -> new ScanConfig(
                    "Lightweight",
                    2, 1, true,
                    true, 2, 5, 20,
                    "LOW", "MEDIUM",
                    INJECT_ALL, RPC_ALL, 0, 0);
            case "Fast" -> new ScanConfig(
                    "Fast",
                    5, 3, true,
                    true, 5, 10, 40,
                    "MEDIUM", "MEDIUM",
                    INJECT_ALL, RPC_ALL, 0, 0);
            case "Deep" -> new ScanConfig(
                    "Deep",
                    0, 10, true,
                    true, 20, 0, 0,
                    "INSANE", "MEDIUM",
                    INJECT_ALL, RPC_ALL, 0, 0);
            case "Balanced" -> balanced();
            default -> balanced();
        };
    }

    private static ScanConfig balanced() {
        return new ScanConfig(
                "Balanced",
                10, 5, true,
                true, 10, 10, 0,
                "HIGH", "MEDIUM",
                INJECT_ALL, RPC_ALL, 0, 0);
    }

    /**
     * The configuration the plain crawl ("Spider") button uses: a moderate crawl
     * with no audit settings that matter (it never audits). Mirrors "Fast"'s
     * discovery budget.
     */
    public static ScanConfig crawlDefault() {
        return preset("Fast");
    }
}
