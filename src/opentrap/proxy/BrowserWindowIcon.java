package opentrap.proxy;

import opentrap.core.Debug;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Puts OpenTRAP's own icon on the testing browser's windows.
 *
 * <p>A desktop panel shows the icon a window advertises in its
 * {@code _NET_WM_ICON} property, and the browser advertises its vendor's. So,
 * while the browser runs, every top-level window carrying the class OpenTRAP
 * launched it with has that property rewritten to the orange globe as it
 * appears. Only windows of that class are touched, so the user's own browser
 * is left alone. The property is written through the X library by a small
 * helper run with the system Python, because the icon array is far larger
 * than the command-line property tools accept. Needs an X display; where
 * there is none, the desktop entry mapping remains the fallback.
 */
final class BrowserWindowIcon {
    private BrowserWindowIcon() {}

    private static final int[] SIZES = {16, 24, 32, 48, 64, 128};

    /** The helper: find the class's top-level windows and brand the ones not yet done. */
    private static final String HELPER = """
            import ctypes, struct, sys
            icon_file, wm_class = sys.argv[1], sys.argv[2].encode()
            X = ctypes.CDLL("libX11.so.6")
            vp, ul, ci = ctypes.c_void_p, ctypes.c_ulong, ctypes.c_int
            X.XOpenDisplay.restype = vp; X.XOpenDisplay.argtypes = [ctypes.c_char_p]
            X.XDefaultRootWindow.restype = ul; X.XDefaultRootWindow.argtypes = [vp]
            X.XInternAtom.restype = ul; X.XInternAtom.argtypes = [vp, ctypes.c_char_p, ci]
            X.XQueryTree.argtypes = [vp, ul, ctypes.POINTER(ul), ctypes.POINTER(ul), ctypes.POINTER(ctypes.POINTER(ul)), ctypes.POINTER(ctypes.c_uint)]
            X.XGetWindowProperty.argtypes = [vp, ul, ul, ctypes.c_long, ctypes.c_long, ci, ul, ctypes.POINTER(ul), ctypes.POINTER(ci), ctypes.POINTER(ul), ctypes.POINTER(ul), ctypes.POINTER(ctypes.c_void_p)]
            X.XChangeProperty.argtypes = [vp, ul, ul, ul, ci, ci, vp, ci]
            X.XFree.argtypes = [vp]; X.XFlush.argtypes = [vp]; X.XCloseDisplay.argtypes = [vp]
            d = X.XOpenDisplay(None)
            if not d: sys.exit(0)
            A = lambda n: X.XInternAtom(d, n, 0)
            WM_CLASS, STRING, ICON, CARDINAL, MARK = A(b"WM_CLASS"), A(b"STRING"), A(b"_NET_WM_ICON"), A(b"CARDINAL"), A(b"_OPENTRAP_ICON")
            def prop(w, atom, typ):
                t, f, n, b, p = ul(), ci(), ul(), ul(), ctypes.c_void_p()
                if X.XGetWindowProperty(d, w, atom, 0, 4096, 0, typ, ctypes.byref(t), ctypes.byref(f), ctypes.byref(n), ctypes.byref(b), ctypes.byref(p)) != 0 or not p.value:
                    return None
                out = ctypes.string_at(p.value, n.value * (8 if f.value == 32 else f.value // 8))
                X.XFree(p); return out
            def children(w):
                r, par, kids, n = ul(), ul(), ctypes.POINTER(ul)(), ctypes.c_uint()
                if not X.XQueryTree(d, w, ctypes.byref(r), ctypes.byref(par), ctypes.byref(kids), ctypes.byref(n)): return []
                out = [kids[i] for i in range(n.value)]
                if n.value: X.XFree(kids)
                return out
            raw = open(icon_file, "rb").read()
            vals = struct.unpack("<%dI" % (len(raw) // 4), raw)
            arr = (ul * len(vals))(*vals)
            one = (ul * 1)(1)
            done = 0
            root = X.XDefaultRootWindow(d)
            level = children(root)
            for depth in range(3):
                nxt = []
                for w in level:
                    c = prop(w, WM_CLASS, STRING)
                    if c and wm_class in c.split(b"\\0"):
                        if prop(w, MARK, CARDINAL) is None:
                            X.XChangeProperty(d, w, ICON, CARDINAL, 32, 0, arr, len(vals))
                            X.XChangeProperty(d, w, MARK, CARDINAL, 32, 0, one, 1)
                            done += 1
                    else:
                        nxt.extend(children(w))
                level = nxt
            X.XFlush(d); X.XCloseDisplay(d)
            print(done)
            """;

    /** Watch the browser process and brand each of its windows as they open. */
    static void watch(Process browser) {
        if (browser == null || System.getenv("DISPLAY") == null) return;
        final Path dir = Path.of(System.getProperty("user.home"), ".opentrap", "browser");
        final Path helper = dir.resolve("brand-window.py");
        final Path icon = dir.resolve("window-icon.argb");
        try {
            Files.createDirectories(dir);
            Files.writeString(helper, HELPER, StandardCharsets.UTF_8);
            if (!Files.exists(icon)) Files.write(icon, iconData());
        } catch (Exception e) {
            return;
        }
        final Path log = dir.resolve("window-icon.log");
        Thread t = new Thread(() -> {
            int idle = 0;
            try { Files.writeString(log, "session=" + System.getenv("XDG_SESSION_TYPE")
                    + " desktop=" + System.getenv("XDG_CURRENT_DESKTOP") + " display=" + System.getenv("DISPLAY") + "\n"); }
            catch (Exception ignore) { }
            while (browser.isAlive()) {
                try {
                    Process p = new ProcessBuilder("python3", "-I", helper.toString(), icon.toString(), BrowserDesktopEntry.WM_CLASS)
                            .redirectErrorStream(true).start();
                    String out = new String(p.getInputStream().readAllBytes()).trim();
                    int rc = p.waitFor();
                    // A short record of what the helper did, for support.
                    if (rc != 0 || !out.equals("0")) {
                        try { Files.writeString(log, "rc=" + rc + " " + out + "\n", StandardCharsets.UTF_8,
                                java.nio.file.StandardOpenOption.APPEND); } catch (Exception ignore) { }
                    }
                    if (rc != 0 && ++idle > 3) return;   // no Python or no X library: give up quietly
                    Thread.sleep(out.equals("0") ? 1500 : 500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception e) {
                    // One failed probe (a busy machine, a missing tool) stops
                    // the icon mirror for this session; leave a trace of why.
                    Debug.error("browser window icon watcher failed; icon mirroring stopped", e);
                    return;
                }
            }
        }, "browser-icon");
        t.setDaemon(true);
        t.start();
    }

    /** The property value as little-endian 32-bit words: per size, width, height, then ARGB pixels. */
    private static byte[] iconData() {
        int words = 0;
        for (int s : SIZES) words += 2 + s * s;
        ByteBuffer b = ByteBuffer.allocate(words * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int s : SIZES) {
            BufferedImage img = BrowserDesktopEntry.icon(s);
            b.putInt(s).putInt(s);
            for (int y = 0; y < s; y++)
                for (int x = 0; x < s; x++) b.putInt(img.getRGB(x, y));
        }
        return b.array();
    }
}
