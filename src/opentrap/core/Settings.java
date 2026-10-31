package opentrap.core;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

/** String-keyed settings with typed accessors, persisted to java.util.prefs. */
public class Settings implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final String NODE = "opentrap.v1";

    private final Map<String, String> values = new LinkedHashMap<>();
    private transient Preferences prefs;
    // Told the key each time a value changes, so a control elsewhere (the proxy
    // server, a banner) can follow a setting without polling it.
    private transient List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    public Settings() {
        try { prefs = Preferences.userRoot().node(NODE); } catch (Exception ignored) {}
    }

    public synchronized String get(String key, String def) {
        String v = values.get(key);
        if (v != null) return v;
        if (prefs != null) {
            try {
                String p = prefs.get(key, null);
                if (p != null) { values.put(key, p); return p; }
            } catch (Exception ignored) {}
        }
        return def;
    }

    public void set(String key, String value) {
        synchronized (this) {
            values.put(key, value);
            if (prefs != null) {
                try { prefs.put(key, value); } catch (Exception ignored) {}
            }
        }
        for (Consumer<String> l : listeners()) l.accept(key);
    }

    public boolean getBool(String k, boolean def) { return Boolean.parseBoolean(get(k, String.valueOf(def))); }
    public void setBool(String k, boolean v)      { set(k, String.valueOf(v)); }

    public int getInt(String k, int def) {
        try { return Integer.parseInt(get(k, String.valueOf(def))); } catch (Exception e) { return def; }
    }
    public void setInt(String k, int v) { set(k, String.valueOf(v)); }

    public synchronized Map<String, String> snapshot() { return new LinkedHashMap<>(values); }

    public void restore(Map<String, String> m) {
        if (m == null) return;
        for (Map.Entry<String, String> e : m.entrySet()) set(e.getKey(), e.getValue());
    }

    /** Be told the key whenever a value is set, on the thread that set it. */
    public void addListener(Consumer<String> l) { listeners().add(l); }

    /** Stop being told. A listener registered by a short-lived component must
     *  be removed when that component goes, or Settings retains it forever. */
    public void removeListener(Consumer<String> l) {
        if (listeners != null) listeners.remove(l);
    }

    private List<Consumer<String>> listeners() {
        if (listeners == null) listeners = new CopyOnWriteArrayList<>();
        return listeners;
    }
}
