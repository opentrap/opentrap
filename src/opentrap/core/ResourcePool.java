package opentrap.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A resource pool: how many requests a task may have in flight at once and
 * how it paces them. Tasks (scans, live tasks, Intruder attacks) pick a pool;
 * the pools themselves are managed under Settings > Project > Tasks. The
 * registry holds every pool for the project, starting with the default.
 */
public final class ResourcePool implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum DelayMode { FIXED, RANDOM, INCREMENT }

    public static final String DEFAULT_NAME = "Default resource pool";

    private String name;
    private boolean limitConcurrent = true;
    private int concurrent = 10;
    private boolean delayOn = false;
    private int delayMs = 0;
    private DelayMode delayMode = DelayMode.FIXED;
    private int incrementMs = 0;
    private boolean throttleOn = false;
    private boolean throttle429 = true;
    private boolean throttle503 = false;
    private String throttleOther = "";

    public ResourcePool(String name) { this.name = name; }

    public static ResourcePool defaultPool() {
        ResourcePool p = new ResourcePool(DEFAULT_NAME);
        p.throttleOn = true;
        return p;
    }

    public String name() { return name; }
    public void setName(String n) { name = n; }
    public boolean limitConcurrent() { return limitConcurrent; }
    public void setLimitConcurrent(boolean b) { limitConcurrent = b; }
    public int concurrent() { return concurrent; }
    public void setConcurrent(int n) { concurrent = Math.max(1, n); }
    public boolean delayOn() { return delayOn; }
    public void setDelayOn(boolean b) { delayOn = b; }
    public int delayMs() { return delayMs; }
    public void setDelayMs(int ms) { delayMs = Math.max(0, ms); }
    public DelayMode delayMode() { return delayMode; }
    public void setDelayMode(DelayMode m) { delayMode = m; }
    public int incrementMs() { return incrementMs; }
    public void setIncrementMs(int ms) { incrementMs = Math.max(0, ms); }
    public boolean throttleOn() { return throttleOn; }
    public void setThrottleOn(boolean b) { throttleOn = b; }
    public boolean throttle429() { return throttle429; }
    public void setThrottle429(boolean b) { throttle429 = b; }
    public boolean throttle503() { return throttle503; }
    public void setThrottle503(boolean b) { throttle503 = b; }
    public String throttleOther() { return throttleOther; }
    public void setThrottleOther(String s) { throttleOther = s == null ? "" : s; }

    /** The effective concurrency: the limit, or unbounded (a large number) when unlimited. */
    public int effectiveConcurrent() { return limitConcurrent ? concurrent : 50; }

    public String requestDelayText() {
        if (!delayOn) return "-";
        return delayMs + " ms";
    }
    public String randomDelayText() { return delayOn && delayMode == DelayMode.RANDOM ? "Yes" : "-"; }
    public String delayIncrementText() { return delayOn && delayMode == DelayMode.INCREMENT ? incrementMs + " ms" : "-"; }
    public String autoThrottleText() { return throttleOn ? "Yes" : "No"; }

    public ResourcePool copy() {
        ResourcePool p = new ResourcePool(name);
        p.limitConcurrent = limitConcurrent; p.concurrent = concurrent;
        p.delayOn = delayOn; p.delayMs = delayMs; p.delayMode = delayMode; p.incrementMs = incrementMs;
        p.throttleOn = throttleOn; p.throttle429 = throttle429; p.throttle503 = throttle503; p.throttleOther = throttleOther;
        return p;
    }

    @Override public String toString() { return name; }

    // ---- the project's registry of pools ----

    private static final List<ResourcePool> POOLS = new ArrayList<>(List.of(defaultPool()));
    private static final List<Runnable> LISTENERS = new ArrayList<>();

    public static synchronized List<ResourcePool> all() { return Collections.unmodifiableList(new ArrayList<>(POOLS)); }

    public static synchronized ResourcePool byName(String name) {
        for (ResourcePool p : POOLS) if (p.name.equals(name)) return p;
        return POOLS.get(0);
    }

    public static synchronized void add(ResourcePool p) {
        if (p == null) return;
        String base = p.name.isBlank() ? "Resource pool" : p.name;
        String n = base; int i = 2;
        while (byNameOrNull(n) != null) n = base + " " + (i++);
        p.name = n;
        POOLS.add(p);
        changed();
    }

    public static synchronized void remove(ResourcePool p) {
        if (p == null || DEFAULT_NAME.equals(p.name)) return;
        POOLS.remove(p);
        changed();
    }

    public static synchronized void replaceAll(List<ResourcePool> pools) {
        POOLS.clear();
        if (pools == null || pools.isEmpty()) POOLS.add(defaultPool()); else POOLS.addAll(pools);
        if (byNameOrNull(DEFAULT_NAME) == null) POOLS.add(0, defaultPool());
        changed();
    }

    private static ResourcePool byNameOrNull(String name) {
        for (ResourcePool p : POOLS) if (p.name.equals(name)) return p;
        return null;
    }

    public static synchronized void addListener(Runnable r) { LISTENERS.add(r); }
    public static void changed() {
        List<Runnable> ls;
        synchronized (ResourcePool.class) { ls = new ArrayList<>(LISTENERS); }
        for (Runnable r : ls) r.run();
    }
}
