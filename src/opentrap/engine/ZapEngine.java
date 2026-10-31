package opentrap.engine;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import opentrap.core.Debug;

/**
 * Lifecycle for the bundled OWASP ZAP scan engine.
 *
 * <p>ZAP is run with no user interface, as a headless daemon child process on the
 * loopback. OpenTRAP never shows anything of ZAP; it finds the engine jar, starts
 * the daemon on a private port with a random API key, waits for it to answer, and
 * then drives it through {@link ZapApi}. The engine is kept warm for the session
 * and shut down on exit.
 *
 * <p>In a release build the trimmed engine is embedded in the application jar as
 * {@code /opentrap/engine/engine.zip}. On first use it is extracted once to
 * {@code ~/.opentrap/engine} and run from there, so a single jar is self-contained.
 * A development build with an {@code engine/} directory (or a system ZAP) is used
 * directly without extraction.
 */
public final class ZapEngine {

    private static final String BUNDLE = "/opentrap/engine/engine.zip";
    private static final String BUNDLE_VERSION = "/opentrap/engine/VERSION";

    /**
     * The lifeline watchdog. Run as {@code /bin/sh -c WATCHDOG name <zap cmd...>}
     * with its stdin wired to a pipe held by OpenTRAP. It starts ZAP in its own
     * session, then blocks reading stdin; when OpenTRAP dies (for any reason) the
     * pipe closes, the read returns EOF, and it kills ZAP and all its descendants
     * (the headless browsers) plus ZAP's process group as a backstop.
     */
    private static final String WATCHDOG = """
            port=; key=; prev=
            for a in "$@"; do
              [ "$prev" = "-port" ] && port=$a
              case $a in api.key=*) key=${a#api.key=};; esac
              prev=$a
            done
            setsid "$@" &
            zap=$!
            cat >/dev/null 2>&1
            # OpenTRAP is gone. Shut ZAP down the same clean way the app itself does
            # - stop the AJAX spider then shut the core down - which quits the
            # Selenium-driven browsers properly. (SIGTERM alone does not: ZAP exits
            # and its browsers orphan to init, out of reach of a process-tree walk.)
            if [ -n "$port" ] && command -v curl >/dev/null 2>&1; then
              curl -gs "http://127.0.0.1:$port/JSON/ajaxSpider/action/stop/?apikey=$key" >/dev/null 2>&1
              curl -gs "http://127.0.0.1:$port/JSON/core/action/shutdown/?apikey=$key" >/dev/null 2>&1
            fi
            # Backstop: record ZAP's descendant browsers by PID while it is still
            # alive, then hard-kill any that survive, plus ZAP's process group.
            descendants() { for c in $(pgrep -P "$1" 2>/dev/null); do echo "$c"; descendants "$c"; done; }
            all=$(descendants "$zap")
            i=0
            while kill -0 "$zap" 2>/dev/null && [ "$i" -lt 8 ]; do all="$all $(descendants "$zap")"; i=$((i+1)); sleep 1; done
            kill -KILL "$zap" 2>/dev/null
            for p in $all; do kill -KILL "$p" 2>/dev/null; done
            kill -KILL -"$zap" 2>/dev/null
            """;

    private String jar;             // path to the engine jar; null until a bundled engine is extracted
    private final boolean bundled;  // engine.zip is embedded in this application jar
    private String base;            // http://127.0.0.1:<port>
    private String apiKey;
    private Process proc;
    private java.io.OutputStream lifeline;   // write end of the watchdog's stdin pipe
    private ZapApi api;
    private Path home;              // the engine's scratch directory, ours to clean up
    private boolean hookInstalled;  // one shutdown hook per engine, not per start

    /**
     * How far into the engine's message history the suite has read, shared by
     * every scan driven on this engine, so a second scan carries on from where
     * the first stopped instead of importing the whole history again.
     */
    private final java.util.concurrent.atomic.AtomicInteger imported = new java.util.concurrent.atomic.AtomicInteger();

    private ZapEngine(String jar, boolean bundled) { this.jar = jar; this.bundled = bundled; }

    /** The shared read position into this engine's message history. */
    public java.util.concurrent.atomic.AtomicInteger importedMessages() { return imported; }

    private static ZapEngine shared;
    private static boolean sharedResolved;

    /**
     * The one engine instance for the whole app. The dashboard (for scans) and
     * the passive-rules tab (for configuration) share it, so a rule change made
     * in the tab governs the scans, and one daemon serves both. Null if ZAP is
     * not installed.
     */
    public static synchronized ZapEngine shared() {
        if (!sharedResolved) { sharedResolved = true; shared = locate(); }
        return shared;
    }

    /** Find an engine on disk, or note the one embedded in this jar, or null. */
    public static ZapEngine locate() {
        for (String dir : candidateDirs()) {
            String j = findJar(dir);
            if (j != null) return new ZapEngine(j, false);
        }
        // Embedded in a release jar but not yet extracted: extract lazily on start.
        if (ZapEngine.class.getResource(BUNDLE) != null) return new ZapEngine(null, true);
        return null;
    }

    /**
     * The engine this build will run, for the about box: its version and where
     * it comes from. A release carries the engine inside the jar and unpacks it
     * on first use; a dev build uses an engine already on disk.
     */
    public static String describe() {
        String bundled = readResource(BUNDLE_VERSION);
        for (String dir : candidateDirs()) {
            String j = findJar(dir);
            if (j == null) continue;
            String v = j.replaceAll(".*/zap-([0-9.]+)\\.jar$", "$1");
            return "OWASP ZAP " + v;
        }
        if (bundled != null) return "OWASP ZAP " + bundled;
        return "OWASP ZAP (none found: run fetch-zap.sh or use a release build)";
    }

    private static List<String> candidateDirs() {
        List<String> l = new ArrayList<>();
        l.add(extractDir().toString());       // a previously-extracted bundled engine
        String cwd = System.getProperty("user.dir", ".");
        l.add(cwd + "/engine");               // a dev build's fetched engine (fetch-zap.sh)
        l.add("/usr/share/zaproxy");          // a system install, as a fallback
        return l;
    }

    private static Path extractDir() {
        return Path.of(System.getProperty("user.home", "."), ".opentrap", "engine");
    }

    private static String findJar(String dir) {
        try {
            Path d = Path.of(dir);
            if (!Files.isDirectory(d)) return null;
            try (var s = Files.list(d)) {
                return s.map(p -> p.getFileName().toString())
                        .filter(n -> n.startsWith("zap-") && n.endsWith(".jar"))
                        .findFirst()
                        .map(n -> dir + "/" + n)
                        .orElse(null);
            }
        } catch (IOException e) {
            return null;
        }
    }

    public boolean available() { return jar != null || bundled; }
    public ZapApi api() { return api; }
    public String base() { return base; }

    /** Start the daemon if not already running, and block until its API answers. */
    public synchronized void start() throws Exception {
        if (api != null && responds()) return;

        // First run of a release jar: unpack the embedded engine to ~/.opentrap/engine.
        if (jar == null && bundled) jar = ensureExtracted();
        if (jar == null) throw new IOException("No scan engine available");

        int port = freePort();
        imported.set(0);   // a fresh engine process starts with an empty history
        apiKey = Long.toHexString(new java.security.SecureRandom().nextLong());
        Path home = this.home = Files.createTempDirectory("opentrap-zap-");

        List<String> cmd = new ArrayList<>(List.of(
                javaBin(), "-jar", jar, "-daemon",
                "-host", "127.0.0.1", "-port", String.valueOf(port),
                "-config", "api.key=" + apiKey,
                "-config", "api.addrs.addr.name=127.0.0.1",
                "-config", "api.addrs.addr.regex=false",
                "-silent",
                "-dir", home.toString()));

        // Point ZAP's Selenium/AJAX-spider at the Chromium and chromedriver we
        // bundle (or the ones on the system), and drive it headless. When either
        // is missing the AJAX crawl simply does not run; the traditional spider
        // still does. The key-name variants cover different add-on versions.
        String chrome = opentrap.proxy.EmbeddedBrowser.findBinary();
        String driver = opentrap.proxy.EmbeddedBrowser.findDriver();
        if (driver != null) {
            cmd.add("-config"); cmd.add("selenium.chromeDriverPath=" + driver);
            cmd.add("-config"); cmd.add("selenium.chromeDriver=" + driver);
        }
        if (chrome != null) {
            cmd.add("-config"); cmd.add("selenium.chromeBinaryPath=" + chrome);
            cmd.add("-config"); cmd.add("selenium.chromeBinary=" + chrome);
        }
        cmd.add("-config"); cmd.add("ajaxSpider.browserId=chrome-headless");
        // Cap the AJAX spider's browser pool. ZAP defaults to one browser per CPU
        // core, which on a many-core machine means a dozen-plus headless Chromium
        // instances per crawl - a huge, needless memory load. Two is plenty.
        cmd.add("-config"); cmd.add("ajaxSpider.numberOfBrowsers=2");

        // Launch ZAP through a lifeline watchdog instead of directly. The watchdog
        // holds a pipe (its stdin) connected to this OpenTRAP process; the moment
        // OpenTRAP exits for ANY reason - clean close, crash, or kill -9 - the
        // kernel closes that pipe, the watchdog wakes on EOF, and it kills ZAP and
        // every browser ZAP spawned. Each OpenTRAP instance watches only its own
        // engine, so running several instances at once is safe.
        List<String> launch = new ArrayList<>(List.of(
                "/bin/sh", "-c", WATCHDOG, "opentrap-engine-watchdog"));
        launch.addAll(cmd);

        ProcessBuilder pb = new ProcessBuilder(launch);
        pb.redirectInput(ProcessBuilder.Redirect.PIPE);    // the lifeline
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        proc = pb.start();
        // Hold the write end open for the engine's lifetime; never write to it.
        // When this JVM ends, the OS closes it and the watchdog fires.
        lifeline = proc.getOutputStream();
        base = "http://127.0.0.1:" + port;
        api = new ZapApi(base, apiKey);

        if (!hookInstalled) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "zap-engine-shutdown"));
            hookInstalled = true;
        }

        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive()) throw new IOException("Scan engine exited during start-up");
            if (responds()) return;
            Thread.sleep(1500);
        }
        throw new IOException("Scan engine did not become ready in time");
    }

    private boolean responds() {
        try { return api.version() != null; } catch (Exception e) { return false; }
    }

    /** Attach to an already-running engine (used by diagnostics/tests). */
    public static ZapEngine attach(String base, String apiKey) {
        ZapEngine e = new ZapEngine("attached", false);
        e.base = base;
        e.apiKey = apiKey;
        e.api = new ZapApi(base, apiKey);
        return e;
    }

    /**
     * Unpack the embedded engine to {@code ~/.opentrap/engine} once, reusing an
     * existing extraction when its version marker matches the bundled version.
     * Returns the path to the engine jar.
     */
    private static synchronized String ensureExtracted() throws IOException {
        Path dir = extractDir();
        String want = readResource(BUNDLE_VERSION);
        String existing = findJar(dir.toString());
        if (existing != null && want != null && want.equals(readMarker(dir))) {
            return existing;   // already extracted and up to date
        }
        extractZip(dir);
        if (want != null) Files.writeString(dir.resolve(".version"), want);
        String j = findJar(dir.toString());
        if (j == null) throw new IOException("Engine extraction produced no jar");
        return j;
    }

    private static void extractZip(Path dest) throws IOException {
        Files.createDirectories(dest);
        try (InputStream in = ZapEngine.class.getResourceAsStream(BUNDLE);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                Path out = dest.resolve(e.getName()).normalize();
                if (!out.startsWith(dest)) continue;   // guard against zip-slip
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out)) { zis.transferTo(os); }
                }
            }
        }
    }

    private static String readMarker(Path dir) {
        try { return Files.readString(dir.resolve(".version")).trim(); }
        catch (IOException e) { return null; }
    }

    private static String readResource(String name) {
        try (InputStream in = ZapEngine.class.getResourceAsStream(name)) {
            return (in == null) ? null : new String(in.readAllBytes()).trim();
        } catch (IOException e) { return null; }
    }

    public synchronized void shutdown() {
        // Ask ZAP to stop the AJAX spider first, then shut down cleanly, so it
        // releases the headless browsers it drives. Every step is best-effort:
        // the watchdog and the forced kill below are the real guarantees, and a
        // failed polite step is traced, not propagated.
        try {
            if (api != null) {
                try { api.ajaxSpiderStop(); } catch (Exception e) { Debug.log("ajax spider stop: " + e); }
                api.shutdown();
            }
        } catch (Exception e) { Debug.log("engine shutdown call: " + e); }
        // Snap the lifeline: this alone makes the watchdog kill the engine tree,
        // and it is the safety net if the in-process kill below is ever skipped.
        if (lifeline != null) {
            try { lifeline.close(); } catch (Exception e) { Debug.log("lifeline close: " + e); }
            lifeline = null;
        }
        if (proc != null) {
            // Kill the WHOLE process tree. ZAP's AJAX spider spawns headless
            // browser processes (chromedriver/chromium, geckodriver/firefox) that
            // are descendants of the ZAP process, not of ours. destroy() signals
            // only ZAP itself, so those browsers would orphan and leak - which is
            // exactly how hundreds of them once piled up. Collect every descendant
            // before killing ZAP, then destroy them too.
            java.util.List<ProcessHandle> tree;
            try { tree = proc.descendants().collect(java.util.stream.Collectors.toList()); }
            catch (Exception e) { tree = java.util.List.of(); }
            proc.destroy();
            for (ProcessHandle h : tree) { try { h.destroy(); } catch (Exception e) { Debug.log("descendant destroy: " + e); } }
            try {
                if (!proc.waitFor(8, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
            } catch (InterruptedException ignore) { proc.destroyForcibly(); }
            for (ProcessHandle h : tree) {
                try { if (h.isAlive()) h.destroyForcibly(); } catch (Exception e) { Debug.log("descendant force-destroy: " + e); }
            }
        }
        // The engine's scratch home is a temp directory we created; remove it
        // once the process is gone so a machine does not accumulate one ZAP
        // home (session data, HSQLDB, logs) per engine start.
        Path h = home;
        home = null;
        if (h != null) deleteTree(h);
    }

    /** Best-effort recursive delete, for the engine's temp home. */
    private static void deleteTree(Path root) {
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); }
                catch (Exception e) { Debug.log("engine home cleanup, could not delete " + p + ": " + e); }
            });
        } catch (Exception e) {
            Debug.log("engine home cleanup: " + e);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); }
    }

    private static String javaBin() {
        String home = System.getProperty("java.home");
        return (home != null) ? home + "/bin/java" : "java";
    }
}
