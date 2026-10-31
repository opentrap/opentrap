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

    /** Forget a key so reads fall back to the default again. */
    public void remove(String key) {
        synchronized (this) {
            values.remove(key);
            if (prefs != null) {
                try { prefs.remove(key); } catch (Exception ignored) {}
            }
        }
        for (Consumer<String> l : listeners()) l.accept(key);
    }

    /** Every key currently set, including those only in the preferences store. */
    public synchronized java.util.Set<String> keys() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>(values.keySet());
        if (prefs != null) { try { out.addAll(java.util.Arrays.asList(prefs.keys())); } catch (Exception ignored) {} }
        return out;
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

    private List<Consumer<String>> listeners() {
        if (listeners == null) listeners = new CopyOnWriteArrayList<>();
        return listeners;
    }
}
