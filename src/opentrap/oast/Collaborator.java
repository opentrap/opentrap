package opentrap.oast;

import opentrap.core.Settings;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs an {@link Interactsh} client in the background and holds the interactions
 * it collects, so the whole suite shares one collaborator session.
 *
 * <p>The active scanner asks this for payloads and correlates callbacks back to
 * its probes; the collaborator tab shows the same interactions live. A single
 * registration and one poll loop serve both.
 *
 * <p>Nothing starts until {@link #start} is called, and a failed registration
 * is surfaced rather than retried in a tight loop, because a public server may
 * be rate-limiting or down and the right response is to tell the user.
 */
public final class Collaborator {

    /** Notified as interactions arrive and as the status changes. */
    public interface Listener {
        void interactionsAdded(List<Interaction> added);
        void statusChanged(String status);
    }

    public static final String SERVER_KEY = "oast.server";
    public static final String TOKEN_KEY  = "oast.token";
    public static final String DEFAULT_SERVER = "https://oast.pro";

    private static final long POLL_INTERVAL_MS = 5000;

    private final Settings settings;
    private final List<Interaction> interactions = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private volatile Interactsh client;
    private volatile Thread poller;
    private volatile boolean running;
    private volatile String status = "not started";

    public Collaborator(Settings settings) {
        this.settings = settings;
    }

    public void addListener(Listener l) { listeners.add(l); }

    public String status()      { return status; }
    public boolean isRunning()  { return running && client != null && client.isRegistered(); }

    /** The configured server URL, or the public default. */
    public String serverUrl() {
        String s = settings == null ? null : settings.get(SERVER_KEY, DEFAULT_SERVER);
        return s == null || s.isBlank() ? DEFAULT_SERVER : s;
    }

    private String token() {
        return settings == null ? "" : settings.get(TOKEN_KEY, "");
    }

    /**
     * Register with the server and begin polling. Safe to call again to restart
     * against a changed server. Returns null on success or an error message.
     */
    public synchronized String start() {
        stop();
        try {
            Interactsh c = new Interactsh(serverUrl(), token());
            c.register();
            client = c;
            running = true;
            setStatus("registered with " + c.serverHost());
            poller = new Thread(this::pollLoop, "oast-poller");
            poller.setDaemon(true);
            poller.start();
            return null;
        } catch (Exception e) {
            setStatus("registration failed: " + e.getMessage());
            return e.getMessage();
        }
    }

    public synchronized void stop() {
        running = false;
        if (poller != null) { poller.interrupt(); poller = null; }
        client = null;
    }

    /**
     * A fresh payload hostname for a check to plant, or null when the
     * collaborator is not running. The check matches a later interaction by the
     * nonce in the host.
     */
    public String payloadHost() {
        Interactsh c = client;
        return c != null && c.isRegistered() ? c.newPayloadHost() : null;
    }

    public String payloadUrl() {
        Interactsh c = client;
        return c != null && c.isRegistered() ? c.newPayloadUrl() : null;
    }

    public String correlationId() {
        Interactsh c = client;
        return c == null ? "" : c.correlationId();
    }

    public List<Interaction> interactions() { return List.copyOf(interactions); }

    /** Interactions whose host carries the given nonce, for a check's correlation. */
    public List<Interaction> interactionsFor(String nonce) {
        List<Interaction> out = new java.util.ArrayList<>();
        String corr = correlationId();
        for (Interaction i : interactions) {
            if (i.nonce(corr).equals(nonce)) out.add(i);
        }
        return out;
    }

    public void clear() { interactions.clear(); }

    private void pollLoop() {
        while (running) {
            try {
                Interactsh c = client;
                if (c == null) break;
                List<Interaction> fresh = c.poll();
                if (!fresh.isEmpty()) {
                    interactions.addAll(fresh);
                    for (Listener l : listeners) l.interactionsAdded(fresh);
                }
            } catch (Exception e) {
                setStatus("poll error: " + e.getMessage());
            }
            synchronized (pollLock) {
                try { pollLock.wait(POLL_INTERVAL_MS); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private final Object pollLock = new Object();

    /** Poll the server right away instead of waiting out the interval. */
    public void pollNow() {
        synchronized (pollLock) { pollLock.notifyAll(); }
    }

    private void setStatus(String s) {
        this.status = s;
        for (Listener l : listeners) l.statusChanged(s);
    }
}
