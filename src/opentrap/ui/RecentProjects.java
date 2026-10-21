package opentrap.ui;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The list of recently used project files, shown in the startup screen's "Open
 * existing project" table. Persisted as a small tab-separated file under
 * {@code ~/.opentrap}, most-recent first, so reopening a project is one click.
 */
public final class RecentProjects {

    /** One remembered project: the name the user gave it, its file, and when it was last opened. */
    public record Entry(String name, File file, long lastAccessed) {}

    private static final int MAX = 20;

    private RecentProjects() {}

    private static Path store() {
        return Path.of(System.getProperty("user.home"), ".opentrap", "recent-projects.tsv");
    }

    public static List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        Path p = store();
        if (!Files.exists(p)) return out;
        try {
            for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", 3);
                if (f.length < 3) continue;
                try {
                    out.add(new Entry(f[0], new File(f[1]), Long.parseLong(f[2])));
                } catch (NumberFormatException ignored) {}
            }
        } catch (Exception ignored) {}
        return out;
    }

    /** Record a project as the most recently used, de-duplicating by file path. */
    public static void record(String name, File file) {
        if (file == null) return;
        List<Entry> list = load();
        list.removeIf(e -> e.file().getAbsolutePath().equals(file.getAbsolutePath()));
        list.add(0, new Entry(name == null || name.isBlank() ? baseName(file) : name,
                file, System.currentTimeMillis()));
        while (list.size() > MAX) list.remove(list.size() - 1);
        try {
            Path p = store();
            Files.createDirectories(p.getParent());
            StringBuilder b = new StringBuilder();
            for (Entry e : list) {
                b.append(e.name()).append('\t')
                 .append(e.file().getAbsolutePath()).append('\t')
                 .append(e.lastAccessed()).append('\n');
            }
            Files.writeString(p, b.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    public static String baseName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }
}
