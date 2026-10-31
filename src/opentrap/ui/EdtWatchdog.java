package opentrap.ui;

import opentrap.core.Debug;

import javax.swing.SwingUtilities;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reports what the interface was doing whenever it stopped answering.
 *
 * <p>Swing does its work on one thread, so anything slow there freezes the
 * whole window: the application looks hung, then catches up, then hangs again.
 * Guessing which piece of work is responsible is unreliable, as the four wrong
 * guesses behind this class attest. The watchdog marks the event thread alive
 * every tick and, when that mark goes stale, takes its stack and keeps the
 * first frame that belongs to this application.
 *
 * <p>Runs only under {@code --debug}, and only samples: it never interrupts
 * the event thread or slows it down. A stall is reported once it ends, with
 * how long it lasted, so a report of a freeze comes with the reason attached.
 */
public final class EdtWatchdog {

    /** Below this a pause is ordinary work, not something a person notices. */
    private static final long STALL_MS = 500;
    private static final long SAMPLE_MS = 100;

    private static volatile long beat = System.currentTimeMillis();
    private static final Map<String, long[]> blame = new LinkedHashMap<>();   // where -> {count, worstMs}

    private EdtWatchdog() {}

    /** Start watching, if debug logging is on. Safe to call more than once. */
    public static void install() {
        if (!Debug.enabled()) return;
        if (started) return;
        started = true;

        Thread heart = new Thread(() -> {
            while (true) {
                SwingUtilities.invokeLater(() -> beat = System.currentTimeMillis());
                sleep(SAMPLE_MS / 2);
            }
        }, "edt-heartbeat");
        heart.setDaemon(true);
        heart.start();

        Thread watch = new Thread(() -> {
            Thread edt = null;
            long reported = 0;
            while (true) {
                if (edt == null || !edt.isAlive()) edt = findEventThread();
                long stalled = System.currentTimeMillis() - beat;
                if (stalled > STALL_MS && edt != null) {
                    String where = firstOwnFrame(edt);
                    synchronized (blame) {
                        long[] e = blame.computeIfAbsent(where, k -> new long[2]);
                        e[0]++;
                        e[1] = Math.max(e[1], stalled);
                    }
                    // One line per stall, once it has run on a while, rather
                    // than a line every sample.
                    if (stalled - reported > 1000) {
                        reported = stalled;
                        Debug.log("interface unresponsive for " + stalled + "ms in " + where);
                    }
                } else {
                    reported = 0;
                }
                sleep(SAMPLE_MS);
            }
        }, "edt-watchdog");
        watch.setDaemon(true);
        watch.start();
    }

    private static boolean started;

    /** What has stalled the interface so far, worst first. */
    public static String summary() {
        StringBuilder sb = new StringBuilder();
        synchronized (blame) {
            blame.entrySet().stream()
                 .sorted((a, b) -> Long.compare(b.getValue()[1], a.getValue()[1]))
                 .forEach(e -> sb.append(String.format("  %5d samples, worst %5dms  %s%n",
                         e.getValue()[0], e.getValue()[1], e.getKey())));
        }
        return sb.length() == 0 ? "  (the interface never stopped answering)" : sb.toString();
    }

    private static String firstOwnFrame(Thread edt) {
        for (StackTraceElement e : edt.getStackTrace()) {
            if (e.getClassName().startsWith("opentrap.")) {
                return e.getClassName() + "." + e.getMethodName() + " (" + e.getLineNumber() + ")";
            }
        }
        return "outside this application";
    }

    private static Thread findEventThread() {
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().startsWith("AWT-EventQueue")) return t;
        }
        return null;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
