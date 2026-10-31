package opentrap.ui;

import opentrap.core.*;

import javax.swing.*;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

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
            save(ctx, f);
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
     * Write the session to {@code file}. The file is written beside its final
     * name and moved into place at the end, so a crash or a full disk part-way
     * through never leaves a truncated project behind.
     */
    public static void save(SuiteContext ctx, File file, SaveOptions options) throws IOException {
        synchronized (SAVE_LOCK) {
            File tmp = new File(file.getParentFile(), file.getName() + ".saving");
            writeTo(ctx, tmp, options == null ? SaveOptions.fromSettings(ctx) : options);
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

    /** The history as a write keeps it: filtered by scope and tool, bodies slimmed. */
    static List<LogEntry> historyToSave(SuiteContext ctx, SaveOptions o) {
        List<LogEntry> out = new ArrayList<>();
        for (LogEntry e : ctx.store().all()) {
            if (o.tools != null && !o.tools.contains(e.tool())) continue;
            if (o.inScopeOnly && !ctx.scope().isInScope(e)) continue;
            boolean drop = !o.keepAuditBodies && opentrap.core.ProjectOptions.AUDIT_TOOL.equals(e.tool());
            out.add(e.slim(drop, o.bodyCapBytes));
        }
        return out;
    }

    private static void writeTo(SuiteContext ctx, File file, SaveOptions o) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(file)))) {
            zos.putNextEntry(new ZipEntry("opentrap"));
            ObjectOutputStream oos = new ObjectOutputStream(zos);
            oos.writeInt(6);                            // format version
            oos.writeObject(historyToSave(ctx, o));     // history (v6: audit bodies left out)
            oos.writeObject(ctx.scope());               // scope
            oos.writeObject(ctx.settings().snapshot()); // settings
            oos.writeObject(ctx.sessions().snapshot()); // session jar, macros, rules (v2)
            oos.writeObject(ctx.organizer().snapshot()); // organizer items (v3)
            // v4: scanner issues, and each tool's own working state.
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
            // v5: the scans on the dashboard
            oos.writeObject(o.includeTasks ? new ArrayList<>(ctx.taskSnapshots()) : new ArrayList<>());
            oos.flush();
            zos.closeEntry();
        }
    }

    @SuppressWarnings("unchecked")
    public static int restore(SuiteContext ctx, File file) throws Exception {
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
