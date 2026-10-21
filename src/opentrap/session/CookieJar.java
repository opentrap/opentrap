package opentrap.session;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The suite-wide cookie jar. Every tool that sends through the session hook
 * feeds its responses here, so cookies observed in the proxy are available to
 * Repeater and Intruder without the tester copying them by hand.
 */
public final class CookieJar implements Serializable {
    private static final long serialVersionUID = 1L;

    private final Map<String, Cookie> cookies = new LinkedHashMap<>();

    public synchronized void set(Cookie c) {
        if (c == null || c.name == null || c.name.isEmpty()) return;
        cookies.put(c.key(), c);
    }

    public synchronized void remove(Cookie c) { if (c != null) cookies.remove(c.key()); }
    public synchronized void clear() { cookies.clear(); }
    public synchronized List<Cookie> all() { return new ArrayList<>(cookies.values()); }
    public synchronized int size() { return cookies.size(); }

    /** Store every Set-Cookie from a response against the host that sent it. */
    public synchronized void updateFrom(HttpService svc, HttpMessage resp) {
        if (svc == null || resp == null) return;
        for (String line : resp.headerLines()) {
            if (line.toLowerCase(Locale.ROOT).startsWith("set-cookie:")) {
                Cookie c = parseSetCookie(line.substring("set-cookie:".length()).trim(), svc.host());
                if (c != null) set(c);
            }
        }
    }

    private static Cookie parseSetCookie(String v, String host) {
        String[] parts = v.split(";");
        if (parts.length == 0) return null;
        int eq = parts[0].indexOf('=');
        if (eq < 0) return null;
        Cookie c = new Cookie();
        c.name = parts[0].substring(0, eq).trim();
        c.value = parts[0].substring(eq + 1).trim();
        c.domain = host == null ? "" : host;
        c.path = "/";
        for (int i = 1; i < parts.length; i++) {
            String a = parts[i].trim();
            String al = a.toLowerCase(Locale.ROOT);
            if (al.startsWith("domain=")) c.domain = a.substring(7).trim();
            else if (al.startsWith("path=")) c.path = a.substring(5).trim();
            else if (al.equals("secure")) c.secure = true;
            else if (al.startsWith("max-age=")) {
                try { c.expires = System.currentTimeMillis() + Long.parseLong(a.substring(8).trim()) * 1000L; }
                catch (Exception ignored) {}
            } else if (al.startsWith("expires=")) {
                try {
                    c.expires = java.time.ZonedDateTime.parse(a.substring(8).trim(),
                            java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
                } catch (Exception ignored) {}
            }
        }
        return c.name.isEmpty() ? null : c;
    }

    /**
     * The Cookie header value for a request: jar cookies that match the host and
     * path, merged over any cookies already on the request (jar wins on a clash).
     * Returns null when nothing from the jar applies and the request had none.
     */
    public synchronized String headerFor(HttpService svc, HttpMessage req, String existingCookie) {
        LinkedHashMap<String, String> merged = new LinkedHashMap<>();
        if (existingCookie != null) {
            for (String p : existingCookie.split(";")) {
                String t = p.trim();
                if (t.isEmpty()) continue;
                int eq = t.indexOf('=');
                if (eq < 0) merged.put(t, "");
                else merged.put(t.substring(0, eq).trim(), t.substring(eq + 1).trim());
            }
        }
        String path = req == null ? "/" : req.path();
        boolean anyFromJar = false;
        for (Cookie c : cookies.values()) {
            if (c.matches(svc, path)) { merged.put(c.name, c.value); anyFromJar = true; }
        }
        if (!anyFromJar && existingCookie == null) return null;
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : merged.entrySet()) {
            if (sb.length() > 0) sb.append("; ");
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        return sb.toString();
    }
}
