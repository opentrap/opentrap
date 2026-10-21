package opentrap.session;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * A session-handling rule: which traffic it governs, whether it applies the
 * cookie jar, and the condition under which it re-runs a macro to re-authenticate.
 */
public final class SessionRule implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Trigger { NONE, STATUS, CONTAINS }

    public String name = "rule";
    public boolean enabled = true;
    public String hostPattern = "";                      // substring, or a *.example glob; empty = all hosts
    public final Set<String> tools = new LinkedHashSet<>(); // empty = all tools
    public boolean useCookieJar = true;                  // add jar cookies to outgoing requests
    public String macroName = "";                        // macro run to (re)establish the session
    public Trigger trigger = Trigger.STATUS;             // when to re-run the macro
    public String triggerValue = "401,403";             // STATUS: comma-separated codes; CONTAINS: response text

    public boolean appliesTo(String tool, HttpService svc) {
        if (!enabled || svc == null) return false;
        if (!tools.isEmpty() && (tool == null || !tools.contains(tool))) return false;
        if (hostPattern != null && !hostPattern.isBlank()) {
            return hostGlob(hostPattern.trim().toLowerCase(Locale.ROOT),
                    svc.host() == null ? "" : svc.host().toLowerCase(Locale.ROOT));
        }
        return true;
    }

    private static boolean hostGlob(String pat, String host) {
        if (pat.startsWith("*.")) {
            String base = pat.substring(2);
            return host.equals(base) || host.endsWith("." + base);
        }
        if (pat.indexOf('*') >= 0) {
            StringBuilder rx = new StringBuilder();
            for (String piece : pat.split("\\*", -1)) {
                if (rx.length() > 0) rx.append(".*");
                rx.append(Pattern_quote(piece));
            }
            try { return host.matches(rx.toString()); } catch (Exception e) { return host.contains(pat.replace("*", "")); }
        }
        return host.contains(pat);
    }

    private static String Pattern_quote(String s) { return s.isEmpty() ? "" : java.util.regex.Pattern.quote(s); }

    /** True when this response should trigger a macro re-run (re-authentication). */
    public boolean triggered(HttpMessage resp) {
        if (resp == null || trigger == Trigger.NONE || macroName == null || macroName.isBlank()) return false;
        if (trigger == Trigger.STATUS) {
            int sc = resp.statusCode();
            for (String s : triggerValue.split(",")) {
                try { if (Integer.parseInt(s.trim()) == sc) return true; } catch (Exception ignored) {}
            }
            return false;
        }
        return triggerValue != null && !triggerValue.isEmpty() && resp.bodyText().contains(triggerValue);
    }

    @Override public String toString() { return name; }
}
