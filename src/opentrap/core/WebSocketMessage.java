package opentrap.core;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/** One WebSocket frame observed by the proxy, in either direction. */
public final class WebSocketMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum Direction { C2S, S2C }
    public enum Op { CONTINUATION, TEXT, BINARY, CLOSE, PING, PONG, OTHER }

    public final int connectionId;
    public final String host;
    public final Direction direction;
    public final Op op;
    public final byte[] payload;
    public final long time;
    public final boolean edited;

    public WebSocketMessage(int connectionId, String host, Direction direction, Op op, byte[] payload, boolean edited) {
        this.connectionId = connectionId;
        this.host = host == null ? "" : host;
        this.direction = direction;
        this.op = op;
        this.payload = payload == null ? new byte[0] : payload;
        this.edited = edited;
        this.time = System.currentTimeMillis();
    }

    public int length() { return payload.length; }

    /** Text frames decode as UTF-8; anything else is shown as hex. */
    public String text() {
        if (op == Op.TEXT) return new String(payload, StandardCharsets.UTF_8);
        return Bytes.toHex(payload);
    }

    public String preview() {
        String t = text().replaceAll("\\s+", " ");
        return t.length() > 120 ? t.substring(0, 120) + "..." : t;
    }

    public static Op opFrom(int opcode) {
        return switch (opcode) {
            case 0 -> Op.CONTINUATION;
            case 1 -> Op.TEXT;
            case 2 -> Op.BINARY;
            case 8 -> Op.CLOSE;
            case 9 -> Op.PING;
            case 10 -> Op.PONG;
            default -> Op.OTHER;
        };
    }
}
