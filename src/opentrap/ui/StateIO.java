package opentrap.ui;

import opentrap.core.*;

import javax.swing.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Save and restore the project as a single zip, as the original did. */
public final class StateIO {
    private StateIO() {}

    /** Project file extension. */
    public static final String EXT = ".otrap";

    /** A timestamped default project name, matching the startup dialog. */
    public static String defaultProjectFileName() {
        return "project-" + java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + EXT;
    }

    private static File withExtension(File f) {
        if (f.getName().toLowerCase().endsWith(EXT)) return f;
        return new File(f.getParentFile(), f.getName() + EXT);
    }

    public static void saveDialog(SuiteContext ctx, java.awt.Component parent) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("save state");
        // Reuse the project file this session is bound to; otherwise pre-fill a
        // timestamped name (like the startup "new project" option does), so a
        // temporary session never has to have a name typed from scratch.
        if (ctx.projectFile() != null) fc.setSelectedFile(ctx.projectFile());
        else fc.setSelectedFile(new File(defaultProjectFileName()));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        File f = withExtension(fc.getSelectedFile());
        if (f.exists()) {
            int r = JOptionPane.showConfirmDialog(parent,
                    "This file already exists. Are you sure?", "confirm", JOptionPane.YES_NO_OPTION);
            if (r != JOptionPane.YES_OPTION) return;
        }
        try {
            saveOrBind(ctx, f, SaveOptions.fromSettings(ctx));
            ctx.setProjectFile(f);   // bind the session, so later saves reuse it
            ctx.alert("suite", "state saved to " + f.getName());
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent, "An error occurred: " + e.getMessage(),
                    "error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public static void restoreDialog(SuiteContext ctx, java.awt.Component parent) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("restore state");
        if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        final File file = fc.getSelectedFile();
        restoreAsync(ctx, parent, file, n -> {
            ctx.alert("suite", "restored " + n + " items from " + file.getName());
            JOptionPane.showMessageDialog(parent,
                    "Restore completed. " + n + " history items loaded.",
                    "restore state", JOptionPane.INFORMATION_MESSAGE);
        });
    }

    // One writer at a time: the autosave timer, a finished scan and an explicit
    // save can all ask for a write, and none may interleave with another.
    private static final Object SAVE_LOCK = new Object();
    private static volatile boolean autosaving;
    private static volatile long savedStamp = -1;   // the session change stamp the file holds

    /**
     * What a write keeps. The session's own project is written with
     * {@link #fromSettings}; a saved copy can narrow it further.
     */
    public static final class SaveOptions {
        /** Only history entries and issues inside the target scope. */
        public boolean inScopeOnly;
        /** The tools whose history is kept, by tool tag; null keeps every tool. */
        public java.util.Set<String> tools;
        /** Keep the scan cards from the dashboard. */
        public boolean includeTasks = true;
        /** Keep the full body of audit probe responses. */
        public boolean keepAuditBodies;
        /** Largest response body kept, in bytes; 0 for no cap. */
        public int bodyCapBytes;

        /** The options the project's settings ask for. */
        public static SaveOptions fromSettings(SuiteContext ctx) {
            SaveOptions o = new SaveOptions();
            o.keepAuditBodies = opentrap.core.ProjectOptions.keepAuditBodies(ctx.settings());
            o.bodyCapBytes = opentrap.core.ProjectOptions.bodyCapBytes(ctx.settings());
            return o;
        }
    }

    /**
     * Save the session to the project file it is bound to, in the background,
     * if anything has changed since the file was last written. Does nothing for
     * a temporary session or while another autosave runs. A failure is reported
     * once in the event log; a success is silent apart from {@code onSaved},
     * which gets the file's size in bytes on the event thread.
     */
    public static void autosave(SuiteContext ctx, java.util.function.LongConsumer onSaved) {
        File f = ctx.projectFile();
        if (f == null || autosaving) return;
        final long stamp = ctx.changeStamp();
        if (stamp == savedStamp) return;
        autosaving = true;
        Thread t = new Thread(() -> {
            try {
                save(ctx, f);
                savedStamp = stamp;
                if (onSaved != null) {
                    final long size = f.length();
                    SwingUtilities.invokeLater(() -> onSaved.accept(size));
                }
            } catch (Exception e) {
                ctx.alert("suite", "Could not save the project to " + f.getName() + ": " + e.getMessage());
            } finally {
                autosaving = false;
            }
        }, "project-autosave");
        t.setDaemon(true);
        t.start();
    }

    /**
     * A restart (a scale or theme change replaces this JVM) writes the session
     * to its project file first, exactly as exiting does; without this,
     * everything since the last autosave was lost on the way past. A temporary
     * session with no project file has nothing to write.
     */
    public static void saveBeforeRestart(SuiteContext ctx) {
        File project = ctx.projectFile();
        if (project == null) return;
        try {
            save(ctx, project);
        } catch (Exception e) {
            Debug.error("could not save the project before restarting", e);
            ctx.alert("suite", "could not save the project before restarting: "
                    + e.getMessage() + "; unsaved work will be lost");
        }
    }

    public static void autosave(SuiteContext ctx) { autosave(ctx, null); }

    /** Write the session to {@code file} with the options its settings ask for. */
    public static void save(SuiteContext ctx, File file) throws IOException {
        save(ctx, file, SaveOptions.fromSettings(ctx));
    }

    /**
     * Write the session to {@code file}. When the session is already recording
     * into this file (a v7 project opened or created this run), saving is cheap:
     * the history is already on disk per exchange, so only a fresh session-state
     * snapshot is appended and flushed. Otherwise the project is written to a new
     * single-file store beside its final name and atomic-moved into place, so a
     * crash part-way through never leaves a truncated project behind.
     */
    public static void save(SuiteContext ctx, File file, SaveOptions options) throws IOException {
        final SaveOptions o = options == null ? SaveOptions.fromSettings(ctx) : options;
        synchronized (SAVE_LOCK) {
            ProjectStore live = ctx.store().diskStore();
            if (live != null && sameFile(live.path().toFile(), file)) {
                // Bound v7 project: the entries are already appended as they were
                // recorded; a save is just the small mutable session state plus fsync.
                live.append(ProjectStore.SNAPSHOT, sessionStateBytes(ctx, o));
                live.sync();
                return;
            }
            File tmp = new File(file.getParentFile(), file.getName() + ".saving");
            ProjectStore target = new ProjectStore(tmp.toPath(), 32L * 1024 * 1024);
            try {
                writeAllTo(ctx, target, o);
                target.sync();
            } finally {
                try { target.close(); } catch (IOException ignore) {}
            }
            try {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                java.nio.file.Files.move(tmp.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } finally {
                tmp.delete();
            }
        }
    }

    /**
     * Save to {@code file}: a cheap snapshot when the session is already bound to
     * it, otherwise write the project there and bind the session to it (a Save As
     * onto a new file), so from then on its saves are cheap too.
     */
    public static void saveOrBind(SuiteContext ctx, File file, SaveOptions options) throws IOException {
        ProjectStore live = ctx.store().diskStore();
        if (live != null && sameFile(live.path().toFile(), file)) save(ctx, file, options);
        else bindNew(ctx, file, options);
    }

    private static boolean sameFile(File a, File b) {
        if (a == null || b == null) return false;
        try { return a.getCanonicalFile().equals(b.getCanonicalFile()); }
        catch (IOException e) { return a.getAbsoluteFile().equals(b.getAbsoluteFile()); }
    }

    /**
     * Create a new single-file project at {@code file} and bind the session to
     * it, so from now on every exchange is appended to this file as it is
     * recorded and saving is a cheap snapshot flush. Any traffic recorded before
     * the project was chosen is migrated into the new file.
     */
    public static void bindNew(SuiteContext ctx, File file, SaveOptions options) throws IOException {
        final SaveOptions o = options == null ? SaveOptions.fromSettings(ctx) : options;
        synchronized (SAVE_LOCK) {
            file.delete();   // a new project starts empty even if the name was reused
            ProjectStore store = new ProjectStore(file.toPath(), 32L * 1024 * 1024);
            List<LogEntry> migrated = new ArrayList<>();
            for (LogEntry e : ctx.store().all()) {
                boolean drop = !o.keepAuditBodies && opentrap.core.ProjectOptions.AUDIT_TOOL.equals(e.tool());
                LogEntry rec = e.writtenTo(store, drop, o.bodyCapBytes);
                store.append(ProjectStore.ENTRY, rec.indexBytes());
                migrated.add(rec);
            }
            store.append(ProjectStore.SNAPSHOT, sessionStateBytes(ctx, o));
            store.sync();
            ctx.store().useStore(store);
            if (!migrated.isEmpty()) ctx.store().replaceAll(migrated);
        }
    }

    /** The small mutable session state a snapshot record holds (everything but the history). */
    static byte[] sessionStateBytes(SuiteContext ctx, SaveOptions o) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ObjectOutputStream oos = new ObjectOutputStream(baos);
        oos.writeObject(ctx.scope());
        oos.writeObject(ctx.settings().snapshot());
        oos.writeObject(ctx.sessions().snapshot());
        oos.writeObject(ctx.organizer().snapshot());
        List<opentrap.core.Issue> issues = new ArrayList<>();
        for (opentrap.core.Issue i : ctx.issues()) {
            if (o.inScopeOnly && !ctx.scope().isInScope(i.service(), i.path())) continue;
            issues.add(i);
        }
        oos.writeObject(issues);
        java.util.LinkedHashMap<String, java.io.Serializable> toolStates = new java.util.LinkedHashMap<>();
        for (opentrap.core.ToolModule t : ctx.tools()) {
            java.io.Serializable s = t.saveState();
            if (s != null) toolStates.put(t.name(), s);
        }
        oos.writeObject(toolStates);
        oos.writeObject(o.includeTasks ? new ArrayList<>(ctx.taskSnapshots()) : new ArrayList<>());
        oos.flush();
        return baos.toByteArray();
    }

    /**
     * Write every live entry (filtered by scope and tool, bodies slimmed) and a
     * session-state snapshot into {@code target}. The live entries are copied,
     * not moved, so the running session is never disturbed.
     */
    static void writeAllTo(SuiteContext ctx, ProjectStore target, SaveOptions o) throws IOException {
        for (LogEntry e : ctx.store().all()) {
            if (o.tools != null && !o.tools.contains(e.tool())) continue;
            if (o.inScopeOnly && !ctx.scope().isInScope(e)) continue;
            boolean drop = !o.keepAuditBodies && opentrap.core.ProjectOptions.AUDIT_TOOL.equals(e.tool());
            LogEntry rec = e.writtenTo(target, drop, o.bodyCapBytes);
            target.append(ProjectStore.ENTRY, rec.indexBytes());
        }
        target.append(ProjectStore.SNAPSHOT, sessionStateBytes(ctx, o));
    }

    /**
     * Open a project, routing to the single-file store format or, for a file
     * saved by an older build, the previous zip format.
     */
    public static int restore(SuiteContext ctx, File file) throws Exception {
        if (ProjectStore.looksLikeStore(file)) return openV7(ctx, file);
        int n = restoreV6(ctx, file);
        // Migrate the old zip format to the single-file store in place and bind
        // the session to it, so the project opens disk-backed from now on and its
        // saves are the cheap incremental kind.
        try { bindNew(ctx, file, SaveOptions.fromSettings(ctx)); }
        catch (IOException e) { Debug.error("could not migrate project to the single-file format", e); }
        return n;
    }

    /**
     * Open a v7 single-file project: scan the record log to rebuild the history
     * index (reading only the small index records, seeking over the bodies) and
     * the latest session-state snapshot, then keep recording into the same file.
     */
    static int openV7(SuiteContext ctx, File file) throws Exception {
        ProjectStore store = new ProjectStore(file.toPath(), 32L * 1024 * 1024);
        java.util.LinkedHashMap<Integer, LogEntry> byNumber = new java.util.LinkedHashMap<>();
        byte[][] snapshot = { null };
        store.scan((type, offset, data) -> {
            try {
                if (type == ProjectStore.ENTRY) {
                    LogEntry le = LogEntry.fromIndex(data, store);
                    byNumber.put(le.number(), le);
                } else if (type == ProjectStore.TOMBSTONE) {
                    int n = ((data[0] & 0xFF) << 24) | ((data[1] & 0xFF) << 16)
                            | ((data[2] & 0xFF) << 8) | (data[3] & 0xFF);
                    byNumber.remove(n);
                } else if (type == ProjectStore.SNAPSHOT) {
                    snapshot[0] = data;
                }
            } catch (Exception ex) {
                Debug.error("skipping a corrupt project record at " + offset, ex);
            }
        });
        List<LogEntry> entries = new ArrayList<>(byNumber.values());
        ctx.store().useStore(store);   // keep recording into this same file
        ctx.setRestoring(true);
        try {
            ctx.store().replaceAll(entries);
            if (snapshot[0] != null) applySessionState(ctx, snapshot[0]);
            int max = 0;
            for (LogEntry le : entries) max = Math.max(max, le.number());
            LogEntry.setCounter(max);
            ctx.scopeChanged();
            ctx.siteMapReloaded(entries);
        } finally {
            ctx.setRestoring(false);
        }
        // Reclaim dead space (superseded index records, tombstoned bodies, old
        // snapshots) by rewriting the file compactly when it has grown well past
        // the live data, so a long-churning project does not bloat without bound.
        if (shouldCompact(file, entries)) {
            try { bindNew(ctx, file, SaveOptions.fromSettings(ctx)); }
            catch (IOException e) { Debug.error("compaction on open failed", e); }
        }
        return entries.size();
    }

    /** True when {@code file} holds enough dead space to be worth rewriting on open. */
    private static boolean shouldCompact(File file, List<LogEntry> entries) {
        long live = 0;
        for (LogEntry e : entries) live += e.bytes();
        long size = file.length();
        // Only for files large enough to matter, and only when more than half is dead.
        return size > 16L * 1024 * 1024 && size > live * 2 + 4L * 1024 * 1024;
    }

    /** Apply a session-state snapshot (everything but the history) to the context. */
    @SuppressWarnings("unchecked")
    static void applySessionState(SuiteContext ctx, byte[] block) throws Exception {
        ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(block));
        Scope scope = (Scope) ois.readObject();
        java.util.Map<String, String> settings = (java.util.Map<String, String>) ois.readObject();
        opentrap.session.SessionManager.State session = (opentrap.session.SessionManager.State) ois.readObject();
        java.util.List<opentrap.organizer.OrganizerItem> organizer =
                (java.util.List<opentrap.organizer.OrganizerItem>) ois.readObject();
        java.util.List<opentrap.core.Issue> issues = (java.util.List<opentrap.core.Issue>) ois.readObject();
        java.util.Map<String, java.io.Serializable> toolStates =
                (java.util.Map<String, java.io.Serializable>) ois.readObject();
        java.util.List<opentrap.core.ScanTask.Snapshot> tasks =
                (java.util.List<opentrap.core.ScanTask.Snapshot>) ois.readObject();
        if (scope != null) {
            ctx.scope().include().clear();
            ctx.scope().include().addAll(scope.include());
            ctx.scope().exclude().clear();
            ctx.scope().exclude().addAll(scope.exclude());
        }
        ctx.settings().restore(settings);
        if (session != null) ctx.sessions().restore(session);
        if (organizer != null) ctx.organizer().restore(organizer);
        if (issues != null) ctx.restoreIssues(issues);
        if (tasks != null) ctx.restoreTasks(tasks);
        if (toolStates != null) {
            for (opentrap.core.ToolModule t : ctx.tools()) {
                java.io.Serializable s = toolStates.get(t.name());
                if (s != null) {
                    try { t.restoreState(s); }
                    catch (Exception ex) {
                        Debug.error("tool '" + t.name() + "' failed to restore its saved state", ex);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    static int restoreV6(SuiteContext ctx, File file) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            ZipEntry e = zis.getNextEntry();
            if (e == null) throw new IOException("Input file is not in correct format");
            ObjectInputStream ois = new ObjectInputStream(zis);
            int version = ois.readInt();
            if (version > 6) {
                ctx.alert("suite", "This file was saved by a later version - restore may fail");
            }
            List<LogEntry> entries = (List<LogEntry>) ois.readObject();
            Scope scope = (Scope) ois.readObject();
            java.util.Map<String, String> settings = (java.util.Map<String, String>) ois.readObject();
            opentrap.session.SessionManager.State session = null;
            if (version >= 2) {
                session = (opentrap.session.SessionManager.State) ois.readObject();
            }
            java.util.List<opentrap.organizer.OrganizerItem> organizer = null;
            if (version >= 3) {
                organizer = (java.util.List<opentrap.organizer.OrganizerItem>) ois.readObject();
            }
            java.util.List<opentrap.core.Issue> savedIssues = null;
            java.util.Map<String, java.io.Serializable> toolStates = null;
            if (version >= 4) {
                savedIssues = (java.util.List<opentrap.core.Issue>) ois.readObject();
                toolStates = (java.util.Map<String, java.io.Serializable>) ois.readObject();
            }
            java.util.List<opentrap.core.ScanTask.Snapshot> savedTasks = null;
            if (version >= 5) {
                savedTasks = (java.util.List<opentrap.core.ScanTask.Snapshot>) ois.readObject();
            }

            ctx.setRestoring(true);
            try {
            ctx.store().replaceAll(entries == null ? new ArrayList<>() : entries);
            if (scope != null) {
                ctx.scope().include().clear();
                ctx.scope().include().addAll(scope.include());
                ctx.scope().exclude().clear();
                ctx.scope().exclude().addAll(scope.exclude());
            }
            ctx.settings().restore(settings);
            if (session != null) ctx.sessions().restore(session);
            if (organizer != null) ctx.organizer().restore(organizer);
            if (savedIssues != null) ctx.restoreIssues(savedIssues);
            if (savedTasks != null) ctx.restoreTasks(savedTasks);
            if (toolStates != null) {
                for (opentrap.core.ToolModule t : ctx.tools()) {
                    java.io.Serializable s = toolStates.get(t.name());
                    if (s != null) {
                        try { t.restoreState(s); }
                        catch (Exception ex) {
                            // Name the tool: a panel that comes up empty after a
                            // project opens is otherwise mystifying.
                            Debug.error("tool '" + t.name()
                                    + "' failed to restore its saved state", ex);
                        }
                    }
                }
            }
            int max = 0;
            List<LogEntry> all = ctx.store().all();
            for (LogEntry le : all) max = Math.max(max, le.number());
            LogEntry.setCounter(max);
            ctx.scopeChanged();
            // One bulk rebuild of the site map, not one tree sync per entry.
            ctx.siteMapReloaded(all);
            return all.size();
            } finally {
                ctx.setRestoring(false);
            }
        }
    }

    /**
     * Restore off the event thread, with a small progress dialog, so a large
     * project never freezes the window. The UI fans the restored data out in
     * bulk on the event thread. {@code onDone} runs on the event thread with
     * the number of history items restored.
     */
    public static void restoreAsync(SuiteContext ctx, java.awt.Component parent, File file,
                                    java.util.function.IntConsumer onDone) {
        java.awt.Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        if (owner == null && parent instanceof java.awt.Window w) owner = w;
        final JDialog progress = new JDialog(owner, "Opening project", java.awt.Dialog.ModalityType.MODELESS);
        JProgressBar bar = new JProgressBar();
        bar.setIndeterminate(true);
        JLabel label = Ui.label("Reading " + file.getName() + " (" + (file.length() / (1024 * 1024)) + " MB)...");
        JPanel body = new JPanel(new java.awt.BorderLayout(0, 8));
        body.setBorder(Ui.pad(16, 20, 16, 20));
        body.add(label, java.awt.BorderLayout.NORTH);
        body.add(bar, java.awt.BorderLayout.CENTER);
        progress.setContentPane(body);
        progress.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        progress.pack();
        progress.setSize(Math.max(380, progress.getWidth()), progress.getHeight());
        progress.setLocationRelativeTo(owner);
        progress.setVisible(true);

        Thread t = new Thread(() -> {
            int n = -1;
            Exception failure = null;
            try { n = restore(ctx, file); } catch (Exception e) { failure = e; }
            final int count = n;
            final Exception err = failure;
            SwingUtilities.invokeLater(() -> {
                progress.dispose();
                if (err != null) {
                    JOptionPane.showMessageDialog(parent, "An error occurred: " + err.getMessage(),
                            "error", JOptionPane.ERROR_MESSAGE);
                } else if (onDone != null) {
                    onDone.accept(count);
                }
            });
        }, "project-restore");
        t.setDaemon(true);
        t.start();
    }
}
