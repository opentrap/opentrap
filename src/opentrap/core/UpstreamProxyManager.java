package opentrap.core;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the upstream-proxy rules and resolves which (if any) applies to a given
 * destination. Rules persist in settings, so they travel with the project and
 * across restarts. Installed as the {@link HttpClient.ProxyProvider}.
 */
public final class UpstreamProxyManager implements HttpClient.ProxyProvider {

    private static final String KEY = "upstream.proxies";

    private final Settings settings;
    private final List<UpstreamProxy> rules = new CopyOnWriteArrayList<>();

    public UpstreamProxyManager(Settings settings) {
        this.settings = settings;
        load();
    }

    public List<UpstreamProxy> rules() { return rules; }

    @Override public UpstreamProxy proxyFor(HttpService svc) {
        for (UpstreamProxy p : rules) if (p.matches(svc)) return p;
        return null;
    }

    public void load() {
        rules.clear();
        String raw = settings.get(KEY, "");
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            if (line.isBlank()) continue;
            String[] f = line.split("\t", -1);
            if (f.length < 7) continue;
            try {
                UpstreamProxy p = new UpstreamProxy();
                p.enabled = Boolean.parseBoolean(f[0]);
                p.type = UpstreamProxy.Type.valueOf(f[1]);
                p.host = f[2];
                p.port = Integer.parseInt(f[3]);
                p.user = dec(f[4]);
                p.pass = dec(f[5]);
                p.hostPattern = f[6];
                rules.add(p);
            } catch (Exception ignored) {}
        }
    }

    public void save() {
        StringBuilder sb = new StringBuilder();
        for (UpstreamProxy p : rules) {
            sb.append(p.enabled).append('\t').append(p.type).append('\t').append(p.host).append('\t')
              .append(p.port).append('\t').append(enc(p.user)).append('\t').append(enc(p.pass)).append('\t')
              .append(p.hostPattern == null ? "" : p.hostPattern).append('\n');
        }
        settings.set(KEY, sb.toString());
    }

    // Base64 the credentials so a tab or newline in them cannot corrupt the record.
    private static String enc(String s) { return s == null ? "" : Base64.getEncoder().encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
    private static String dec(String s) {
        if (s == null || s.isEmpty()) return "";
        try { return new String(Base64.getDecoder().decode(s), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }
}
