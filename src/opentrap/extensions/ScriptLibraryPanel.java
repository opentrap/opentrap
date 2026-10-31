package opentrap.extensions;

import opentrap.ui.Glyphs;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * A library of small scripts that tools across the suite run: filters,
 * custom actions and custom scan checks. One screen manages them all: a table
 * (name, function, location, modified) with New / Import and edit, duplicate,
 * export and delete, and a script editor on the right. Scripts are files under
 * {@code ~/.opentrap/scripts/<function>/<name>.java}, so they survive restarts
 * and can be shared.
 *
 * <p>Built for two homes: the Extensions tab's library (every function) and
 * the Custom scan checks tab (one function), by passing the function filter.
 */
public final class ScriptLibraryPanel extends JPanel {

    /** What a script does; each function has its own signature. */
    public enum Function {
        FILTER("Filter", "boolean matches(HttpRequestResponse requestResponse, Utilities utilities)"),
        CUSTOM_ACTION("Custom action", "void action(HttpRequestResponse requestResponse, Utilities utilities)"),
        SCAN_CHECK("Custom scan check", "List<Issue> check(HttpRequestResponse base, Utilities utilities)"),
        TABLE_COLUMN("Custom column", "Object column(HttpRequestResponse requestResponse, Utilities utilities)");
        public final String label, signature;
        Function(String label, String signature) { this.label = label; this.signature = signature; }
        public static Function of(String label) {
            for (Function f : values()) if (f.label.equalsIgnoreCase(label) || f.name().equalsIgnoreCase(label)) return f;
            return FILTER;
        }
        String dir() { return name().toLowerCase().replace('_', '-'); }
    }

    public record Script(String name, Function function, Path file, long modified) {}

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("HH:mm:ss dd MMM yyyy").withZone(ZoneId.systemDefault());

    private final Function only;              // null = every function
    private final List<Script> scripts = new ArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Name", "Function", "Location", "Date last modified"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTable table = new JTable(model);
    private final JPanel right = new JPanel(new BorderLayout());
    private final JTextArea editor = new JTextArea();
    private final JTextField nameField = Ui.field("", 24);
    private final JComboBox<String> functionBox;
    private Script editing;
    private final JButton edit, duplicate, export, delete;

    public static Path root() { return Path.of(System.getProperty("user.home", "."), ".opentrap", "scripts"); }

    /**
     * @param only       restrict to one function (the Custom scan checks tab), or null for all
     * @param title      the heading
     * @param blurb      the one-paragraph description under it
     * @param emptyTitle the empty-state headline on the right
     */
    public ScriptLibraryPanel(Function only, String title, String blurb, String emptyTitle, String emptyText) {
        super(new BorderLayout(0, 8));
        this.only = only;
        setOpaque(false);
        setBorder(Ui.pad(10, 12, 10, 12));

        String[] fnames = new String[Function.values().length];
        for (int i = 0; i < fnames.length; i++) fnames[i] = Function.values()[i].label;
        functionBox = Ui.combo(fnames);

        // Header: heading + blurb; GitHub + help on the right.
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(Ui.vbox(Ui.heading(title), Ui.note(blurb)), BorderLayout.CENTER);
        JPanel hr = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        hr.setOpaque(false);
        hr.add(Glyphs.button(Glyphs.github(18, Theme.ICON), "Ready-to-use scripts on GitHub", () -> opentrap.ui.Unbuilt.openProject(this)));
        hr.add(Glyphs.button(Glyphs.help(), "About scripts", () -> JOptionPane.showMessageDialog(this,
                "Scripts are small Java methods that tools run at a defined point.\n"
              + "Each function has a fixed signature; the editor shows it.\n"
              + "Files live under " + root() + ".", "Script library", JOptionPane.INFORMATION_MESSAGE)));
        head.add(hr, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        // Left column: toolbar, filter bar, table.
        JButton neu = Ui.primaryButton("New", "create a script");
        neu.setIcon(Glyphs.plusRing(14, Color.WHITE));
        JButton importB = Ui.button("Import", "import a script file");
        edit = Glyphs.button(Glyphs.pencil(), "Edit", this::editSelected);
        duplicate = Glyphs.button(Glyphs.copy(), "Duplicate", this::duplicateSelected);
        export = Glyphs.button(Glyphs.export(), "Export", this::exportSelected);
        delete = Glyphs.button(Glyphs.trash(), "Delete", this::deleteSelected);
        neu.addActionListener(e -> newScript());
        importB.addActionListener(e -> importScript());
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.setOpaque(false);
        tools.add(neu); tools.add(importB);
        tools.add(Box.createHorizontalStrut(6));
        tools.add(edit); tools.add(duplicate); tools.add(export); tools.add(delete);

        JButton filter = Ui.filterButton();
        filter.setText("Filter settings: Showing all items");
        JTextField search = Ui.field("", 14);
        search.putClientProperty("JTextField.placeholderText", "Search");
        JButton kebab = Glyphs.menuButton("More", () -> {
            JPopupMenu m = new JPopupMenu();
            JMenuItem open = new JMenuItem("Open scripts folder");
            open.setFont(Ui.PLAIN);
            open.addActionListener(e -> { try { Desktop.getDesktop().open(root().toFile()); } catch (Exception ignore) { } });
            m.add(open);
            return m;
        });
        JPanel fbar = Ui.filterBar(filter, search, kebab);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { reload(search.getText()); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { reload(search.getText()); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { reload(search.getText()); }
        });

        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        Ui.distributeWidths(table, 160, 110, 160, 140);
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) onSelect(); });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { if (e.getClickCount() == 2) editSelected(); }
        });

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setOpaque(false);
        left.add(Ui.vbox(tools, Box.createVerticalStrut(4), fbar), BorderLayout.NORTH);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(600, 10));
        left.add(sp, BorderLayout.CENTER);

        // Right: empty state or editor.
        right.setOpaque(false);
        emptyTitleText = emptyTitle;
        emptyBody = emptyText;
        showEmpty();

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerSize(5);
        split.setBorder(null);
        split.setDividerLocation(610);
        add(split, BorderLayout.CENTER);
        reload("");
    }

    private final String emptyTitleText, emptyBody;

    private void showEmpty() {
        right.removeAll();
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        JLabel icon = new JLabel(Glyphs.bolt(64, Ui.ACCENT));
        icon.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel h = new JLabel(emptyTitleText);
        h.setFont(new Font("Dialog", Font.BOLD, 18));
        h.setAlignmentX(Component.CENTER_ALIGNMENT);
        JTextArea t = Ui.note(emptyBody);
        Ui.sizeNote(t, 420);
        t.setAlignmentX(Component.CENTER_ALIGNMENT);
        JButton learn = Ui.button("Learn more");
        learn.addActionListener(e -> opentrap.ui.Unbuilt.openProject(this));
        JButton neu = Ui.primaryButton("New script", "create a script");
        neu.setIcon(Glyphs.plusRing(14, Color.WHITE));
        neu.addActionListener(e -> newScript());
        JPanel btns = Ui.row(learn, neu);
        btns.setAlignmentX(Component.CENTER_ALIGNMENT);
        JLabel gh = new JLabel("<html><u>View GitHub repository</u></html>");
        gh.setFont(Ui.PLAIN);
        gh.setForeground(new Color(0x1A, 0x5F, 0xB4));
        gh.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        gh.setAlignmentX(Component.CENTER_ALIGNMENT);
        gh.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { opentrap.ui.Unbuilt.openProject(ScriptLibraryPanel.this); }
        });
        col.add(Box.createVerticalGlue());
        col.add(icon); col.add(Box.createVerticalStrut(18));
        col.add(h); col.add(Box.createVerticalStrut(14));
        col.add(t); col.add(Box.createVerticalStrut(16));
        col.add(btns); col.add(Box.createVerticalStrut(12));
        col.add(gh);
        col.add(Box.createVerticalGlue());
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);
        wrap.add(col, new GridBagConstraints());
        right.add(wrap, BorderLayout.CENTER);
        right.revalidate();
        right.repaint();
        editing = null;
        enableRowActions(false);
    }

    private void showEditor(Script s, String text) {
        right.removeAll();
        editing = s;
        nameField.setText(s == null ? "" : s.name());
        functionBox.setSelectedItem(s == null ? (only == null ? Function.FILTER.label : only.label) : s.function().label);
        functionBox.setEnabled(only == null);
        editor.setText(text);
        editor.setFont(Ui.MONO);
        editor.setCaretPosition(0);

        JPanel form = Ui.form("Name", nameField, "Function", functionBox);
        JLabel sig = Ui.label(Function.of(String.valueOf(functionBox.getSelectedItem())).signature);
        sig.setFont(Ui.MONO);
        sig.setForeground(Theme.MUTED);
        functionBox.addActionListener(e -> sig.setText(Function.of(String.valueOf(functionBox.getSelectedItem())).signature));
        JButton save = Ui.primaryButton("Save", "save the script to the library");
        JButton cancel = Ui.button("Cancel");
        save.addActionListener(e -> save());
        cancel.addActionListener(e -> { onSelect(); });
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        bar.setOpaque(false);
        bar.add(cancel); bar.add(save);

        JPanel top = Ui.vbox(form, Box.createVerticalStrut(4), sig);
        top.setBorder(Ui.pad(0, 0, 6, 0));
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        p.setBorder(Ui.pad(0, 8, 0, 0));
        p.add(top, BorderLayout.NORTH);
        p.add(Ui.scroll(editor), BorderLayout.CENTER);
        p.add(bar, BorderLayout.SOUTH);
        right.add(p, BorderLayout.CENTER);
        right.revalidate();
        right.repaint();
    }

    private void enableRowActions(boolean on) {
        edit.setEnabled(on); duplicate.setEnabled(on); export.setEnabled(on); delete.setEnabled(on);
    }

    // ---- actions ----

    private Script selected() {
        int r = table.getSelectedRow();
        return r < 0 || r >= scripts.size() ? null : scripts.get(r);
    }

    private void onSelect() {
        Script s = selected();
        enableRowActions(s != null);
        if (s == null) { showEmpty(); return; }
        showEditor(s, read(s.file()));
    }

    private void newScript() {
        Function f = only == null ? Function.FILTER : only;
        showEditor(null, "// " + f.signature + "\n" + template(f));
        nameField.setText("Untitled");
        nameField.selectAll();
        nameField.requestFocusInWindow();
    }

    private static String template(Function f) {
        return switch (f) {
            case FILTER -> "return true;\n";
            case CUSTOM_ACTION -> "// act on the message\n";
            case SCAN_CHECK -> "return java.util.List.of();\n";
            case TABLE_COLUMN -> "return requestResponse.response() == null ? \"\" : requestResponse.response().statusCode();\n";
        };
    }

    private void editSelected() { if (selected() != null) onSelect(); }

    private void save() {
        String name = nameField.getText().strip();
        if (name.isEmpty()) { JOptionPane.showMessageDialog(this, "Give the script a name.", "Save", JOptionPane.WARNING_MESSAGE); return; }
        Function f = Function.of(String.valueOf(functionBox.getSelectedItem()));
        Path file = root().resolve(f.dir()).resolve(safe(name) + ".java");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, editor.getText());
            if (editing != null && !editing.file().equals(file)) Files.deleteIfExists(editing.file());
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not save: " + e.getMessage(), "Save", JOptionPane.ERROR_MESSAGE);
            return;
        }
        reload("");
        selectByFile(file);
    }

    private void duplicateSelected() {
        Script s = selected();
        if (s == null) return;
        Path copy = s.file().resolveSibling(safe(s.name()) + " copy.java");
        try { Files.copy(s.file(), copy); } catch (IOException e) { return; }
        reload("");
        selectByFile(copy);
    }

    private void exportSelected() {
        Script s = selected();
        if (s == null) return;
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File(s.file().getFileName().toString()));
        if (fc.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            try { Files.copy(s.file(), fc.getSelectedFile().toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e) { JOptionPane.showMessageDialog(this, "Could not export: " + e.getMessage()); }
        }
    }

    private void deleteSelected() {
        Script s = selected();
        if (s == null) return;
        if (JOptionPane.showConfirmDialog(this, "Delete \"" + s.name() + "\"?", "Delete script",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) return;
        try { Files.deleteIfExists(s.file()); } catch (IOException ignore) { }
        reload("");
    }

    private void importScript() {
        JFileChooser fc = new JFileChooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path src = fc.getSelectedFile().toPath();
        Function f = only == null ? Function.FILTER : only;
        if (only == null) {
            String pick = (String) JOptionPane.showInputDialog(this, "Function of the imported script:", "Import",
                    JOptionPane.QUESTION_MESSAGE, null, functionBox.getModel().getSize() > 0
                            ? java.util.stream.IntStream.range(0, functionBox.getItemCount()).mapToObj(functionBox::getItemAt).toArray(String[]::new)
                            : new String[]{Function.FILTER.label}, Function.FILTER.label);
            if (pick == null) return;
            f = Function.of(pick);
        }
        String base = src.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path dst = root().resolve(f.dir()).resolve(safe(base) + ".java");
        try {
            Files.createDirectories(dst.getParent());
            Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            JOptionPane.showMessageDialog(this, "Could not import: " + e.getMessage(), "Import", JOptionPane.ERROR_MESSAGE);
            return;
        }
        reload("");
        selectByFile(dst);
    }

    private void selectByFile(Path f) {
        for (int i = 0; i < scripts.size(); i++) if (scripts.get(i).file().equals(f)) { table.setRowSelectionInterval(i, i); return; }
    }

    private static String safe(String name) { return name.replaceAll("[^A-Za-z0-9 _.-]", "_"); }

    private static String read(Path p) {
        try { return Files.readString(p); } catch (IOException e) { return ""; }
    }

    /** Re-read the library from disk. */
    public void reload(String query) {
        String q = query == null ? "" : query.strip().toLowerCase();
        scripts.clear();
        model.setRowCount(0);
        for (Function f : Function.values()) {
            if (only != null && f != only) continue;
            Path dir = root().resolve(f.dir());
            if (!Files.isDirectory(dir)) continue;
            try (var ds = Files.newDirectoryStream(dir, "*.java")) {
                for (Path p : ds) {
                    String name = p.getFileName().toString().replaceAll("\\.java$", "");
                    if (!q.isEmpty() && !name.toLowerCase().contains(q)) continue;
                    scripts.add(new Script(name, f, p, Files.getLastModifiedTime(p).toMillis()));
                }
            } catch (IOException ignore) { }
        }
        scripts.sort((a, b) -> Long.compare(b.modified(), a.modified()));
        for (Script s : scripts) {
            model.addRow(new Object[]{s.name(), s.function().label, "Library", FMT.format(Instant.ofEpochMilli(s.modified()))});
        }
        if (scripts.isEmpty()) showEmpty();
    }
}
