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
 * existing project to reopen. Shown as a welcome dialog when the app starts.
 */
public final class ProjectStartupDialog extends JDialog {

    public enum Mode { TEMPORARY, NEW, OPEN }

    /** Fixed width of the "Name:" / "File:" label column so every field aligns. */
    private static final int LABEL_W = 56;

    private final JRadioButton temporary = new JRadioButton("Temporary project in memory", true);
    private final JRadioButton newOnDisk = new JRadioButton("New project on disk");
    private final JRadioButton openExisting = new JRadioButton("Open existing project");
    private final JTextField newName = Ui.field("");
    private final JTextField newFile = Ui.field("");
    private final JTextField openFile = Ui.field("");
    private final JCheckBox trust = new JCheckBox("Trust this project file", true);
    private final JCheckBox pause = new JCheckBox("Pause automated tasks");
    private final List<RecentProjects.Entry> recents = RecentProjects.load();
    private final JTable recentsTable = new JTable();

    private boolean proceeded;

    public ProjectStartupDialog(Frame owner, String title) {
        super(owner, title, true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setIconImages(AppIcon.images());

        ButtonGroup g = new ButtonGroup();
        g.add(temporary); g.add(newOnDisk); g.add(openExisting);
        for (JRadioButton r : new JRadioButton[]{temporary, newOnDisk, openExisting}) {
            r.setFont(Ui.BOLD); r.setOpaque(false); r.setFocusable(false);
        }

        // The Name and the file name are one thing: edit either and the other
        // follows, so a project can never be shown under a name its file does
        // not carry.
        newName.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { syncFileToName(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { syncFileToName(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { syncFileToName(); }
        });
        newFile.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { syncNameToFile(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { syncNameToFile(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { syncNameToFile(); }
        });

        // Only fill in a default name once "New project on disk" is actually
        // chosen (by click or by the folder picker), never while on a temporary
        // or existing project.
        newOnDisk.addItemListener(e -> {
            if (!newOnDisk.isSelected()) return;
            if (newName.getText().trim().isEmpty()) newName.setText(defaultProjectName());
            // Default the file to the user's home directory, shown in full.
            if (newFile.getText().trim().isEmpty()) {
                newFile.setText(new File(System.getProperty("user.home"), projectFileName()).getAbsolutePath());
            }
        });

        // A two-column grid: radio labels in a left column, their inputs in an
        // aligned right column, with a thin separator between each section.
        JPanel body = new JPanel(new GridBagLayout());
        body.setBackground(Theme.BG);
        body.setBorder(Ui.pad(14, 20, 10, 20));
        int y = 0;

        addSpan(body, header(), y++);
        addSeparator(body, y++);
        addRow(body, temporary, null, y++);
        addSeparator(body, y++);
        addRow(body, newOnDisk, newFields(), y++);
        addSeparator(body, y++);
        addRow(body, openExisting, openFields(), y++);

        setLayout(new BorderLayout());
        add(body, BorderLayout.CENTER);
        add(buttons(), BorderLayout.SOUTH);
        // Open at the form's natural size so the whole thing is visible at once,
        // with no scroll bar. The dialog stays resizable if the user wants room.
        pack();
        setSize(Math.max(780, getWidth()), getHeight());
        setLocationRelativeTo(null);

        // pack() runs before the window manager adds its title bar, so on many
        // Linux desktops the decoration insets are still zero and the window
        // opens a title-bar's-worth too short, clipping the bottom controls.
        // Once the window is actually shown the real insets are known, so grow
        // it then to guarantee the whole form fits.
        addWindowListener(new java.awt.event.WindowAdapter() {
            private boolean fixed;
            @Override public void windowOpened(java.awt.event.WindowEvent e) {
                if (fixed) return;
                fixed = true;
                // Make sure the dialog is raised above other windows and focused.
                toFront();
                requestFocus();
                SwingUtilities.invokeLater(() -> {
                    Dimension pref = getContentPane().getPreferredSize();
                    Insets in = getInsets();
                    int top = in.top > 0 ? in.top : 34;
                    int wantW = Math.max(780, pref.width + in.left + in.right);
                    int wantH = pref.height + top + in.bottom + 6;
                    if (getWidth() < wantW || getHeight() < wantH) {
                        setSize(Math.max(getWidth(), wantW), Math.max(getHeight(), wantH));
                        setLocationRelativeTo(null);
                    }
                });
            }
        });
    }

    // ---- grid helpers ----

    /** A section row: the radio label at the top of the left column, inputs on the right. */
    private void addRow(JPanel grid, JComponent label, JComponent fields, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = y; c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(8, 0, 8, 20);
        grid.add(label, c);
        if (fields != null) {
            c.gridx = 1; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(8, 0, 8, 0);
            grid.add(fields, c);
        }
    }

    private void addSpan(JPanel grid, JComponent comp, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = y; c.gridwidth = 2; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(0, 0, 4, 0);
        grid.add(comp, c);
    }

    private void addSeparator(JPanel grid, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.gridy = y; c.gridwidth = 2; c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL; c.insets = new Insets(2, 0, 2, 0);
        JSeparator s = new JSeparator();
        s.setForeground(Ui.GRID);
        grid.add(s, c);
    }

    /** A labelled input row with a fixed-width right-aligned label so fields align. */
    private JComponent formRow(String label, JComponent field) {
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        JLabel l = new JLabel(label, SwingConstants.RIGHT);
        l.setFont(Ui.PLAIN);
        l.setPreferredSize(new Dimension(LABEL_W, l.getPreferredSize().height));
        row.add(l, BorderLayout.WEST);
        row.add(field, BorderLayout.CENTER);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        return row;
    }

    // ---- sections ----

    private JComponent header() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JPanel leftSide = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        leftSide.setOpaque(false);
        leftSide.add(helpGlyph());
        JLabel welcome = new JLabel("Welcome to OpenTRAP. Use the options below to create or open a project.");
        welcome.setFont(Ui.PLAIN);
        leftSide.add(welcome);
        p.add(leftSide, BorderLayout.WEST);
        JLabel logo = new JLabel("OpenTRAP", AppIcon.icon(32), SwingConstants.RIGHT);
        logo.setFont(Ui.HEADING.deriveFont(18f));
        logo.setForeground(Theme.ACCENT);
        logo.setIconTextGap(8);
        p.add(logo, BorderLayout.EAST);
        return p;
    }

    private JComponent newFields() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        p.add(formRow("Name:", newName));
        p.add(Box.createVerticalStrut(6));
        p.add(formRow("File:", fileRow(newFile)));
        return p;
    }

    private JComponent openFields() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);

        // Recent-projects table: Name, File, Last Accessed. Clicking a row fills
        // the File field and selects this option. Shown only when there are
        // recents, so first launch stays compact.
        if (!recents.isEmpty()) {
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
            sp.setPreferredSize(new Dimension(620, 150));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 150));
            sp.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(sp);
            p.add(Box.createVerticalStrut(8));
        }
        p.add(formRow("File:", fileRow(openFile)));

        // Checkboxes, indented so they line up under the field, not the label.
        trust.setFont(Ui.PLAIN); trust.setOpaque(false); trust.setEnabled(false);
        pause.setFont(Ui.PLAIN); pause.setOpaque(false);
        trust.setAlignmentX(Component.LEFT_ALIGNMENT);
        pause.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel checks = new JPanel();
        checks.setLayout(new BoxLayout(checks, BoxLayout.Y_AXIS));
        checks.setOpaque(false);
        checks.setBorder(Ui.pad(6, LABEL_W + 8, 0, 0));
        checks.setAlignmentX(Component.LEFT_ALIGNMENT);
        checks.add(trust);
        checks.add(pause);
        p.add(checks);
        return p;
    }

    private JComponent fileRow(JTextField field) {
        boolean isNew = field == newFile;
        JButton choose = Ui.button(isNew ? "Choose folder..." : "Choose file...");
        choose.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (isNew) {
                // Pick a folder; the file name comes from the project Name, so the
                // user never has to type a filename and the name is always kept.
                fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                fc.setDialogTitle("Choose a folder for the new project");
                // Start in the current project directory, or the home directory.
                String cur = newFile.getText().trim();
                File start = cur.isEmpty() ? new File(System.getProperty("user.home"))
                                           : new File(cur).getParentFile();
                if (start != null && start.isDirectory()) fc.setCurrentDirectory(start);
                if (fc.showDialog(this, "Select folder") == JFileChooser.APPROVE_OPTION) {
                    newOnDisk.setSelected(true);   // fills a default name if none yet
                    newFile.setText(new File(fc.getSelectedFile(), projectFileName()).getAbsolutePath());
                }
            } else {
                if (fc.showDialog(this, "Select") == JFileChooser.APPROVE_OPTION) {
                    field.setText(fc.getSelectedFile().getAbsolutePath());
                    openExisting.setSelected(true);
                }
            }
        });
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        row.add(choose, BorderLayout.EAST);
        return row;
    }

    /** The default project name, shown with its .otrap extension so the user sees the full filename. */
    private static String defaultProjectName() {
        return StateIO.defaultProjectFileName();
    }

    /**
     * The on-disk file name derived from the Name field. The Name already shows
     * the .otrap extension, so this mirrors it exactly (just making the characters
     * filesystem-safe) rather than re-appending an extension, which would double
     * it up to ".otra.otrap" as the user edits.
     */
    private String projectFileName() {
        String n = newName.getText().trim();
        if (n.isEmpty()) n = defaultProjectName();
        return n.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    // Set while one field is being written from the other, so the two document
    // listeners do not feed each other.
    private boolean syncing;

    /** Keep the chosen file in step with the Name: edit the name, the file follows. */
    private void syncFileToName() {
        if (syncing) return;
        String path = newFile.getText().trim();
        if (path.isEmpty()) return;
        File parent = new File(path).getParentFile();
        if (parent == null) return;
        String want = new File(parent, projectFileName()).getAbsolutePath();
        if (want.equals(path)) return;
        syncing = true;
        try { SwingUtilities.invokeLater(() -> { newFile.setText(want); syncing = false; }); }
        catch (RuntimeException e) { syncing = false; throw e; }
    }

    /** Keep the Name in step with the chosen file: edit the file, the name follows. */
    private void syncNameToFile() {
        if (syncing) return;
        String path = newFile.getText().trim();
        if (path.isEmpty()) return;
        String want = new File(path).getName();
        if (want.isEmpty() || want.equals(newName.getText().trim())) return;
        syncing = true;
        try { SwingUtilities.invokeLater(() -> { newName.setText(want); syncing = false; }); }
        catch (RuntimeException e) { syncing = false; throw e; }
    }

    /** A small grey question-mark glyph, matching the welcome header. */
    private static JComponent helpGlyph() {
        JComponent c = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.MUTED);
                g2.setStroke(new BasicStroke(1.4f));
                g2.drawOval(2, 2, 19, 19);
                g2.setFont(Ui.BOLD);
                FontMetrics fm = g2.getFontMetrics();
                int sw = fm.stringWidth("?");
                g2.drawString("?", (24 - sw) / 2f, 2 + (19 + fm.getAscent()) / 2f - 1);
                g2.dispose();
            }
        };
        c.setPreferredSize(new Dimension(24, 24));
        return c;
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

    /** The project's name, which is its file name. */
    public String projectName() {
        File f = file();
        if (f != null && !f.getName().isBlank()) return f.getName();
        return newName.getText().trim();
    }

    /** Whether to open without automatically starting scanning tasks. */
    public boolean pauseTasks() { return pause.isSelected(); }
}
