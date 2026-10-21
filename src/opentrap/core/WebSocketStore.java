package opentrap.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The suite-wide record of WebSocket frames, mirroring the HTTP MessageStore. */
public final class WebSocketStore {

    public interface Listener { void messageAdded(WebSocketMessage m); }

    private final List<WebSocketMessage> all = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void add(WebSocketMessage m) {
        synchronized (this) { all.add(m); }
        for (Listener l : listeners) l.messageAdded(m);
    }

    public synchronized List<WebSocketMessage> all() { return new ArrayList<>(all); }
    public synchronized int size() { return all.size(); }
    public synchronized void clear() { all.clear(); }
    public void addListener(Listener l) { listeners.add(l); }
}
