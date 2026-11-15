package opentrap.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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
    public final long time;
    public final boolean edited;

    // payload is volatile because the offload writer thread nulls it once the
    // bytes are on disk while the event thread reads them to paint the table.
    // The volatile write on nulling publishes the snapshot taken just before
    // it, so a reader that sees a null field is guaranteed to see the offset
    // and preview that replace it, never a half-offloaded frame (the same
    // contract as LogEntry's request and response).
    private volatile byte[] payload;
    // Set when the payload lives in a disk ProjectStore rather than the heap;
    // transient, with the offset persisted by the project format phase.
    private transient ProjectStore payloadStore;
    private long off = -1;
    private int len;
    private volatile boolean offloaded;
    // Preview snapshot, taken from the payload the moment it is offloaded, so
    // the history table never loads a payload back from disk to paint a row.
    private String mPreview = "";

    public WebSocketMessage(int connectionId, String host, Direction direction, Op op, byte[] payload, boolean edited) {
        this.connectionId = connectionId;
        this.host = host == null ? "" : host;
        this.direction = direction;
        this.op = op;
        this.payload = payload == null ? new byte[0] : payload;
        this.edited = edited;
        this.time = System.currentTimeMillis();
    }

    /** A copy of {@code src} that keeps its identity but carries a different payload. */
    private WebSocketMessage(WebSocketMessage src, byte[] payload) {
        this.connectionId = src.connectionId;
        this.host = src.host;
        this.direction = src.direction;
        this.op = src.op;
        this.time = src.time;
        this.edited = src.edited;
        this.payload = payload;
    }

    /** The frame's payload, reloading it from the disk store if it was offloaded. */
    public byte[] payload() {
        byte[] p = payload;
        if (p != null) return p;
        if (payloadStore == null || off < 0) return new byte[0];
        try { return payloadStore.readBody(off, len); }
        catch (Exception e) { return new byte[0]; }
    }

    /** Payload length, from the live bytes or the offload snapshot. */
    public int length() {
        byte[] p = payload;
        return p != null ? p.length : len;
    }

    /** Text frames decode as UTF-8; anything else is shown as hex. */
    public String text() {
        if (op == Op.TEXT) return new String(payload(), StandardCharsets.UTF_8);
        return Bytes.toHex(payload());
    }

    /**
     * The one-line preview the history table shows, from the live payload
     * while it is resident and from the snapshot once it is on disk, so
     * painting a row never touches the disk.
     */
    public String preview() {
        byte[] p = payload;
        if (p == null) return mPreview;
        String t = text().replaceAll("\\s+", " ");
        return t.length() > 120 ? t.substring(0, 120) + "..." : t;
    }

    /** Whether this frame's payload lives on disk rather than in the heap. */
    public boolean offloaded() { return offloaded; }

    /**
     * Snapshot the preview the table needs, then write the payload bytes to
     * {@code store} and drop the in-heap copy, so the frame no longer retains
     * its data. {@link #payload()} reloads it on demand; {@link #preview()}
     * and {@link #length()} read the snapshot. The store reference, offset
     * and offloaded flag are set before the payload is nulled, so the volatile
     * null publishes everything a reader needs to reload it.
     */
    void offload(ProjectStore store) throws java.io.IOException {
        byte[] p = payload;
        if (p == null) return;
        String t = (op == Op.TEXT ? new String(p, StandardCharsets.UTF_8) : Bytes.toHex(p))
                .replaceAll("\\s+", " ");
        mPreview = t.length() > 120 ? t.substring(0, 120) + "..." : t;
        this.payloadStore = store;
        this.off = store.append(ProjectStore.BODY, p);
        this.len = p.length;
        this.offloaded = true;
        this.payload = null;
    }

    /**
     * This frame's index record: the frame serialised with its payload left
     * on disk, so only the metadata and the payload offset are written. The
     * caller offloads first, so the payload is null and nothing heavy is
     * inlined.
     */
    public byte[] indexBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) { oos.writeObject(this); }
        return baos.toByteArray();
    }

    /** Rebuild a frame from an index record and reconnect it to {@code store}. */
    public static WebSocketMessage fromIndex(byte[] b, ProjectStore store) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(b))) {
            WebSocketMessage m = (WebSocketMessage) ois.readObject();
            m.payloadStore = store;
            return m;
        }
    }

    /**
     * An independent copy of this frame with its payload resident (loaded
     * back from the disk store if it was offloaded) and written into
     * {@code target}, so a project copy carries the payload without the live
     * frame changing. Returns the copy, ready for {@link #indexBytes}.
     */
    public WebSocketMessage writtenTo(ProjectStore target) throws IOException {
        WebSocketMessage c = new WebSocketMessage(this, payload());
        c.offload(target);
        return c;
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
