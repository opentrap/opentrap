package opentrap.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Central history of every exchange any tool performs.
 *
 * Kept in memory deliberately: v1.2 did the same, and it keeps the project
 * file a single serialisable object graph. Listeners are notified on the EDT
 * by the UI layer, not here.
 *
 * <p>The one kind of traffic that is capped is the audit's own probe traffic
 * (tool {@link ProjectOptions#AUDIT_TOOL}): an active audit sends thousands of
 * requests per endpoint, so past {@link #setAuditCapBytes(long) the cap} the
 * oldest probes are dropped as new ones arrive. Everything else is kept.
 */
public class MessageStore {
    public interface Listener {
        void entryAdded(LogEntry e);
        void entryUpdated(LogEntry e);
        /** An entry was dropped to stay within the audit traffic cap. */
        default void entryRemoved(LogEntry e) {}
        /**
         * The whole history was replaced at once (a project was restored).
         * Listeners rebuild from {@code all} in one go rather than one entry at
         * a time, which is what keeps a large restore fast.
         */
        default void reloaded(List<LogEntry> all) {}
    }

    private final List<LogEntry> entries = new ArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private long auditCapBytes = ProjectOptions.DEFAULT_AUDIT_MEMORY_MB * 1024L * 1024L;
    private long auditBytes;

    // Issue #37: when set, each added entry's request and response bytes are
    // written to disk and dropped from the heap, so a long session holds an
    // index rather than every body. Off by default, so a plain store (and every
    // test) behaves exactly as before. The write happens on a single background
    // thread so the recording thread never blocks on the disk.
    private volatile ProjectStore bodyStore;
    private volatile Offloader offloader;

    /** Turn on disk-backed bodies, writing them under {@code dir}. */
    public void useDiskStore(java.nio.file.Path dir) {
        try {
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path f = java.nio.file.Files.createTempFile(dir, "traffic-", ".store");
            f.toFile().deleteOnExit();
            useStore(new ProjectStore(f, 32L * 1024 * 1024));   // 32 MB read cache
            Runtime.getRuntime().addShutdownHook(new Thread(this::closeStore, "traffic-store-close"));
        } catch (java.io.IOException e) {
            opentrap.core.Debug.log("could not open disk body store; keeping traffic in memory: " + e);
        }
    }

    /**
     * Attach an already-open project store as the disk backing for recorded
     * bodies, replacing any previous one (which is drained and closed). Callers
     * migrate any entries they want to keep into {@code store} before switching,
     * since entries still pointing at the old store would lose their bodies.
     */
    public void useStore(ProjectStore store) {
        Offloader oldOff = offloader;
        ProjectStore oldStore = bodyStore;
        this.bodyStore = store;
        this.offloader = new Offloader(store);
        if (oldOff != null) oldOff.close();
        if (oldStore != null) { try { oldStore.close(); } catch (java.io.IOException ignore) {} }
    }

    /** The disk store backing recorded bodies, or null when traffic is kept in memory. */
    public ProjectStore diskStore() { return bodyStore; }

    /** Flush and close the disk store, draining anything still queued. */
    public void closeStore() {
        Offloader o = offloader;
        ProjectStore b = bodyStore;
        if (o != null) o.close();
        if (b != null) { try { b.close(); } catch (java.io.IOException ignore) {} }
    }

    public void add(LogEntry e) {
        boolean audit = ProjectOptions.AUDIT_TOOL.equals(e.tool());
        List<LogEntry> evicted = null;
        synchronized (this) {
            entries.add(e);
            if (audit) {
                auditBytes += e.bytes();
                evicted = evictAudit();
            }
            // Notify under the lock so listeners see entries in list order. The
            // entry still carries its bodies here; the offload below moves them
            // to disk a moment later, transparently to every getter.
            for (Listener l : listeners) l.entryAdded(e);
            if (evicted != null) for (LogEntry x : evicted) for (Listener l : listeners) l.entryRemoved(x);
        }
        // Offload non-audit bodies on the writer thread (audit traffic is memory
        // capped and dropped, so writing it would be wasted). Done outside the
        // lock: when the disk cannot keep up this applies back pressure to the
        // recording thread rather than letting the heap grow without bound.
        Offloader o = offloader;
        if (o != null && !audit) o.submit(Offloader.ADD, e);
    }

    /** Drop the oldest audit probes until the audit traffic fits the cap again. */
    private List<LogEntry> evictAudit() {
        if (auditCapBytes <= 0 || auditBytes <= auditCapBytes) return null;
        List<LogEntry> out = new ArrayList<>();
        java.util.Iterator<LogEntry> it = entries.iterator();
        while (it.hasNext() && auditBytes > auditCapBytes) {
            LogEntry x = it.next();
            if (!ProjectOptions.AUDIT_TOOL.equals(x.tool())) continue;
            it.remove();
            auditBytes -= x.bytes();
            out.add(x);
        }
        return out.isEmpty() ? null : out;
    }

    /** Audit traffic kept in memory, in bytes; 0 keeps everything. */
    public synchronized void setAuditCapBytes(long bytes) {
        auditCapBytes = bytes;
        List<LogEntry> evicted = evictAudit();
        if (evicted != null) for (LogEntry x : evicted) for (Listener l : listeners) l.entryRemoved(x);
    }

    public synchronized long auditCapBytes() { return auditCapBytes; }

    /** Bytes of audit traffic currently held. */
    public synchronized long auditBytes() { return auditBytes; }

    public void updated(LogEntry e) {
        Offloader o = offloader;
        if (o != null && !ProjectOptions.AUDIT_TOOL.equals(e.tool())) o.submit(Offloader.UPDATE, e);
        for (Listener l : listeners) l.entryUpdated(e);
    }

    public synchronized List<LogEntry> all() { return new ArrayList<>(entries); }

    public synchronized int size() { return entries.size(); }

    public synchronized LogEntry get(int i) {
        return (i >= 0 && i < entries.size()) ? entries.get(i) : null;
    }

    public synchronized void clear() { entries.clear(); auditBytes = 0; }

    public void remove(LogEntry e) {
        boolean removed;
        synchronized (this) {
            removed = entries.remove(e);
            if (removed && ProjectOptions.AUDIT_TOOL.equals(e.tool())) auditBytes -= e.bytes();
        }
        Offloader o = offloader;
        if (removed && o != null && !ProjectOptions.AUDIT_TOOL.equals(e.tool())) o.submit(Offloader.REMOVE, e);
    }

    public void replaceAll(List<LogEntry> list) {
        List<LogEntry> copy;
        synchronized (this) {
            entries.clear();
            entries.addAll(list);
            auditBytes = 0;
            for (LogEntry e : entries) if (ProjectOptions.AUDIT_TOOL.equals(e.tool())) auditBytes += e.bytes();
            copy = new ArrayList<>(entries);
        }
        for (Listener l : listeners) l.reloaded(copy);
    }

    public void addListener(Listener l)    { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    /**
     * The single writer that moves recorded traffic to the project file off the
     * recording thread. Every change queues here as a task; one thread drains
     * the queue, writing body records and an index record on an add, a fresh
     * index record on an update, and a tombstone on a remove, then fsyncs on an
     * interval. A byte budget bounds how much un-written traffic may pile up, so
     * a disk that cannot keep up slows recording instead of growing the heap.
     */
    private static final class Offloader {
        static final byte ADD = 0, UPDATE = 1, REMOVE = 2;
        private static final long PENDING_CAP = 64L * 1024 * 1024;
        private static final long SYNC_INTERVAL_MS = 2000;

        private record Task(byte kind, LogEntry entry, long bytes) {}

        private final ProjectStore store;
        private final BlockingQueue<Task> queue = new LinkedBlockingQueue<>();
        private final Object gate = new Object();
        private long pendingBytes;
        private volatile boolean running = true;
        private final Thread thread;

        Offloader(ProjectStore store) {
            this.store = store;
            this.thread = new Thread(this::run, "traffic-offload");
            this.thread.setDaemon(true);
            this.thread.start();
        }

        void submit(byte kind, LogEntry e) {
            long sz = kind == REMOVE ? 0 : e.bytes();
            synchronized (gate) {
                while (running && pendingBytes > PENDING_CAP) {
                    try { gate.wait(); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
                }
                pendingBytes += sz;
            }
            queue.add(new Task(kind, e, sz));
        }

        private void run() {
            long lastSync = System.currentTimeMillis();
            while (running || !queue.isEmpty()) {
                Task t = null;
                try { t = queue.poll(1, TimeUnit.SECONDS); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                if (t != null) {
                    try { apply(t); }
                    catch (Exception ex) { Debug.log("could not persist entry " + t.entry().number() + ": " + ex); }
                    synchronized (gate) { pendingBytes -= t.bytes(); gate.notifyAll(); }
                }
                long now = System.currentTimeMillis();
                if (now - lastSync >= SYNC_INTERVAL_MS) {
                    try { store.sync(); } catch (Exception ex) { Debug.log("project store sync failed: " + ex); }
                    lastSync = now;
                }
            }
            try { store.sync(); } catch (Exception ignore) {}
        }

        private void apply(Task t) throws java.io.IOException {
            LogEntry e = t.entry();
            switch (t.kind()) {
                case ADD -> { e.offload(store); store.append(ProjectStore.ENTRY, e.indexBytes()); }
                case UPDATE -> {
                    if (e.bodiesResident()) e.offload(store);   // a later-arriving body is written now
                    store.append(ProjectStore.ENTRY, e.indexBytes());
                }
                case REMOVE -> {
                    byte[] n = new byte[4];
                    int v = e.number();
                    n[0] = (byte) (v >>> 24); n[1] = (byte) (v >>> 16); n[2] = (byte) (v >>> 8); n[3] = (byte) v;
                    store.append(ProjectStore.TOMBSTONE, n);
                }
            }
        }

        void close() {
            running = false;
            synchronized (gate) { gate.notifyAll(); }
            // Wait generously for the queue to drain so a clean shutdown flushes
            // everything recorded; the queue is bounded, so this terminates well
            // within the bound even on a slow disk.
            try { thread.join(30000); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
    }
}
