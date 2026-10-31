package opentrap.proxy;

import opentrap.core.Debug;
import opentrap.core.SuiteContext;
import opentrap.core.WebSocketMessage;
import opentrap.core.WebSocketMessage.Direction;
import opentrap.core.WebSocketMessage.Op;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * One upgraded WebSocket connection. It pumps frames both ways between the
 * browser and the upstream server, recording each into the suite's WebSocket
 * store, optionally pausing text/binary frames for interception, and letting the
 * tester inject new frames (the WebSocket equivalent of Repeater).
 */
public final class WebSocketConnection {

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final List<WebSocketConnection> LIVE = new CopyOnWriteArrayList<>();
    private static final List<Runnable> LIVE_LISTENERS = new CopyOnWriteArrayList<>();

    /** Global toggle, driven by the WebSocket tool's intercept checkbox. */
    public static volatile boolean intercepting = false;
    private static volatile WsInterceptor interceptor;

    public static List<WebSocketConnection> live()            { return LIVE; }
    public static void setInterceptor(WsInterceptor i)        { interceptor = i; }
    public static void addLiveListener(Runnable r)            { LIVE_LISTENERS.add(r); }
    private static void liveChanged() { for (Runnable r : LIVE_LISTENERS) r.run(); }

    public final int id = IDS.incrementAndGet();
    public final opentrap.core.HttpService service;
    private final SuiteContext ctx;
    private final OutputStream clientOut; // to the browser (frames unmasked)
    private final OutputStream serverOut; // to the upstream server (frames masked)
    private final List<MatchReplaceRule> rules; // WebSocket match and replace, shared with the proxy
    private volatile boolean open = true;

    public WebSocketConnection(SuiteContext ctx, opentrap.core.HttpService service,
                               OutputStream clientOut, OutputStream serverOut,
                               List<MatchReplaceRule> rules) {
        this.ctx = ctx;
        this.service = service;
        this.clientOut = clientOut;
        this.serverOut = serverOut;
        this.rules = rules;
    }

    @Override public String toString() { return "#" + id + " " + (service == null ? "" : service.host()); }

    /** Relay until either side closes. Blocks the calling thread on the C2S pump. */
    public void run(InputStream clientIn, InputStream serverIn) {
        LIVE.add(this);
        liveChanged();
        Thread s2c = new Thread(() -> {
            try { pump(serverIn, Direction.S2C); }
            catch (RuntimeException e) {
                // Normally the IOException path in pump() ends a relay; a
                // runtime failure would otherwise vanish and look like a
                // silently dead direction.
                Debug.error("websocket s2c relay failed for " + service, e);
            }
        }, "ws-s2c-" + id);
        s2c.setDaemon(true);
        s2c.start();
        try {
            pump(clientIn, Direction.C2S); // this thread handles client-to-server
        } finally {
            // Every exit path lands here: a runtime failure in the pump must
            // not leave a stale "live" entry in the WebSockets panel.
            open = false;
            try { s2c.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            LIVE.remove(this);
            liveChanged();
        }
    }

    private void pump(InputStream in, Direction dir) {
        try {
            while (open) {
                WebSocketFrame f = WebSocketFrame.read(in);
                if (f == null) break;
                Op op = WebSocketMessage.opFrom(f.opcode);
                byte[] payload = f.payload;
                boolean edited = false;

                // Match and replace runs on whole text messages only (a single
                // unfragmented frame), before interception sees them.
                if (f.fin && op == Op.TEXT && rules != null && !rules.isEmpty()) {
                    String text = new String(payload, StandardCharsets.UTF_8);
                    String replaced = MatchReplaceRule.applyAllWs(rules, text, dir == Direction.C2S);
                    if (!replaced.equals(text)) {
                        payload = replaced.getBytes(StandardCharsets.UTF_8);
                        edited = true;
                    }
                }

                if (intercepting && interceptor != null && (op == Op.TEXT || op == Op.BINARY)) {
                    WebSocketMessage probe = new WebSocketMessage(id, host(), dir, op, payload, false);
                    WsInterceptor.Decision d = interceptor.intercept(probe);
                    if (d != null && d.drop) continue; // drop: do not forward or log
                    if (d != null && d.payload != null && !Arrays.equals(d.payload, payload)) {
                        payload = d.payload;
                        edited = true;
                    }
                }

                ctx.wsStore().add(new WebSocketMessage(id, host(), dir, op, payload, edited));
                OutputStream target = dir == Direction.C2S ? serverOut : clientOut;
                byte[] out = WebSocketFrame.encode(f.fin, f.opcode, payload, dir == Direction.C2S);
                synchronized (target) { target.write(out); target.flush(); }
                if (op == Op.CLOSE) break;
            }
        } catch (IOException ignored) {
        } finally {
            open = false;
        }
    }

    /** Inject a new text frame towards the server, as if the client sent it. */
    public void sendTextToServer(String text) throws IOException {
        byte[] p = text.getBytes(StandardCharsets.UTF_8);
        byte[] frame = WebSocketFrame.encode(true, 1, p, true);
        synchronized (serverOut) { serverOut.write(frame); serverOut.flush(); }
        ctx.wsStore().add(new WebSocketMessage(id, host(), Direction.C2S, Op.TEXT, p, true));
    }

    private String host() { return service == null ? "" : service.host(); }
}
