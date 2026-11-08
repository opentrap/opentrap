package opentrap.ui;

/**
 * Classifies the "engagement tool" sinks, which the reference groups in an
 * Engagement tools submenu rather than listing flat beside Repeater and Intruder.
 */
public final class Engagement {
    private Engagement() {}

    /** True for a sink that belongs in the Engagement tools submenu. */
    public static boolean isEngagement(String t) {
        return "CSRF PoC".equals(t) || "content discovery".equals(t);
    }

    /**
     * True for an engagement tool that needs one specific request to work on, so
     * it only makes sense where there is a single request (a CSRF PoC), not on a
     * host or folder. Content discovery works from a path, so it does not.
     */
    public static boolean needsRequest(String t) {
        return "CSRF PoC".equals(t);
    }

    /** The submenu label for an engagement tool. */
    public static String label(String t) {
        return switch (t) {
            case "CSRF PoC" -> "Generate CSRF PoC";
            case "content discovery" -> "Discover content";
            default -> t;
        };
    }
}
