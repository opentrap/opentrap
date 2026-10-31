package opentrap.ui;

import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * Collapses a burst of requests into one piece of work.
 *
 * <p>A scan reports its findings one at a time, and a listener that rebuilds a
 * whole table or tree for each one does that work a thousand times over a
 * thousand findings, on the event thread, which stops the window answering for
 * seconds at a time. Asking through a coalescer does it once per interval
 * however many requests arrive.
 *
 * <p>The first request is served immediately so a single finding still appears
 * at once; it is a burst that is collapsed, not the first event delayed.
 */
public final class Coalescer {

    private final Timer timer;
    private volatile boolean dirty;

    public Coalescer(int intervalMs, Runnable action) {
        timer = new Timer(intervalMs, e -> {
            if (dirty) { dirty = false; action.run(); }
            else ((Timer) e.getSource()).stop();
        });
        timer.setRepeats(true);
        timer.setInitialDelay(0);   // the first refresh is prompt, not delayed
    }

    /** Ask for the work to run. Safe from any thread. */
    public void request() {
        dirty = true;
        SwingUtilities.invokeLater(() -> { if (!timer.isRunning()) timer.start(); });
    }
}
