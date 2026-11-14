package opentrap.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The suite-wide record of WebSocket frames, mirroring the HTTP MessageStore.
 *
 * <p>Frame payloads are the memory hazard of a long session: a busy socket
 * relays hundreds of thousands of frames and every payload used to sit in the
 * heap forever. Frames are therefore handled like HTTP history: when a disk
 * {@link ProjectStore} is attached, each payload is written to it on a single
 * background thread and dropped from the heap, leaving a small index
 * (metadata plus the payload's offset) in memory, and the payloads reload on
 * demand. Without a disk store, past {@link #setCapBytes(long) the cap} the
 * oldest frames are dropped as new ones arrive, exactly like the audit
 * traffic cap.
 */
public final class WebSocketStore {

    public interface Listener {
        void messageAdded(WebSocketMessage m);
        /** A frame was dropped to stay within the memory cap. */
        default void messageRemoved(WebSocketMessage m) {}
        /**
         * The whole history was replaced at once (a project was restored).
         * Listeners rebuild from {@code all} in one go rather than one frame
         * at a time, which is what keeps a large restore fast.
         */
        default void reloaded(List<WebSocketMessage> all) {}
    }

    private final List<WebSocketMessage> all = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long capBytes = ProjectOptions.DEFAULT_WS_MEMORY_MB * 1024L * 1024L;
    private long bytes;   // payload bytes held in the heap

    // Like MessageStore's body store: when set, each frame's payload is
    // written to disk and dropped from the heap, so a long session holds an
    // index rather than every payload. The write happens on a single
    // background thread so the relay thread never blocks on the disk.
    private volatile ProjectStore payloadStore;
    private volatile Offloader offloader;

    /** Turn on disk-backed payloads, writing them under {@code dir}. */
    public void useDiskStore(java.nio.file.Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path f = java.nio.file.Files.createTempFile(dir, "ws-", ".store");
            f.toFile().deleteOnExit();
            useStore(new ProjectStore(f, 32L * 1024 * 1024));   // 32 MB read cache
            Runtime.getRuntime().addShutdownHook(new Thread(this::closeStore, "ws-store-close"));
        } catch (java.io.IOException e) {
            opentrap.core.Debug.log("could not open disk frame store; keeping frames in memory: " + e);
        }
    }

    /**
     * Attach an already-open project store as the disk backing for frame
     * payloads, replacing any previous one (which is drained and closed).
     * Callers migrate any frames they want to keep into {@code store} before
     * switching, since frames still pointing at the old store would lose
     * their payloads.
     */
    public void useStore(ProjectStore store) {
        Offloader oldOff = offloader;
        ProjectStore oldStore = payloadStore;
        this.payloadStore = store;
        this.offloader = new Offloader(this, store);
        if (oldOff != null) oldOff.close();
        if (oldStore != null) { try { oldStore.close(); } catch (java.io.IOException ignore) {} }
    }

    /** The disk store backing frame payloads, or null when frames are kept in memory. */
    public ProjectStore diskStore() { return payloadStore; }

    /** Flush and close the disk store, draining anything still queued. */
    public void closeStore() {
        Offloader o = offloader;
        ProjectStore b = payloadStore;
        if (o != null) o.close();
        if (b != null) { try { b.close(); } catch (java.io.IOException ignore) {} }
    }

    public void add(WebSocketMessage m) {
        List<WebSocketMessage> evicted = null;
        synchronized (this) {
            all.add(m);
            bytes += m.length();
            evicted = evict();
            // Notify under the lock so listeners see frames in list order. The
            // frame still carries its payload here; the offload below moves it
            // to disk a moment later, transparently to every getter.
            for (Listener l : listeners) l.messageAdded(m);
            if (evicted != null) for (WebSocketMessage x : evicted) for (Listener l : listeners) l.messageRemoved(x);
        }
        // Offload the payload on the writer thread, outside the lock: when the
        // disk cannot keep up this applies back pressure to the relay thread
        // rather than letting the heap grow without bound.
        Offloader o = offloader;
        if (o != null) o.submit(m);
    }

    /**
     * Drop the oldest frames until the heap-resident payload bytes fit the
     * cap again. Frames already on disk are never dropped: their payloads
     * have left the heap, so keeping them costs nothing and dropping them
     * would lose them for good. That makes this a no-op whenever a disk store
     * is attached, which is the point: disk-backed frames are bounded by
     * offloading, not by deletion.
     */
    private List<WebSocketMessage> evict() {
        if (payloadStore != null || capBytes <= 0 || bytes <= capBytes) return null;
        List<WebSocketMessage> out = new ArrayList<>();
        java.util.Iterator<WebSocketMessage> it = all.iterator();
        while (it.hasNext() && bytes > capBytes) {
            WebSocketMessage x = it.next();
            if (x.offloaded()) continue;
            it.remove();
            bytes -= x.length();
            out.add(x);
        }
        return out.isEmpty() ? null : out;
    }

    /** Payload bytes kept in memory when no disk store is attached; 0 keeps everything. */
    public synchronized void setCapBytes(long cap) {
        capBytes = cap;
        List<WebSocketMessage> evicted = evict();
        if (evicted != null) for (WebSocketMessage x : evicted) for (Listener l : listeners) l.messageRemoved(x);
    }

    public synchronized long capBytes() { return capBytes; }

    /** Payload bytes currently held in the heap. */
    public synchronized long residentBytes() { return bytes; }

    /** The offloader reports each payload that has left the heap. */
    private void frameOffloaded(WebSocketMessage m) {
        synchronized (this) { bytes -= m.length(); }
    }

    /**
     * Every frame, oldest first, as one copy. The save and restore paths use
     * this; a view should not, because a session relayed for hours holds far
     * too many frames to pull at once.
     */
    public synchronized List<WebSocketMessage> all() { return new ArrayList<>(all); }

    /**
     * The newest {@code max} frames, oldest first: the bounded window a view
     * draws from, without copying the whole history.
     */
    public synchronized List<WebSocketMessage> recent(int max) {
        int n = all.size();
        int from = max <= 0 ? n : Math.max(0, n - max);
        return new ArrayList<>(all.subList(from, n));
    }

    public synchronized WebSocketMessage get(int i) {
        return (i >= 0 && i < all.size()) ? all.get(i) : null;
    }

    public synchronized int size() { return all.size(); }

    public synchronized void clear() { all.clear(); bytes = 0; }

    /**
     * Replace the whole history (a project was restored). Listeners get one
     * {@code reloaded} with the new list rather than an event per frame.
     */
    public void replaceAll(List<WebSocketMessage> list) {
        List<WebSocketMessage> copy;
        synchronized (this) {
            all.clear();
            if (list != null) all.addAll(list);
            bytes = 0;
            for (WebSocketMessage m : all) if (!m.offloaded()) bytes += m.length();
            copy = new ArrayList<>(all);
        }
        for (Listener l : listeners) l.reloaded(copy);
    }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /**
     * The single writer that moves frame payloads to the project file off the
     * relay thread, mirroring MessageStore's offloader. Every frame queues
     * here; one thread drains the queue, writing a payload record and a frame
     * index record, then fsyncs on an interval. A byte budget bounds how much
     * un-written traffic may pile up, so a disk that cannot keep up slows the
     * relay instead of growing the heap.
     */
    private static final class Offloader {
        private static final long PENDING_CAP = 64L * 1024 * 1024;
        private static final long SYNC_INTERVAL_MS = 2000;

        private final WebSocketStore owner;
        private final ProjectStore store;
        private final BlockingQueue<WebSocketMessage> queue = new LinkedBlockingQueue<>();
        private final Object gate = new Object();
        private long pendingBytes;
        private volatile boolean running = true;
        private final Thread thread;

        Offloader(WebSocketStore owner, ProjectStore store) {
            this.owner = owner;
            this.store = store;
            this.thread = new Thread(this::run, "ws-offload");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void submit(WebSocketMessage m) {
            long sz = m.length();
            synchronized (gate) {
                while (running && pendingBytes > PENDING_CAP) {
                    try { gate.wait(); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
                pendingBytes += sz;
            }
            queue.add(m);
        }

        private void run() {
            long lastSync = System.currentTimeMillis();
            while (running || !queue.isEmpty()) {
                WebSocketMessage m = null;
                try { m = queue.poll(1, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                if (m != null) {
                    try { apply(m); }
                    catch (Exception ex) { Debug.log("could not persist a websocket frame: " + ex); }
                    synchronized (gate) { pendingBytes -= m.length(); gate.notifyAll(); }
                }
                long now = System.currentTimeMillis();
                if (now - lastSync >= SYNC_INTERVAL_MS) {
                    try { store.sync(); } catch (Exception ex) { Debug.log("project store sync failed: " + ex); }
                    lastSync = now;
                }
            }
            try { store.sync(); } catch (Exception ignore) {}
        }

        private void apply(WebSocketMessage m) throws java.io.IOException {
            m.offload(store);
            store.append(ProjectStore.WS, m.indexBytes());
            if (owner != null) owner.frameOffloaded(m);
        }

        void close() {
            running = false;
            synchronized (gate) { gate.notifyAll(); }
            // Wait generously for the queue to drain so a clean shutdown
            // flushes every frame relayed; the queue is bounded, so this
            // terminates well within the bound even on a slow disk.
            try { thread.join(30000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }
}
