package opentrap.core;

import java.util.Locale;

/**
 * One upstream-proxy rule: for destinations matching the host pattern, route
 * outbound traffic through this HTTP (CONNECT) or SOCKS proxy.
 */
public final class UpstreamProxy {

    public enum Type { HTTP, SOCKS }

    public boolean enabled = true;
    public Type type = Type.HTTP;
    public String host = "";
    public int port = 8080;
    public String user = "";
    public String pass = "";
    public String hostPattern = "";  // destination host glob; empty = all destinations

    public UpstreamProxy() {}

    public boolean matches(HttpService svc) {
        if (!enabled || host.isEmpty() || svc == null) return false;
        if (hostPattern == null || hostPattern.isBlank()) return true;
        return hostGlob(hostPattern.trim().toLowerCase(Locale.ROOT),
                svc.host() == null ? "" : svc.host().toLowerCase(Locale.ROOT));
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
                if (!piece.isEmpty()) rx.append(java.util.regex.Pattern.quote(piece));
            }
            try { return host.matches(rx.toString()); } catch (Exception e) { return host.contains(pat.replace("*", "")); }
        }
        return host.contains(pat);
    }

    @Override public String toString() {
        return (enabled ? "" : "(off) ") + type + " " + host + ":" + port
                + (hostPattern.isBlank() ? " [all]" : " [" + hostPattern + "]");
    }
}
