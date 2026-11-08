package opentrap.core;

import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.CRC32;

/**
 * The single-file, log-structured project store (issue #37). One file holds the
 * whole session and is written in place as traffic is recorded, so opening is a
 * seek rather than an unpack and saving is a flush rather than a repack.
 *
 * <p>Layout: an 8 byte header (magic + format version), then a sequence of
 * length-prefixed, CRC-checked records. Each record is {@code [int payloadLen]
 * [int crc32][byte type][data...]}; the offset returned by {@link #append}
 * points at the length field, and {@code payloadLen} counts the type byte and
 * the data. Record types:
 *
 * <ul>
 *   <li>{@link #BODY}: raw request or response bytes, referenced by offset from
 *       an index entry. Read back on demand through {@link #readBody}.
 *   <li>{@link #ENTRY}: one history entry's index (its metadata and the offsets
 *       of its bodies), appended as the entry is recorded so the index is
 *       durable per exchange, not only at a periodic save.
 *   <li>{@link #SNAPSHOT}: the small mutable session state (scope, settings,
 *       findings, tool states, tasks), appended when it changes or on save.
 *   <li>{@link #TOMBSTONE}: marks an earlier entry removed.
 * </ul>
 *
 * <p>The latest ENTRY for a given number and the latest SNAPSHOT win; superseded
 * records are dead space a compaction reclaims. {@link #open} rebuilds by
 * scanning the record headers, reading only the small index and snapshot
 * payloads and seeking over the bodies, and a torn tail from a crash mid-write
 * is detected by the framing and truncated, costing at most the last record.
 *
 * <p>Reads and the single writer use positional channel I/O, serialised by the
 * platform per record, so a detail-view read never blocks behind the writer.
 */
public final class ProjectStore implements Closeable {

    public static final byte BODY = 1;
    public static final byte ENTRY = 2;
    public static final byte SNAPSHOT = 3;
    public static final byte TOMBSTONE = 4;

    private static final int MAGIC = 0x4F545231;   // "OTR1"
    private static final int VERSION = 1;
    static final int HEADER = 8;                    // magic(4) + version(4)
    static final int FRAME = 8;                     // payloadLen(4) + crc32(4)
    private static final int MAX_RECORD = 512 * 1024 * 1024;

    private final FileChannel ch;
    private final Path path;
    private final Object lock = new Object();
    private long end;
    private volatile boolean dirty;

    private final LinkedHashMap<Long, byte[]> cache = new LinkedHashMap<>(256, 0.75f, true);
    private long cacheBytes;
    private final long cacheCap;

    /** One record seen during {@link #scan}. {@code data} is null for a body (skipped for speed). */
    public interface Visitor { void record(byte type, long offset, byte[] data); }

    public ProjectStore(Path path, long cacheCapBytes) throws IOException {
        this.cacheCap = cacheCapBytes;
        this.path = path;
        this.ch = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        if (ch.size() < HEADER) {
            ByteBuffer h = ByteBuffer.allocate(HEADER);
            h.putInt(MAGIC).putInt(VERSION).flip();
            writeFully(h, 0);
            end = HEADER;
            ch.force(true);
        } else {
            ByteBuffer h = ByteBuffer.allocate(HEADER);
            readFully(h, 0);
            h.flip();
            if (h.getInt() != MAGIC) throw new IOException("not an OpenTRAP project file");
            h.getInt();   // version, read forward-compatibly
            end = ch.size();   // real end set by scan(); until then, append after everything
        }
    }

    /** True when this file already holds records beyond the header. */
    public boolean hasRecords() { return end > HEADER; }

    /** The file this store is open on. */
    public Path path() { return path; }

    /** Whether {@code file} begins with the project-store magic. */
    public static boolean looksLikeStore(java.io.File file) {
        try (java.io.DataInputStream in = new java.io.DataInputStream(
                new java.io.BufferedInputStream(new java.io.FileInputStream(file)))) {
            return in.readInt() == MAGIC;
        } catch (IOException e) { return false; }
    }

    /** Append a record of {@code type} carrying {@code data}; return its offset. */
    public long append(byte type, byte[] data) throws IOException {
        if (data == null) data = new byte[0];
        int plen = 1 + data.length;
        long off;
        synchronized (lock) {
            off = end;
            end += FRAME + plen;
        }
        ByteBuffer rec = ByteBuffer.allocate(FRAME + plen);
        rec.position(FRAME);
        rec.put(type).put(data);
        CRC32 c = new CRC32();
        c.update(rec.array(), FRAME, plen);
        rec.putInt(0, plen).putInt(4, (int) c.getValue());
        rec.position(0);
        writeFully(rec, off);
        dirty = true;
        if (type == BODY) synchronized (lock) { cachePut(off, data); }
        return off;
    }

    /** Read the body record at {@code off}; {@code len} is a hint, the frame is authoritative. */
    public byte[] readBody(long off, int len) throws IOException {
        if (off < 0) return new byte[0];
        synchronized (lock) {
            byte[] hit = cache.get(off);
            if (hit != null) return hit;
        }
        byte[] data = readRecord(off, BODY);
        synchronized (lock) { cachePut(off, data); }
        return data;
    }

    /** Read one record's data at {@code off}, checking its type and checksum. */
    private byte[] readRecord(long off, byte expectType) throws IOException {
        ByteBuffer f = ByteBuffer.allocate(FRAME);
        readFully(f, off);
        f.flip();
        int plen = f.getInt();
        int crc = f.getInt();
        if (plen < 1 || plen > MAX_RECORD) throw new IOException("corrupt record length at " + off);
        ByteBuffer p = ByteBuffer.allocate(plen);
        readFully(p, off + FRAME);
        byte[] payload = p.array();
        CRC32 c = new CRC32();
        c.update(payload, 0, plen);
        if ((int) c.getValue() != crc) throw new IOException("corrupt record (bad checksum) at " + off);
        if (expectType != 0 && payload[0] != expectType) {
            throw new IOException("record at " + off + " is type " + payload[0] + ", expected " + expectType);
        }
        byte[] data = new byte[plen - 1];
        System.arraycopy(payload, 1, data, 0, plen - 1);
        return data;
    }

    /**
     * Walk every intact record from the start, in write order, calling {@code v}.
     * Body records are reported with null data (their payload is seeked over, not
     * read, so a large project opens quickly); other records are read and
     * checksum-verified. A torn or corrupt tail ends the walk and the file is
     * truncated to the last intact record. Sets the append point to that end.
     */
    public void scan(Visitor v) throws IOException {
        long pos = HEADER;
        long size = ch.size();
        while (pos + FRAME <= size) {
            ByteBuffer f = ByteBuffer.allocate(FRAME);
            readFully(f, pos);
            f.flip();
            int plen = f.getInt();
            int crc = f.getInt();
            if (plen < 1 || plen > MAX_RECORD || pos + FRAME + plen > size) break;   // torn tail
            ByteBuffer tb = ByteBuffer.allocate(1);
            readFully(tb, pos + FRAME);
            byte type = tb.get(0);
            if (type == BODY) {
                if (v != null) v.record(BODY, pos, null);   // skip the payload, just note it
            } else {
                ByteBuffer p = ByteBuffer.allocate(plen);
                readFully(p, pos + FRAME);
                byte[] payload = p.array();
                CRC32 c = new CRC32();
                c.update(payload, 0, plen);
                if ((int) c.getValue() != crc) break;       // corrupt tail
                if (v != null) {
                    byte[] data = new byte[plen - 1];
                    System.arraycopy(payload, 1, data, 0, plen - 1);
                    v.record(type, pos, data);
                }
            }
            pos += FRAME + plen;
        }
        synchronized (lock) { end = pos; }
        if (pos < ch.size()) ch.truncate(pos);
    }

    /** Flush buffered writes to disk. Called on an interval and before a save. */
    public void sync() throws IOException {
        if (dirty) { ch.force(false); dirty = false; }
    }

    private void cachePut(long off, byte[] b) {
        cache.put(off, b);
        cacheBytes += b.length;
        while (cacheBytes > cacheCap && cache.size() > 1) {
            Iterator<Map.Entry<Long, byte[]>> it = cache.entrySet().iterator();
            Map.Entry<Long, byte[]> eldest = it.next();
            it.remove();
            cacheBytes -= eldest.getValue().length;
        }
    }

    private void writeFully(ByteBuffer b, long pos) throws IOException {
        while (b.hasRemaining()) pos += ch.write(b, pos);
    }

    private void readFully(ByteBuffer b, long pos) throws IOException {
        while (b.hasRemaining()) {
            int n = ch.read(b, pos);
            if (n < 0) throw new IOException("short read at " + pos);
            pos += n;
        }
    }

    @Override public void close() throws IOException {
        synchronized (lock) {
            try { ch.force(true); } catch (IOException ignore) {}
            ch.close();
        }
    }
}
