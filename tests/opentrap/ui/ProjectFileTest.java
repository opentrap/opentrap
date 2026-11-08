package opentrap.ui;

import opentrap.core.*;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The single-file project format (issue #37): a bound session records every
 * exchange to the one file as it arrives, updates and removes are kept, the file
 * reopens with the whole session, and a crash mid-write costs at most the last
 * record rather than the project.
 */
public final class ProjectFileTest {

    private static final HttpService SVC = new HttpService("example.com", 443, true);

    private static LogEntry entry(String path, int bodyLen) {
        LogEntry e = new LogEntry("proxy", SVC,
                new HttpMessage(Bytes.bytes("GET " + path + " HTTP/1.1\r\nHost: example.com\r\n\r\n")));
        e.setResponse(new HttpMessage(Bytes.bytes(
                "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" + "x".repeat(bodyLen))));
        return e;
    }

    private static File tmp() throws Exception {
        File f = Files.createTempFile("opentrap-projfile", ".otrap").toFile();
        f.delete();   // bindNew/open create it fresh
        return f;
    }

    public void run() {
        Harness.suite("single-file project");

        Harness.test("a bound session records to the file and reopens with everything", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                File f = tmp();
                StateIO.bindNew(ctx, f, StateIO.SaveOptions.fromSettings(ctx));
                LogEntry a = entry("/a", 1000), b = entry("/b/c?q=1", 2000), c = entry("/d.js", 500);
                byte[] bodyA = a.response().raw().clone();
                byte[] bodyB = b.request().raw().clone();
                ctx.store().add(a); ctx.store().add(b); ctx.store().add(c);
                ctx.alert("note", "state change");   // some session state to snapshot
                StateIO.save(ctx, f);                 // cheap snapshot append
                ctx.store().closeStore();             // drains the index records and fsyncs

                SuiteContext back = new SuiteContext();
                int n = StateIO.restore(back, f);
                Expect.equal(n, 3, "all three entries reopen");
                List<LogEntry> all = back.store().all();
                Expect.equal(all.get(0).pathAndQuery(), "/a", "order and metadata preserved");
                Expect.equal(all.get(1).pathAndQuery(), "/b/c?q=1", "second entry preserved");
                Expect.equal(all.get(2).fileExtension(), "js", "extension from the reopened index");
                Expect.isTrue(Arrays.equals(all.get(0).response().raw(), bodyA), "response bytes reopen exactly");
                Expect.isTrue(Arrays.equals(all.get(1).request().raw(), bodyB), "request bytes reopen exactly");
                back.store().closeStore();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an update and a remove survive a reopen", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                File f = tmp();
                StateIO.bindNew(ctx, f, StateIO.SaveOptions.fromSettings(ctx));
                LogEntry a = entry("/keep", 400), b = entry("/edit", 400), c = entry("/drop", 400);
                ctx.store().add(a); ctx.store().add(b); ctx.store().add(c);
                b.setNotes("look here");
                ctx.store().updated(b);
                ctx.store().remove(c);
                ctx.store().closeStore();

                SuiteContext back = new SuiteContext();
                StateIO.restore(back, f);
                List<LogEntry> all = back.store().all();
                Expect.equal(all.size(), 2, "the removed entry is gone");
                boolean sawEdit = false;
                for (LogEntry e : all) {
                    Expect.isFalse(e.pathAndQuery().equals("/drop"), "the dropped entry does not come back");
                    if (e.pathAndQuery().equals("/edit")) { sawEdit = true; Expect.equal(e.notes(), "look here", "the edit is kept"); }
                }
                Expect.isTrue(sawEdit, "the edited entry is present");
                back.store().closeStore();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("an old-format project migrates to the single file on open", () -> {
            try {
                File f = tmp();
                List<LogEntry> history = new ArrayList<>();
                history.add(entry("/old/a", 500));
                history.add(entry("/old/b", 700));
                // Hand-write a project in the previous zip format (version 6).
                try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(f)))) {
                    zos.putNextEntry(new ZipEntry("opentrap"));
                    ObjectOutputStream oos = new ObjectOutputStream(zos);
                    oos.writeInt(6);
                    oos.writeObject(history);
                    oos.writeObject(new Scope());
                    oos.writeObject(new HashMap<String, String>());
                    oos.writeObject(null); // session
                    oos.writeObject(null); // organizer
                    oos.writeObject(null); // issues
                    oos.writeObject(null); // tool states
                    oos.writeObject(null); // tasks
                    oos.flush();
                    zos.closeEntry();
                }
                Expect.isFalse(ProjectStore.looksLikeStore(f), "starts in the old zip format");

                SuiteContext back = new SuiteContext();
                int n = StateIO.restore(back, f);
                Expect.equal(n, 2, "both old entries load");
                Expect.isTrue(ProjectStore.looksLikeStore(f), "the file is now the single-file format");
                Expect.notNull(back.store().diskStore(), "the migrated session is disk-backed");
                Expect.isTrue(back.store().all().get(0).response().length() > 0, "bodies survive the migration");
                Expect.equal(back.store().all().get(1).pathAndQuery(), "/old/b", "order preserved through migration");
                back.store().closeStore();
            } catch (Exception e) { throw new RuntimeException(e); }
        });

        Harness.test("a torn tail from a crash is dropped, the rest of the project opens", () -> {
            try {
                SuiteContext ctx = new SuiteContext();
                File f = tmp();
                StateIO.bindNew(ctx, f, StateIO.SaveOptions.fromSettings(ctx));
                for (int i = 0; i < 5; i++) ctx.store().add(entry("/r/" + i, 300));
                ctx.store().closeStore();
                long before = f.length();
                // A crash leaves a half-written record at the end.
                try (RandomAccessFile raf = new RandomAccessFile(f, "rw")) { raf.setLength(before - 7); }

                SuiteContext back = new SuiteContext();
                int n = StateIO.restore(back, f);
                Expect.isTrue(n >= 4 && n <= 5, "the intact records open; at most the last is lost (" + n + ")");
                for (LogEntry e : back.store().all()) {
                    Expect.notNull(e.response(), "every reopened entry still has its body");
                }
                back.store().closeStore();
            } catch (Exception e) { throw new RuntimeException(e); }
        });
    }
}
