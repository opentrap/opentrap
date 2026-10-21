package opentrap.proxy;

import opentrap.core.WebSocketMessage;

/**
 * Lets the WebSocket tool pause a frame the proxy is relaying so the tester can
 * edit or drop it. The relay thread blocks on this call until the UI resolves.
 */
public interface WsInterceptor {

    final class Decision {
        public final boolean drop;
        public final byte[] payload; // null = forward unchanged
        private Decision(boolean drop, byte[] payload) { this.drop = drop; this.payload = payload; }
        public static Decision forward()            { return new Decision(false, null); }
        public static Decision edit(byte[] payload) { return new Decision(false, payload); }
        public static Decision drop()               { return new Decision(true, null); }
    }

    /** Called on the relay thread for each text/binary frame while interception is on. */
    Decision intercept(WebSocketMessage frame);
}
