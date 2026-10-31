package opentrap.dashboard;

import opentrap.engine.LoginStore;
import opentrap.ui.Glyphs;
import opentrap.ui.LibraryDialog;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The scan wizard's Application login step: use recorded login sequences, or
 * username and password credentials. Each mode has its table and New / Edit /
 * Delete (Replay for recorded logins), with Select from library and Save to
 * library beneath. Recorded logins are recorded manually: a script of steps
 * pasted or typed, with an optional status check.
 */
public final class ApplicationLoginPanel extends JPanel {

    private final JRadioButton useRecorded = Ui.radio("Use recorded login sequences (record your login sequence using the browser extension)", true);
    private final JRadioButton useCredentials = Ui.radio("Use login credentials (username and password)", false);
    private final List<LoginStore.RecordedLogin> recorded = new ArrayList<>();
    private final List<LoginStore.Credentials> credentials = new ArrayList<>();
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final AbstractTableModel recModel = new AbstractTableModel() {
        @Override public int getRowCount() { return recorded.size(); }
        @Override public int getColumnCount() { return 1; }
        @Override public String getColumnName(int c) { return "Label"; }
        @Override public Object getValueAt(int r, int c) { return recorded.get(r).label; }
    };
    private final AbstractTableModel credModel = new AbstractTableModel() {
        @Override public int getRowCount() { return credentials.size(); }
        @Override public int getColumnCount() { return 2; }
        @Override public String getColumnName(int c) { return c == 0 ? "Label" : "Username"; }
        @Override public Object getValueAt(int r, int c) { return c == 0 ? credentials.get(r).label : credentials.get(r).username; }
    };
    private final JTable recTable = new JTable(recModel);
    private final JTable credTable = new JTable(credModel);

    public ApplicationLoginPanel() {
        super(new BorderLayout(0, 10));
        setOpaque(false);
        ButtonGroup g = new ButtonGroup();
        g.add(useRecorded); g.add(useCredentials);
        JPanel radios = Ui.vbox(useRecorded, useCredentials);
        add(radios, BorderLayout.NORTH);

        body.setOpaque(false);
        body.add(table(recTable, true), "recorded");
        body.add(table(credTable, false), "credentials");
        add(body, BorderLayout.CENTER);
        useRecorded.addItemListener(e -> cards.show(body, useRecorded.isSelected() ? "recorded" : "credentials"));

        JPanel links = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        links.setOpaque(false);
        links.add(link("Select from library", this::selectFromLibrary));
        links.add(link("Save to library", this::saveToLibrary));
        add(links, BorderLayout.SOUTH);
    }

    private JComponent table(JTable t, boolean rec) {
        Ui.styleTable(t);
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JButton neu = Ui.button("New ...");
        JButton edit = Ui.button("Edit");
        JButton delete = Ui.button("Delete");
        JButton replay = Ui.button("Replay", "replay the recorded login in the browser");
        if (rec) {
            neu.addActionListener(e -> { LoginStore.RecordedLogin r = RecordedLoginDialog.show(this, null); if (r != null) { recorded.add(r); recModel.fireTableDataChanged(); } });
            edit.addActionListener(e -> { int i = recTable.getSelectedRow(); if (i >= 0 && RecordedLoginDialog.show(this, recorded.get(i)) != null) recModel.fireTableDataChanged(); });
            delete.addActionListener(e -> { int i = recTable.getSelectedRow(); if (i >= 0) { recorded.remove(i); recModel.fireTableDataChanged(); } });
            replay.addActionListener(e -> {
                int i = recTable.getSelectedRow();
                if (i < 0) return;
                JOptionPane.showMessageDialog(this, "Replay runs the recorded steps in the suite's browser.\n"
                        + recorded.get(i).script.lines().count() + " step(s) in \"" + recorded.get(i).label + "\".", "Replay", JOptionPane.INFORMATION_MESSAGE);
            });
        } else {
            neu.addActionListener(e -> { LoginStore.Credentials c = CredentialsDialog.show(this, null); if (c != null) { credentials.add(c); credModel.fireTableDataChanged(); } });
            edit.addActionListener(e -> { int i = credTable.getSelectedRow(); if (i >= 0 && CredentialsDialog.show(this, credentials.get(i)) != null) credModel.fireTableDataChanged(); });
            delete.addActionListener(e -> { int i = credTable.getSelectedRow(); if (i >= 0) { credentials.remove(i); credModel.fireTableDataChanged(); } });
        }
        JPanel p = new JPanel(new BorderLayout(8, 0));
        p.setOpaque(false);
        JScrollPane sp = Ui.scroll(t);
        sp.setPreferredSize(new Dimension(520, 180));
        p.add(sp, BorderLayout.CENTER);
        p.add(rec ? Ui.buttonColumn(neu, edit, delete, replay) : Ui.buttonColumn(neu, edit, delete), BorderLayout.EAST);
        return p;
    }

    private static JComponent link(String text, Runnable action) {
        JLabel l = new JLabel("<html><u>" + text + "</u></html>");
        l.setFont(Ui.PLAIN);
        l.setForeground(new Color(0x1A, 0x5F, 0xB4));
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { action.run(); }
        });
        return l;
    }

    private void selectFromLibrary() {
        String name = LibraryDialog.pick(this, "login", "Load from configuration library");
        if (name == null) return;
        Object o = LoginStore.load(name);
        if (o instanceof LoginStore.RecordedLogin r) { recorded.add(r); recModel.fireTableDataChanged(); useRecorded.setSelected(true); }
        else if (o instanceof LoginStore.Credentials c) { credentials.add(c); credModel.fireTableDataChanged(); useCredentials.setSelected(true); }
    }

    private void saveToLibrary() {
        try {
            if (useRecorded.isSelected()) {
                int i = recTable.getSelectedRow();
                if (i < 0) { JOptionPane.showMessageDialog(this, "Select a recorded login to save.", "Save to library", JOptionPane.INFORMATION_MESSAGE); return; }
                LoginStore.save(recorded.get(i));
            } else {
                int i = credTable.getSelectedRow();
                if (i < 0) { JOptionPane.showMessageDialog(this, "Select a credential to save.", "Save to library", JOptionPane.INFORMATION_MESSAGE); return; }
                LoginStore.save(credentials.get(i));
            }
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Could not save: " + e.getMessage(), "Save to library", JOptionPane.ERROR_MESSAGE);
        }
    }

    public boolean usesCredentials() { return useCredentials.isSelected(); }
    public List<LoginStore.Credentials> credentials() { return new ArrayList<>(credentials); }
    public List<LoginStore.RecordedLogin> recordedLogins() { return new ArrayList<>(recorded); }

    /** A one-line description for the task card. */
    public String describe() {
        if (useCredentials.isSelected()) return credentials.isEmpty() ? "" : credentials.size() + " credential(s)";
        return recorded.isEmpty() ? "" : recorded.size() + " recorded login(s)";
    }

    // ---- dialogs ----

    /** "New Login Credentials": label, username, password (with show/hide). */
    static final class CredentialsDialog {
        static LoginStore.Credentials show(Component parent, LoginStore.Credentials editing) {
            Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
            JDialog d = new JDialog(owner, editing == null ? "New Login Credentials" : "Edit Login Credentials", Dialog.ModalityType.APPLICATION_MODAL);
            LoginStore.Credentials[] out = {null};
            JTextField label = Ui.field(editing == null ? "" : editing.label, 30);
            JTextField user = Ui.field(editing == null ? "" : editing.username, 30);
            JPasswordField pass = new JPasswordField(editing == null ? "" : editing.password, 30);
            pass.setFont(Ui.PLAIN);
            JToggleButton eye = new JToggleButton(Glyphs.search(14, Theme.MUTED));
            eye.setToolTipText("show or hide the password");
            eye.setFocusable(false);
            char echo = pass.getEchoChar();
            eye.addActionListener(e -> pass.setEchoChar(eye.isSelected() ? (char) 0 : echo));
            JPanel form = Ui.form("Label:", label, "Username:", user, "Password:", Ui.row(pass, eye));
            form.setBorder(Ui.pad(16, 18, 8, 18));
            JButton ok = Ui.primaryButton("OK", "save these credentials");
            JButton cancel = Ui.button("Cancel");
            ok.addActionListener(e -> {
                if (label.getText().isBlank()) { JOptionPane.showMessageDialog(d, "Give the credentials a label.", "Login credentials", JOptionPane.WARNING_MESSAGE); return; }
                LoginStore.Credentials c = editing == null ? new LoginStore.Credentials() : editing;
                c.label = label.getText().strip(); c.username = user.getText(); c.password = new String(pass.getPassword());
                out[0] = c; d.dispose();
            });
            cancel.addActionListener(e -> d.dispose());
            JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
            bar.add(ok); bar.add(cancel);
            JPanel root = new JPanel(new BorderLayout());
            root.add(form, BorderLayout.CENTER); root.add(bar, BorderLayout.SOUTH);
            d.setContentPane(root); d.pack(); d.setLocationRelativeTo(parent); d.setVisible(true);
            return out[0];
        }
    }

    /** "New Recorded Login" (manual): label, the script of steps, and a status check. */
    static final class RecordedLoginDialog {
        static LoginStore.RecordedLogin show(Component parent, LoginStore.RecordedLogin editing) {
            Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
            JDialog d = new JDialog(owner, editing == null ? "New Recorded Login" : "Edit Recorded Login", Dialog.ModalityType.APPLICATION_MODAL);
            LoginStore.RecordedLogin[] out = {null};
            JTextField label = Ui.field(editing == null ? "" : editing.label, 40);
            JTextArea script = new JTextArea(editing == null ? "" : editing.script, 12, 50);
            script.setFont(Ui.MONO);
            script.putClientProperty("JTextArea.placeholderText", "Paste Script");
            JTextField statusUrl = Ui.field(editing == null ? "" : editing.statusUrl, 40);
            JTextField confirm = Ui.field(editing == null ? "" : editing.confirmationText, 40);

            JButton asEvents = Ui.button("See as Events", "show each line as a step");
            JButton insertAbove = Ui.button("Insert above");
            JButton insertBelow = Ui.button("Insert below");
            JButton edit = Ui.button("Edit");
            JButton delete = Ui.button("Delete");
            asEvents.addActionListener(e -> {
                StringBuilder sb = new StringBuilder();
                int n = 1;
                for (String line : script.getText().split("\n")) if (!line.isBlank()) sb.append(n++).append(". ").append(line.strip()).append('\n');
                JTextArea view = new JTextArea(sb.toString(), 12, 50);
                view.setEditable(false); view.setFont(Ui.MONO);
                JOptionPane.showMessageDialog(d, Ui.scroll(view), "Steps", JOptionPane.PLAIN_MESSAGE);
            });
            insertAbove.addActionListener(e -> insertLine(script, true));
            insertBelow.addActionListener(e -> insertLine(script, false));
            edit.addActionListener(e -> {
                int line = lineAtCaret(script);
                String cur = lineText(script, line);
                String v = JOptionPane.showInputDialog(d, "Step:", cur);
                if (v != null) replaceLine(script, line, v);
            });
            delete.addActionListener(e -> replaceLine(script, lineAtCaret(script), null));

            JPanel scriptRow = new JPanel(new BorderLayout(8, 0));
            scriptRow.setOpaque(false);
            scriptRow.add(Ui.scroll(script), BorderLayout.CENTER);
            scriptRow.add(Ui.buttonColumn(asEvents, insertAbove, insertBelow, edit, delete), BorderLayout.EAST);

            JPanel body = new JPanel();
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            body.setBorder(Ui.pad(14, 18, 8, 18));
            left(body, Ui.form("Label:", label));
            body.add(Box.createVerticalStrut(8));
            left(body, Ui.bold("Paste Script"));
            left(body, scriptRow);
            body.add(Box.createVerticalStrut(10));
            JPanel sc = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            sc.setOpaque(false);
            sc.add(Ui.bold("Status Check"));
            sc.add(new JLabel(Glyphs.help(14, Theme.MUTED)));
            JLabel req = Ui.label("Required when using TOTP steps");
            req.setForeground(Theme.MUTED);
            sc.add(req);
            left(body, sc);
            left(body, Ui.form("URL:", statusUrl, "Confirmation Text:", confirm));

            JButton open = Ui.button("Open browser to record login", "open the suite's browser to record the steps");
            open.addActionListener(e -> {
                for (Frame f : Frame.getFrames()) if (f instanceof opentrap.ui.SuiteFrame sf) { sf.launchBrowserPublic(); break; }
            });
            JButton ok = Ui.primaryButton("OK", "keep this recorded login");
            JButton cancel = Ui.button("Cancel");
            ok.addActionListener(e -> {
                if (label.getText().isBlank()) { JOptionPane.showMessageDialog(d, "Give the recorded login a label.", "Recorded login", JOptionPane.WARNING_MESSAGE); return; }
                LoginStore.RecordedLogin r = editing == null ? new LoginStore.RecordedLogin() : editing;
                r.label = label.getText().strip(); r.script = script.getText(); r.statusUrl = statusUrl.getText().strip(); r.confirmationText = confirm.getText();
                out[0] = r; d.dispose();
            });
            cancel.addActionListener(e -> d.dispose());
            JPanel bar = new JPanel(new BorderLayout());
            bar.setBorder(Ui.pad(4, 18, 10, 18));
            JPanel l = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0)); l.add(Glyphs.button(Glyphs.help(), "About recorded logins", null)); l.add(open);
            JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); r.add(cancel); r.add(ok);
            bar.add(l, BorderLayout.WEST); bar.add(r, BorderLayout.EAST);
            JPanel root = new JPanel(new BorderLayout());
            root.add(body, BorderLayout.CENTER); root.add(bar, BorderLayout.SOUTH);
            d.setContentPane(root); d.pack(); d.setMinimumSize(d.getSize()); d.setLocationRelativeTo(parent); d.setVisible(true);
            return out[0];
        }

        private static void left(JPanel body, JComponent c) { c.setAlignmentX(Component.LEFT_ALIGNMENT); body.add(c); }

        private static int lineAtCaret(JTextArea a) {
            try { return a.getLineOfOffset(a.getCaretPosition()); } catch (Exception e) { return 0; }
        }
        private static String lineText(JTextArea a, int line) {
            try { int s = a.getLineStartOffset(line), e = a.getLineEndOffset(line); return a.getText(s, e - s).replace("\n", ""); } catch (Exception e) { return ""; }
        }
        private static void replaceLine(JTextArea a, int line, String with) {
            try {
                int s = a.getLineStartOffset(line), e = a.getLineEndOffset(line);
                a.replaceRange(with == null ? "" : with + "\n", s, e);
            } catch (Exception ignore) { }
        }
        private static void insertLine(JTextArea a, boolean above) {
            String v = JOptionPane.showInputDialog(a, "Step:", "");
            if (v == null) return;
            try {
                int line = lineAtCaret(a);
                int pos = above ? a.getLineStartOffset(line) : a.getLineEndOffset(line);
                a.insert(v + "\n", pos);
            } catch (Exception ignore) { }
        }
    }
}
