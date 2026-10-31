package opentrap.extensions;

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
        final String type, name;
        final File file;
        String status = "Loaded";
        final StringBuilder output = new StringBuilder();
        final StringBuilder errors = new StringBuilder();
        Loaded(String type, String name, File file) { this.type = type; this.name = name; this.file = file; }
    }

    private final List<Loaded> items = new ArrayList<>();
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Loaded", "Type", "Name"}, 0) {
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
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
        model.addTableModelListener(e -> {
            if (e.getColumn() == 0 && e.getFirstRow() >= 0 && e.getFirstRow() < items.size())
                items.get(e.getFirstRow()).enabled = Boolean.TRUE.equals(model.getValueAt(e.getFirstRow(), 0));
        });
        table.getSelectionModel().addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) showSelected(); });

        JPanel centre = new JPanel(new BorderLayout(8, 0));
        centre.setOpaque(false);
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
        items.add(l);
        model.addRow(new Object[]{l.enabled, l.type, l.name});
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
        output.setText(l.output.toString());
        errors.setText(l.errors.toString());
    }
}
