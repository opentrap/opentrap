package opentrap.core;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The on-disk body store (issue #37): bytes written are read back exactly, and a
 * crash mid-write costs at most the final record, never the file.
 */
public final class ProjectStoreTest {

    private static Path temp() throws Exception {
        Path p = Files.createTempFile("bodystore-test-", ".store");
        p.toFile().deleteOnExit();
        Files.delete(p);   // let the store create it fresh
        return p;
    }

    private static byte[] pattern(int n) {
        byte[] b = new byte[n];
        for (int i = 0; i < n; i++) b[i] = (byte) ((i * 31 + 7) & 0xFF);
        return b;
    }

    public void run() {
        Harness.suite("disk body store");

        Harness.test("bytes read back equal bytes written, across sizes and content", () -> {
            try {
                Path p = temp();
                ProjectStore s = new ProjectStore(p, 8L * 1024 * 1024);
                byte[] empty = new byte[0];
                byte[] small = Bytes.bytes("GET / HTTP/1.1\r\nHost: example.com\r\n\r\n");
                byte[] framed = Bytes.bytes("HTTP/1.1 200 OK\r\n\r\nbody\r\n\r\nwith blank lines");
                byte[] binary = pattern(1_000_000);   // every byte value, past the cache
                long oEmpty = s.append(ProjectStore.BODY, empty), oSmall = s.append(ProjectStore.BODY, small), oFramed = s.append(ProjectStore.BODY, framed), oBin = s.append(ProjectStore.BODY, binary);
                Expect.isTrue(Arrays.equals(s.readBody(oEmpty, 0), empty), "empty body round-trips");
                Expect.isTrue(Arrays.equals(s.readBody(oSmall, small.length), small), "request round-trips");
                Expect.isTrue(Arrays.equals(s.readBody(oFramed, framed.length), framed), "blank lines preserved");
                Expect.isTrue(Arrays.equals(s.readBody(oBin, binary.length), binary), "1 MB binary round-trips");
                s.close();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("reopening a clean store reads every record and appends after them", () -> {
            try {
                Path p = temp();
                ProjectStore s = new ProjectStore(p, 1024 * 1024);
                byte[] a = Bytes.bytes("alpha"), b = pattern(50_000);
                long oa = s.append(ProjectStore.BODY, a), ob = s.append(ProjectStore.BODY, b);
                s.close();
                ProjectStore r = new ProjectStore(p, 1024 * 1024);
                r.scan(null);   // the open path recovers the append point
                Expect.isTrue(Arrays.equals(r.readBody(oa, a.length), a), "first record survives a reopen");
                Expect.isTrue(Arrays.equals(r.readBody(ob, b.length), b), "second record survives a reopen");
                byte[] c = Bytes.bytes("gamma");
                long oc = r.append(ProjectStore.BODY, c);
                Expect.isTrue(oc >= ob, "a new record appends after the recovered ones");
                Expect.isTrue(Arrays.equals(r.readBody(oc, c.length), c), "and reads back");
                r.close();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a torn tail from a crash is dropped, intact records kept", () -> {
            try {
                Path p = temp();
                ProjectStore s = new ProjectStore(p, 1024 * 1024);
                byte[] a = pattern(2000), b = pattern(3000), c = pattern(4000);
                long oa = s.append(ProjectStore.BODY, a), ob = s.append(ProjectStore.BODY, b), oc = s.append(ProjectStore.BODY, c);
                s.close();
                // Simulate a crash part-way through writing the third record: chop
                // the last few bytes of its payload so its frame no longer fits.
                long size = Files.size(p);
                try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
                    raf.setLength(size - 5);
                }
                ProjectStore r = new ProjectStore(p, 1024 * 1024);
                r.scan(null);   // the open path detects the torn tail and truncates it
                Expect.isTrue(Arrays.equals(r.readBody(oa, a.length), a), "record before the tear is intact");
                Expect.isTrue(Arrays.equals(r.readBody(ob, b.length), b), "second record before the tear is intact");
                // The torn record's slot is now reused by the next append.
                byte[] d = pattern(1500);
                long od = r.append(ProjectStore.BODY, d);
                Expect.equal(od, oc, "the torn record's offset is reclaimed");
                Expect.isTrue(Arrays.equals(r.readBody(od, d.length), d), "the reclaimed slot reads back the new record");
                r.close();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a flipped byte is caught by the checksum, not returned silently", () -> {
            try {
                Path p = temp();
                ProjectStore s = new ProjectStore(p, 1024 * 1024);
                byte[] a = pattern(1000);
                long oa = s.append(ProjectStore.BODY, a);
                s.close();
                // Corrupt a byte well inside the payload (past the 8 byte frame).
                try (RandomAccessFile raf = new RandomAccessFile(p.toFile(), "rw")) {
                    long at = oa + ProjectStore.HEADER;   // somewhere in the payload
                    raf.seek(at);
                    int v = raf.read();
                    raf.seek(at);
                    raf.write(v ^ 0xFF);
                }
                ProjectStore r = new ProjectStore(p, 1024 * 1024);
                boolean threw = false;
                try { r.readBody(oa, a.length); } catch (Exception ex) { threw = true; }
                Expect.isTrue(threw, "a corrupt record is reported, not served as if intact");
                r.close();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
