package opentrap.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A single RFC 6455 WebSocket frame. Reading unmasks the payload; encoding masks
 * it again when sending client-to-server, so a frame can be parsed, inspected or
 * edited and put back on the wire correctly in either direction.
 */
public final class WebSocketFrame {

    public boolean fin;
    public int opcode;
    public byte[] payload = new byte[0];

    /** Read one frame, returning null at end of stream. Payload is returned unmasked. */
    public static WebSocketFrame read(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = readByte(in);
        WebSocketFrame f = new WebSocketFrame();
        f.fin = (b0 & 0x80) != 0;
        f.opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = (readByte(in) << 8) | readByte(in);
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
        }
        if (len < 0 || len > Integer.MAX_VALUE - 8) throw new IOException("frame too large");
        byte[] mask = new byte[4];
        if (masked) readFully(in, mask);
        byte[] p = new byte[(int) len];
        readFully(in, p);
        if (masked) for (int i = 0; i < p.length; i++) p[i] ^= mask[i & 3];
        f.payload = p;
        return f;
    }

    /** Encode a frame, masking the payload when {@code mask} is true (client-to-server). */
    public static byte[] encode(boolean fin, int opcode, byte[] payload, boolean mask) {
        if (payload == null) payload = new byte[0];
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write((fin ? 0x80 : 0) | (opcode & 0x0F));
        int lenCode = payload.length < 126 ? payload.length : (payload.length <= 0xFFFF ? 126 : 127);
        o.write((mask ? 0x80 : 0) | lenCode);
        if (lenCode == 126) {
            o.write((payload.length >> 8) & 0xFF);
            o.write(payload.length & 0xFF);
        } else if (lenCode == 127) {
            for (int i = 7; i >= 0; i--) o.write((int) (((long) payload.length >> (8 * i)) & 0xFF));
        }
        if (mask) {
            byte[] key = new byte[4];
            new java.security.SecureRandom().nextBytes(key);
            o.write(key, 0, 4);
            for (int i = 0; i < payload.length; i++) o.write(payload[i] ^ key[i & 3]);
        } else {
            o.writeBytes(payload);
        }
        return o.toByteArray();
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("unexpected end of WebSocket stream");
        return b & 0xFF;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int k = in.read(buf, off, buf.length - off);
            if (k < 0) throw new IOException("unexpected end of WebSocket stream");
            off += k;
        }
    }
}
