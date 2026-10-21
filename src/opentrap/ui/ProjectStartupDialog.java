package opentrap.ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.List;

/**
 * The startup screen, shown before the main window, where the user chooses a
 * project: a temporary in-memory session, a new project saved to disk, or an
 * existing project to reopen. Mirrors Burp's welcome dialog.
 */
public final class ProjectStartupDialog extends JDialog {

    public enum Mode { TEMPORARY, NEW, OPEN }

    private final JRadioButton temporary = new JRadioButton("Temporary project in memory", true);
    private final JRadioButton newOnDisk = new JRadioButton("New project on disk");
    private final JRadioButton openExisting = new JRadioButton("Open existing project");
    private final JTextField newName = Ui.field("", 24);
    private final JTextField newFile = Ui.field("", 24);
    private final JTextField openFile = Ui.field("", 24);
    private final JCheckBox trust = new JCheckBox("Trust this project file", true);
    private final JCheckBox pause = new JCheckBox("Pause automated tasks");
    private final List<RecentProjects.Entry> recents = RecentProjects.load();
    private final JTable recentsTable = new JTable();

    private boolean proceeded;

    public ProjectStartupDialog(String title) {
        super((Frame) null, title, true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        ButtonGroup g = new ButtonGroup();
        g.add(temporary); g.add(newOnDisk); g.add(openExisting);
        for (JRadioButton r : new JRadioButton[]{temporary, newOnDisk, openExisting}) {
            r.setFont(Ui.BOLD); r.setOpaque(false); r.setFocusable(false);
        }

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBackground(Theme.BG);
        body.setBorder(Ui.pad(18, 22, 12, 22));

        body.add(header());
        body.add(Box.createVerticalStrut(16));
        body.add(left(temporary));
        body.add(Box.createVerticalStrut(14));
        body.add(left(newOnDisk));
        body.add(left(indent(Ui.form("Name:", newName, "File:", fileRow(newFile)))));
        body.add(Box.createVerticalStrut(14));
        body.add(left(openExisting));
        body.add(left(indent(openSection())));

        JScrollPane bodyScroll = new JScrollPane(body,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        bodyScroll.setBorder(BorderFactory.createEmptyBorder());
        bodyScroll.getVerticalScrollBar().setUnitIncrement(16);

        setLayout(new BorderLayout());
        add(bodyScroll, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);
        pack();
        setSize(Math.max(760, getWidth()), Math.min(720, getHeight()));
        setLocationRelativeTo(null);
    }

    private JComponent header() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JLabel welcome = new JLabel("Welcome to OpenTRAP. Use the options below to create or open a project.");
        welcome.setFont(Ui.PLAIN);
        p.add(welcome, BorderLayout.WEST);
        JLabel logo = new JLabel("OpenTRAP", AppIcon.icon(32), SwingConstants.RIGHT);
        logo.setFont(Ui.HEADING.deriveFont(18f));
        logo.setForeground(Theme.ACCENT);
        logo.setIconTextGap(8);
        p.add(logo, BorderLayout.EAST);
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    private JComponent openSection() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);

        // Recent-projects table: Name, File, Last Accessed. Clicking a row fills
        // the File field below and selects the "Open existing project" option.
        String[] cols = {"Name", "File", "Last Accessed"};
        DefaultTableModel model = new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
        SimpleDateFormat fmt = new SimpleDateFormat("MMM d, yyyy");
        for (RecentProjects.Entry e : recents) {
            model.addRow(new Object[]{e.name(), e.file().getAbsolutePath(), fmt.format(e.lastAccessed())});
        }
        recentsTable.setModel(model);
        recentsTable.setFont(Ui.PLAIN);
        recentsTable.getTableHeader().setReorderingAllowed(false);
        recentsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        recentsTable.getColumnModel().getColumn(0).setPreferredWidth(140);
        recentsTable.getColumnModel().getColumn(1).setPreferredWidth(360);
        recentsTable.getColumnModel().getColumn(2).setPreferredWidth(120);
        recentsTable.getSelectionModel().addListSelectionListener(ev -> {
            if (ev.getValueIsAdjusting()) return;
            int row = recentsTable.getSelectedRow();
            if (row >= 0) {
                openFile.setText((String) model.getValueAt(row, 1));
                openExisting.setSelected(true);
            }
        });
        JScrollPane sp = new JScrollPane(recentsTable);
        sp.setPreferredSize(new Dimension(640, 150));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(sp);
        p.add(Box.createVerticalStrut(6));
        p.add(left(Ui.form("File:", fileRow(openFile))));

        trust.setFont(Ui.PLAIN); trust.setOpaque(false); trust.setEnabled(false);
        pause.setFont(Ui.PLAIN); pause.setOpaque(false);
        trust.setAlignmentX(Component.LEFT_ALIGNMENT);
        pause.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(Box.createVerticalStrut(4));
        p.add(trust);
        p.add(pause);
        return p;
    }

    private JComponent fileRow(JTextField field) {
        JButton choose = Ui.button("Choose file...");
        choose.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showDialog(this, "Select") == JFileChooser.APPROVE_OPTION) {
                field.setText(fc.getSelectedFile().getAbsolutePath());
                if (field == newFile) newOnDisk.setSelected(true);
                if (field == openFile) openExisting.setSelected(true);
            }
        });
        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        row.add(choose, BorderLayout.EAST);
        return row;
    }

    private JComponent buttons() {
        JButton cancel = Ui.button("Cancel");
        JButton next = Ui.primaryButton("Next", "open the project");
        cancel.addActionListener(e -> { proceeded = false; dispose(); });
        next.addActionListener(e -> onNext());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID));
        bar.add(cancel); bar.add(next);
        return bar;
    }

    private void onNext() {
        if (newOnDisk.isSelected() && newFile.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Choose a file for the new project.",
                    "New project", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (openExisting.isSelected() && openFile.getText().trim().isEmpty()) {
            JOptionPane.showMessageDialog(this, "Choose a project file to open.",
                    "Open project", JOptionPane.WARNING_MESSAGE);
            return;
        }
        proceeded = true;
        dispose();
    }

    // ---- result ----

    public boolean proceeded() { return proceeded; }

    public Mode mode() {
        if (newOnDisk.isSelected()) return Mode.NEW;
        if (openExisting.isSelected()) return Mode.OPEN;
        return Mode.TEMPORARY;
    }

    public File file() {
        String t = mode() == Mode.NEW ? newFile.getText().trim()
                 : mode() == Mode.OPEN ? openFile.getText().trim() : "";
        return t.isEmpty() ? null : new File(t);
    }

    /** The name the user gave a new project, else the file's base name. */
    public String projectName() {
        String n = newName.getText().trim();
        File f = file();
        return !n.isEmpty() ? n : (f == null ? "" : RecentProjects.baseName(f));
    }

    /** Whether to open without automatically starting scanning tasks. */
    public boolean pauseTasks() { return pause.isSelected(); }

    private static JComponent left(JComponent c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); return c; }

    private static JComponent indent(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(Ui.pad(4, 26, 0, 0));
        p.add(c, BorderLayout.CENTER);
        return p;
    }
}
