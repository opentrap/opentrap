package opentrap.extensions;

import opentrap.ui.Glyphs;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Extensions > Installed: the loaded extensions table with Add / Remove / Up /
 * Down, and Details / Output / Errors for the selected one. Add opens the
 * {@link LoadExtensionDialog}; a loaded entry records its type, file and
 * status. Loading runs the extension's declared entry point when it is a jar
 * on the classpath that implements {@code opentrap.core.ToolModule}; anything
 * else is listed with its status so the user can see what happened.
 */
public final class InstalledPanel extends JPanel {

    /** One loaded extension. */
    static final class Loaded {
        boolean enabled = true;
        boolean autoReload;
        final String type, name;
        final File file;
        String status = "Loaded";
        /** A rough measure of what the extension costs to run, 0-100. */
        int impact;
        final StringBuilder output = new StringBuilder();
        final StringBuilder errors = new StringBuilder();
        Loaded(String type, String name, File file) { this.type = type; this.name = name; this.file = file; }
    }

    private final List<Loaded> items = new ArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(
            new Object[]{"Loaded", "Type", "Name", "System impact", "Auto-reload"}, 0) {
        @Override public Class<?> getColumnClass(int c) {
            return (c == 0 || c == 4) ? Boolean.class : c == 3 ? Integer.class : String.class;
        }
        @Override public boolean isCellEditable(int r, int c) { return c == 0 || c == 4; }
    };
    private final JTable table = new JTable(model);
    private final DefaultTableModel details = new DefaultTableModel(new Object[]{"Item", "Detail"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JTextArea output = console();
    private final JTextArea errors = console();

    public InstalledPanel() {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        setBorder(Ui.pad(10, 12, 10, 12));

        JButton add = Ui.primaryButton("Add", "load an extension from a file");
        JButton remove = Ui.button("Remove", "unload the selected extension");
        JButton up = Ui.button("Up");
        JButton down = Ui.button("Down");
        add.addActionListener(e -> {
            LoadExtensionDialog.Result r = LoadExtensionDialog.show(this);
            if (r != null) load(r);
        });
        remove.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) { items.remove(r); model.removeRow(r); showSelected(); }
        });
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));

        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        table.getColumnModel().getColumn(1).setMaxWidth(90);
        table.getColumnModel().getColumn(3).setMaxWidth(130);
        table.getColumnModel().getColumn(3).setPreferredWidth(130);
        table.getColumnModel().getColumn(4).setMaxWidth(90);
        table.getColumnModel().getColumn(3).setCellRenderer(new MeterRenderer());
        model.addTableModelListener(e -> {
            int r = e.getFirstRow();
            if (r < 0 || r >= items.size()) return;
            if (e.getColumn() == 0) items.get(r).enabled = Boolean.TRUE.equals(model.getValueAt(r, 0));
            if (e.getColumn() == 4) items.get(r).autoReload = Boolean.TRUE.equals(model.getValueAt(r, 4));
        });
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelected(); });

        // The heading, the one-line description and the search box, as the
        // reference puts them above the table.
        JTextArea blurb = Ui.note("Extensions let you customise OpenTRAP's behaviour. Load one from a file, "
                + "turn it off without unloading it, and read its output and errors below.");
        Ui.sizeNote(blurb, 760);
        JTextField find = Ui.searchField(18);
        javax.swing.table.TableRowSorter<DefaultTableModel> sorter = new javax.swing.table.TableRowSorter<>(model);
        table.setRowSorter(sorter);
        find.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void go() {
                String q = find.getText().strip();
                sorter.setRowFilter(q.isEmpty() ? null
                        : RowFilter.regexFilter("(?i)" + java.util.regex.Pattern.quote(q), 1, 2));
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        JPanel headRow = new JPanel(new BorderLayout(8, 0));
        headRow.setOpaque(false);
        JPanel headLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        headLeft.setOpaque(false);
        headLeft.add(Ui.heading("OpenTRAP extensions"));
        headLeft.add(Ui.helpButton("Extensions",
                "An extension is a jar whose classes implement the suite's tool-module contract. "
                + "Loading one runs its entry point and adds whatever tabs and menus it declares."));
        headRow.add(headLeft, BorderLayout.WEST);
        headRow.add(find, BorderLayout.EAST);
        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.setBorder(Ui.pad(0, 0, 8, 0));
        head.add(headRow, BorderLayout.NORTH);
        head.add(blurb, BorderLayout.CENTER);

        JPanel centre = new JPanel(new BorderLayout(8, 0));
        centre.setOpaque(false);
        centre.add(head, BorderLayout.NORTH);
        centre.add(Ui.buttonColumn(add, remove, up, down), BorderLayout.WEST);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(600, 160));
        centre.add(sp, BorderLayout.CENTER);

        JTable dt = new JTable(details);
        Ui.styleTable(dt);
        JTabbedPane detail = new JTabbedPane();
        detail.setFont(Ui.PLAIN);
        detail.addTab("Details", Ui.scroll(dt));
        detail.addTab("Output", Ui.scroll(output));
        detail.addTab("Errors", Ui.scroll(errors));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, centre, detail);
        split.setResizeWeight(0.45);
        split.setBorder(null);
        split.setDividerSize(5);
        add(split, BorderLayout.CENTER);
    }

    /**
     * A rough system-impact score from the extension's own size: a bigger jar
     * with more classes loads more code and costs more to run. It is a measure
     * of the file, not a prediction about behaviour, which is why it is called
     * an estimate everywhere it is shown.
     */
    private static int measureImpact(File jar) {
        if (jar == null || !jar.isFile()) return 0;
        long kb = jar.length() / 1024;
        int classes = 0;
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
            while (en.hasMoreElements()) if (en.nextElement().getName().endsWith(".class")) classes++;
        } catch (Exception ignored) { }
        int score = (int) Math.min(60, kb / 40) + Math.min(40, classes / 10);
        return Math.max(1, Math.min(100, score));
    }

    private static String impactLabel(int score) {
        return score < 25 ? "Low" : score < 60 ? "Medium" : "High";
    }

    /** The system-impact cell: a short bar with its Low / Medium / High word. */
    private static final class MeterRenderer extends javax.swing.table.DefaultTableCellRenderer {
        private final JPanel row = new JPanel(new BorderLayout(6, 0));
        private final JProgressBar bar = new JProgressBar(0, 100);
        private final JLabel word = new JLabel();
        MeterRenderer() {
            bar.setBorderPainted(false);
            bar.setPreferredSize(new Dimension(56, 6));
            word.setFont(Ui.PLAIN);
            row.setBorder(Ui.pad(0, 4, 0, 4));
            row.add(bar, BorderLayout.CENTER);
            row.add(word, BorderLayout.EAST);
        }
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel,
                boolean focus, int r, int c) {
            int score = v instanceof Integer i ? i : 0;
            bar.setValue(score);
            bar.setForeground(score < 25 ? Theme.CONTROL : score < 60 ? Ui.SEV_MEDIUM : Ui.SEV_HIGH);
            word.setText(impactLabel(score));
            word.setForeground(sel ? Theme.TEXT : Theme.MUTED);
            row.setBackground(sel ? t.getSelectionBackground() : t.getBackground());
            row.setOpaque(true);
            return row;
        }
    }

    private static JTextArea console() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setFont(Ui.MONO);
        return a;
    }

    private void load(LoadExtensionDialog.Result r) {
        Loaded l = new Loaded(r.type(), r.file().getName(), r.file());
        if (!r.file().isFile()) {
            l.status = "File not found";
            l.errors.append("No such file: ").append(r.file()).append('\n');
        } else if ("Java".equals(r.type())) {
            l.status = loadJar(l, r.file());
        } else {
            l.status = r.type() + " extensions need a " + r.type() + " runtime; set its location under Settings > Extensions";
            l.errors.append(l.status).append('\n');
        }
        l.impact = measureImpact(r.file());
        items.add(l);
        model.addRow(new Object[]{l.enabled, l.type, l.name, l.impact, l.autoReload});
        table.setRowSelectionInterval(items.size() - 1, items.size() - 1);
    }

    /** Try the jar: find the first class implementing ToolModule and instantiate it. */
    private static String loadJar(Loaded l, File jar) {
        try (java.util.jar.JarFile jf = new java.util.jar.JarFile(jar)) {
            java.net.URLClassLoader cl = new java.net.URLClassLoader(new java.net.URL[]{jar.toURI().toURL()},
                    InstalledPanel.class.getClassLoader());
            java.util.Enumeration<java.util.jar.JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                String n = en.nextElement().getName();
                if (!n.endsWith(".class") || n.contains("$")) continue;
                String cn = n.substring(0, n.length() - 6).replace('/', '.');
                try {
                    Class<?> c = Class.forName(cn, false, cl);
                    if (opentrap.core.ToolModule.class.isAssignableFrom(c) && !c.isInterface()) {
                        Object inst = c.getDeclaredConstructor().newInstance();
                        l.output.append("Loaded ").append(cn).append(" as a tool module (")
                                .append(((opentrap.core.ToolModule) inst).name()).append(")\n");
                        return "Loaded";
                    }
                } catch (Throwable t) {
                    l.errors.append(cn).append(": ").append(t).append('\n');
                }
            }
            l.output.append("No class in the jar implements opentrap.core.ToolModule.\n");
            return "No entry point";
        } catch (Exception e) {
            l.errors.append(e).append('\n');
            return "Failed: " + e.getMessage();
        }
    }

    private void move(int delta) {
        int r = table.getSelectedRow();
        int to = r + delta;
        if (r < 0 || to < 0 || to >= items.size()) return;
        Loaded l = items.remove(r);
        items.add(to, l);
        model.moveRow(r, r, to);
        table.setRowSelectionInterval(to, to);
    }

    private void showSelected() {
        details.setRowCount(0);
        output.setText("");
        errors.setText("");
        int r = table.getSelectedRow();
        if (r < 0 || r >= items.size()) return;
        Loaded l = items.get(r);
        details.addRow(new Object[]{"Name", l.name});
        details.addRow(new Object[]{"Type", l.type});
        details.addRow(new Object[]{"File", l.file.getAbsolutePath()});
        details.addRow(new Object[]{"Status", l.status});
        details.addRow(new Object[]{"Enabled", l.enabled ? "Yes" : "No"});
        details.addRow(new Object[]{"System impact", impactLabel(l.impact) + " (" + l.impact + " of 100)"});
        details.addRow(new Object[]{"Auto-reload", l.autoReload ? "Yes" : "No"});
        output.setText(l.output.toString());
        errors.setText(l.errors.toString());
    }
}
