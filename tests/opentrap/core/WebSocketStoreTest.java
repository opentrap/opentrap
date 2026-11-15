package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The WebSocket frame store: without a disk store a byte cap drops the oldest
 * frames as new ones arrive, like the audit traffic cap; with one, payloads
 * move to disk off the relay thread while every column stays served from the
 * heap; adds and reads are safe concurrently; and the frame records reopen
 * byte-exact from a project store.
 */
public final class WebSocketStoreTest {

    /** A text frame whose payload is exactly 1000 bytes and identifies itself. */
    private static WebSocketMessage frame(int i) {
        String text = String.format("%04d ", i) + "x".repeat(995);
        return new WebSocketMessage(1, "example.com", WebSocketMessage.Direction.S2C,
                WebSocketMessage.Op.TEXT, text.getBytes(StandardCharsets.UTF_8), false);
    }

    private static void awaitOffload(WebSocketStore store) {
        long deadline = System.currentTimeMillis() + 5000;
        while (store.residentBytes() > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static Path tempDir(String tag) throws Exception {
        Path d = Files.createTempDirectory(tag);
        d.toFile().deleteOnExit();
        return d;
    }

    public void run() {
        Harness.suite("websocket frame store");

        Harness.test("past the cap the oldest frames go first, with listeners told", () -> {
            WebSocketStore store = new WebSocketStore();
            List<WebSocketMessage> removed = new ArrayList<>();
            store.addListener(new WebSocketStore.Listener() {
                @Override public void messageAdded(WebSocketMessage m) {}
                @Override public void messageRemoved(WebSocketMessage m) { removed.add(m); }
            });
            store.setCapBytes(2500);   // two frames of 1000 bytes fit, three do not
            WebSocketMessage f1 = frame(1), f2 = frame(2), f3 = frame(3), f4 = frame(4);
            store.add(f1); store.add(f2);
            Expect.equal(removed.size(), 0, "within the cap nothing is dropped");
            store.add(f3);
            Expect.equal(removed.size(), 1, "one frame dropped");
            Expect.isTrue(removed.get(0) == f1, "the oldest frame");
            store.add(f4);
            Expect.equal(removed.size(), 2, "the next oldest goes next");
            Expect.isTrue(removed.get(1) == f2, "in arrival order");
            Expect.equal(store.size(), 2, "the window keeps the newest two");
            List<WebSocketMessage> all = store.all();
            Expect.isFalse(all.contains(f1), "the dropped frame is gone");
            Expect.isTrue(all.contains(f3) && all.contains(f4), "the newest frames stay");
            Expect.isTrue(store.residentBytes() <= 2500, "payload bytes within the cap");
        });

        Harness.test("zero keeps everything; lowering the cap trims at once", () -> {
            WebSocketStore store = new WebSocketStore();
            AtomicInteger removed = new AtomicInteger();
            store.addListener(new WebSocketStore.Listener() {
                @Override public void messageAdded(WebSocketMessage m) {}
                @Override public void messageRemoved(WebSocketMessage m) { removed.incrementAndGet(); }
            });
            store.setCapBytes(0);
            for (int i = 0; i < 20; i++) store.add(frame(i));
            Expect.equal(store.size(), 20, "no cap keeps everything");
            store.setCapBytes(2500);
            Expect.equal(store.size(), 2, "trimmed to fit");
            Expect.equal(removed.get(), 18, "listeners told about every dropped frame");
            List<WebSocketMessage> window = store.recent(2);
            Expect.equal(window.size(), 2, "the window is the newest frames");
            Expect.isTrue(window.get(0).text().startsWith("0018"), "oldest of the kept window first");
            Expect.isTrue(window.get(1).text().startsWith("0019"), "the newest last");
        });

        Harness.test("a disk-backed store spills payloads and keeps every frame", () -> {
            try {
                WebSocketStore store = new WebSocketStore();
                store.useDiskStore(tempDir("ws-cols-"));
                List<WebSocketMessage> removed = new ArrayList<>();
                store.addListener(new WebSocketStore.Listener() {
                    @Override public void messageAdded(WebSocketMessage m) {}
                    @Override public void messageRemoved(WebSocketMessage m) { removed.add(m); }
                });
                WebSocketMessage a = frame(1), b = frame(2);
                byte[] aPayload = a.payload().clone();
                store.add(a); store.add(b);
                awaitOffload(store);

                Expect.isTrue(a.offloaded() && b.offloaded(), "the payloads moved to disk");
                Expect.equal(store.size(), 2, "disk-backed frames are never dropped");
                Expect.equal(removed.size(), 0, "nothing dropped while payloads can spill");
                Expect.equal(store.residentBytes(), 0L, "no payload bytes left in the heap");

                // Even a cap of one byte drops nothing: offloaded frames cost
                // no heap, so eviction has nothing to do.
                store.setCapBytes(1);
                store.add(frame(3));
                awaitOffload(store);
                Expect.equal(store.size(), 3, "a cap never drops spilled frames");
                Expect.equal(removed.size(), 0, "still nothing dropped");

                // Every table column is served from the heap index.
                Expect.equal(a.length(), 1000, "length from the snapshot");
                Expect.isTrue(a.preview().startsWith("0001"), "preview from the snapshot");
                // The payload reloads byte-exact on demand (the detail view).
                Expect.isTrue(Arrays.equals(a.payload(), aPayload), "payload reloads byte-exact");
                Expect.isTrue(b.text().startsWith("0002"), "text reloads on demand");
                store.closeStore();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });

        Harness.test("concurrent adds and reads are safe, capped or not", () -> {
            try {
                final int threads = 4, perThread = 3000;
                WebSocketStore store = new WebSocketStore();
                store.setCapBytes(0);
                AtomicInteger failures = new AtomicInteger();
                List<Thread> adders = new ArrayList<>();
                for (int t = 0; t < threads; t++) {
                    Thread th = new Thread(() -> {
                        try {
                            for (int i = 0; i < perThread; i++) {
                                store.add(frame(i));
                                if ((i & 255) == 0 && store.size() >= 5) {
                                    // A reader racing the writers: the window, a
                                    // point lookup and a full copy.
                                    Expect.equal(store.recent(5).size(), 5, "the window reads whole");
                                    Expect.notNull(store.get(store.size() - 1), "a point lookup");
                                    store.all();
                                }
                            }
                        } catch (Throwable x) {
                            failures.incrementAndGet();
                        }
                    }, "ws-add-" + t);
                    th.start();
                    adders.add(th);
                }
                for (Thread th : adders) th.join();
                Expect.equal(failures.get(), 0, "no concurrent add/read failures");
                Expect.equal(store.size(), threads * perThread, "every frame landed");

                // Now with a cap: the same churn must stay within it, and the
                // accounting must match what is actually kept.
                store.setCapBytes(1_000_000);   // a thousand 1000-byte frames
                for (int t = 0; t < threads; t++) {
                    Thread th = new Thread(() -> {
                        for (int i = 0; i < perThread; i++) store.add(frame(i));
                    }, "ws-add-capped-" + t);
                    th.start();
                    adders.set(t, th);
                }
                for (Thread th : adders) th.join();
                Expect.isTrue(store.residentBytes() <= 1_000_000, "capped under concurrency");
                Expect.equal(store.size() * 1000L, store.residentBytes(), "the accounting matches the frames kept");
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });

        Harness.test("frame records reopen from a project store byte-exact", () -> {
            try {
                Path f = tempDir("ws-round-").resolve("frames.store");
                String first, second;
                WebSocketStore store = new WebSocketStore();
                store.useStore(new ProjectStore(f, 8L * 1024 * 1024));
                WebSocketMessage a = frame(1), b = frame(2), c = frame(3);
                byte[] aPayload = a.payload().clone(), cPayload = c.payload().clone();
                store.add(a); store.add(b); store.add(c);
                awaitOffload(store);
                first = a.text();
                second = b.text();
                store.closeStore();

                ProjectStore reopened = new ProjectStore(f, 8L * 1024 * 1024);
                List<WebSocketMessage> frames = new ArrayList<>();
                reopened.scan((type, offset, data) -> {
                    if (type != ProjectStore.WS) return;
                    try { frames.add(WebSocketMessage.fromIndex(data, reopened)); }
                    catch (Exception e) { throw new RuntimeException(e); }
                });
                Expect.equal(frames.size(), 3, "every frame index reopened");
                Expect.equal(frames.get(0).text(), first, "order and payload preserved");
                Expect.equal(frames.get(1).text(), second, "the middle frame preserved");
                Expect.isTrue(Arrays.equals(frames.get(0).payload(), aPayload), "payload reloads byte-exact");
                Expect.isTrue(Arrays.equals(frames.get(2).payload(), cPayload), "the last frame too");
                Expect.isTrue(frames.get(0).preview().startsWith("0001"), "the snapshot travels with it");
                reopened.close();
            } catch (Exception ex) { throw new RuntimeException(ex); }
        });
    }
}
