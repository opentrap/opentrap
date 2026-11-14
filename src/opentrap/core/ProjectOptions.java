package opentrap.core;

/**
 * The settings that decide what a project keeps and what the proxy records.
 *
 * <p>They are ordinary {@link Settings} keys, so they travel in the project
 * file like every other setting; this class only names them and their
 * defaults in one place.
 *
 * <p>The defaults follow the shape of a scan: the crawl's pages and everything
 * done by hand are kept in full, the audit's own probe traffic is kept as
 * headers only (every finding already carries its full request and response),
 * and the probe traffic held in memory during a session is capped so a long
 * audit cannot grow the heap without limit. WebSocket frames are bounded the
 * same way: their payloads spill to the project store like HTTP bodies, and
 * the cap drops the oldest only while they must stay in memory.
 */
public final class ProjectOptions {
    private ProjectOptions() {}

    /** Keep the full response body of every audit request in the project file. Off by default. */
    public static final String KEEP_AUDIT_BODIES = "project.keepAuditBodies";
    /** Largest response body written to the project, in KB. 0 means no cap. */
    public static final String BODY_CAP_KB = "project.bodyCapKb";
    /** How much audit traffic the session keeps in memory, in MB; the oldest is dropped past this. */
    public static final String AUDIT_MEMORY_MB = "project.auditMemoryMb";
    /** How much WebSocket frame payload the session keeps in memory, in MB; the oldest is dropped past this. */
    public static final String WS_MEMORY_MB = "project.wsMemoryMb";
    /** Whether the proxy records traffic that is outside the target scope. */
    public static final String LOG_OUT_OF_SCOPE = "proxy.logOutOfScope";
    /**
     * What to do when an item is added to scope: {@code ask}, {@code stop}
     * (stop recording out-of-scope traffic) or {@code keep}.
     */
    public static final String OUT_OF_SCOPE_CHOICE = "proxy.outOfScopeChoice";
    /** Whether the scope tab shows the regex rule editor rather than URL prefixes. */
    public static final String SCOPE_ADVANCED = "scope.advanced";

    public static final int DEFAULT_AUDIT_MEMORY_MB = 100;
    public static final int DEFAULT_WS_MEMORY_MB = 32;

    /** The tool tag the engine gives its audit probes; the one kind of traffic saved headers-only. */
    public static final String AUDIT_TOOL = "audit";

    public static boolean keepAuditBodies(Settings s) { return s.getBool(KEEP_AUDIT_BODIES, false); }

    /** The body cap in bytes, or 0 for none. */
    public static int bodyCapBytes(Settings s) {
        int kb = s.getInt(BODY_CAP_KB, 0);
        return kb <= 0 ? 0 : kb * 1024;
    }

    /** The in-memory audit cap in bytes, or 0 for none. */
    public static long auditMemoryBytes(Settings s) {
        int mb = s.getInt(AUDIT_MEMORY_MB, DEFAULT_AUDIT_MEMORY_MB);
        return mb <= 0 ? 0 : mb * 1024L * 1024L;
    }

    /** The in-memory WebSocket cap in bytes, or 0 for none. */
    public static long wsMemoryBytes(Settings s) {
        int mb = s.getInt(WS_MEMORY_MB, DEFAULT_WS_MEMORY_MB);
        return mb <= 0 ? 0 : mb * 1024L * 1024L;
    }

    public static boolean logOutOfScope(Settings s) { return s.getBool(LOG_OUT_OF_SCOPE, true); }

    public static String outOfScopeChoice(Settings s) { return s.get(OUT_OF_SCOPE_CHOICE, "ask"); }
}
