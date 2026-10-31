package opentrap.proxy;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Hands a message to the UI and blocks the connection thread until the user
 * forwards or drops it. One item is presented at a time, which is what makes
 * the intercept tab comprehensible.
 */
public class InterceptQueue {

    public enum Action { FORWARD, DROP }

    public static class Pending {
        public final HttpService service;
        public final HttpMessage message;
        public final boolean isRequest;
        public final HttpMessage relatedRequest;
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        private volatile Action action = Action.FORWARD;
        private volatile byte[] edited;

        public Pending(HttpService service, HttpMessage message, boolean isRequest, HttpMessage relatedRequest) {
            this.service = service; this.message = message;
            this.isRequest = isRequest; this.relatedRequest = relatedRequest;
        }

        public void resolve(Action a, byte[] editedBytes) {
            this.action = a;
            this.edited = editedBytes;
            latch.countDown();
        }

        public Action await() {
            try { latch.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            return action;
        }

        public byte[] editedBytes() { return edited; }
        public boolean wasEdited()  { return edited != null && !java.util.Arrays.equals(edited, message.raw()); }
    }

    public interface Presenter { void present(Pending p); void cleared(Pending p); }

    private final LinkedBlockingQueue<Pending> queue = new LinkedBlockingQueue<>();
    private volatile Presenter presenter;
    private volatile Pending current;

    public void setPresenter(Presenter p) { this.presenter = p; }

    /** Called on a connection thread. Blocks until the user decides. */
    public Action submit(Pending p) {
        try {
            queue.put(p);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Action.FORWARD;
        }
        pump();
        return p.await();
    }

    private synchronized void pump() {
        if (current != null) return;
        Pending next = queue.poll();
        if (next == null) return;
        current = next;
        Presenter pr = presenter;
        if (pr != null) pr.present(next);
        else next.resolve(Action.FORWARD, null);
    }

    /** Called by the UI once the user has acted on the current item. */
    public synchronized void completed(Pending p) {
        if (current == p) {
            current = null;
            Presenter pr = presenter;
            if (pr != null) pr.cleared(p);
            pump();
        }
    }

    public Pending current() { return current; }

    public int pendingCount() { return queue.size(); }

    /** Release everything, used when interception is switched off. */
    public synchronized void releaseAll() {
        Pending c = current;
        if (c != null) c.resolve(Action.FORWARD, null);
        current = null;
        Pending p;
        while ((p = queue.poll()) != null) p.resolve(Action.FORWARD, null);
    }

    public boolean awaitIdle(long millis) {
        long end = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < end) {
            if (current == null && queue.isEmpty()) return true;
            try { TimeUnit.MILLISECONDS.sleep(20); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
        }
        return false;
    }
}
