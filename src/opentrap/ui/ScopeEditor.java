package opentrap.ui;

import opentrap.core.ProjectOptions;
import opentrap.core.Scope;
import opentrap.core.SuiteContext;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;

/**
 * The include and exclude tables of the target scope.
 *
 * <p>By default each table lists URL prefixes, with "include subdomains" on the
 * include side, and Add opens a small prefix dialog, as the reference does.
 * Ticking "Use advanced scope control" shows the same rules as protocol, host,
 * port and file regexes instead, which is where a rule that cannot be said as
 * a prefix is written. The choice is a project setting.
 */
public class ScopeEditor extends JPanel {

    private final SuiteContext ctx;
    private final Scope scope;
    private final CardLayout cards = new CardLayout();
    private final JPanel body = new JPanel(cards);
    private final JCheckBox advanced;
    private final RulePanel simpleInclude, simpleExclude, advancedInclude, advancedExclude;

    public ScopeEditor(SuiteContext ctx, Scope scope) {
        super();
        this.ctx = ctx;
        this.scope = scope;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        advanced = Ui.check("Use advanced scope control", ctx.settings().getBool(ProjectOptions.SCOPE_ADVANCED, false));
        advanced.setToolTipText("edit the rules as protocol, host, port and file regexes");
        advanced.setAlignmentX(LEFT_ALIGNMENT);
        advanced.addActionListener(e -> {
            ctx.settings().setBool(ProjectOptions.SCOPE_ADVANCED, advanced.isSelected());
            showMode();
        });
        add(advanced);
        add(Box.createVerticalStrut(10));

        simpleInclude = new RulePanel(scope.include(), true, false);
        simpleExclude = new RulePanel(scope.exclude(), false, false);
        advancedInclude = new RulePanel(scope.include(), true, true);
        advancedExclude = new RulePanel(scope.exclude(), false, true);
        body.setOpaque(false);
        body.add(tables(simpleInclude, simpleExclude), "simple");
        body.add(tables(advancedInclude, advancedExclude), "advanced");
        body.setAlignmentX(LEFT_ALIGNMENT);
        add(body);
        showMode();

        ctx.addScopeListener(() -> SwingUtilities.invokeLater(this::refresh));
    }

    private static JPanel tables(RulePanel include, RulePanel exclude) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        JLabel inc = Ui.bold("Include in scope");
        inc.setAlignmentX(LEFT_ALIGNMENT);
        p.add(inc);
        p.add(Box.createVerticalStrut(6));
        p.add(include);
        p.add(Box.createVerticalStrut(16));
        JLabel exc = Ui.bold("Exclude from scope");
        exc.setAlignmentX(LEFT_ALIGNMENT);
        p.add(exc);
        p.add(Box.createVerticalStrut(6));
        p.add(exclude);
        return p;
    }

    private void showMode() {
        cards.show(body, advanced.isSelected() ? "advanced" : "simple");
        revalidate();
        repaint();
    }

    public void refresh() {
        for (RulePanel p : new RulePanel[]{simpleInclude, simpleExclude, advancedInclude, advancedExclude}) p.model.fireTableDataChanged();
        repaint();
    }

    /**
     * The prefix dialog: "Specify a prefix for URLs you want to match", a
     * prefix field, "Include subdomains" on the include side, Paste URL, OK and
     * Cancel. Returns the rule, or null when cancelled or not a URL.
     */
    public static Scope.Rule prefixDialog(Component parent, boolean include, Scope.Rule editing) {
        String title = (editing == null ? "Add" : "Edit") + " prefix for " + (include ? "in-scope" : "out-of-scope") + " URLs";
        JTextField prefix = Ui.field(editing == null ? "" : editing.prefix);
        JCheckBox subdomains = Ui.check("Include subdomains", editing != null && editing.subdomains);
        JButton paste = Ui.button("Paste URL", "fill the prefix from the clipboard");
        paste.addActionListener(e -> {
            try {
                Object t = java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                        .getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (t != null) prefix.setText(String.valueOf(t).trim());
            } catch (Exception ignore) {}
        });

        JPanel form = new JPanel();
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(Ui.pad(12, 16, 8, 16));
        JLabel lead = Ui.label("Specify a prefix for URLs you want to match.");
        lead.setAlignmentX(LEFT_ALIGNMENT);
        form.add(lead);
        form.add(Box.createVerticalStrut(12));
        JPanel row = Ui.form("Prefix:", prefix);
        row.setAlignmentX(LEFT_ALIGNMENT);
        form.add(row);
        if (include) {
            form.add(Box.createVerticalStrut(10));
            subdomains.setAlignmentX(LEFT_ALIGNMENT);
            form.add(subdomains);
        }
        form.add(Box.createVerticalStrut(12));
        JPanel pasteRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        pasteRow.setOpaque(false);
        pasteRow.add(paste);
        pasteRow.setAlignmentX(LEFT_ALIGNMENT);
        form.add(pasteRow);

        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        final Scope.Rule[] result = new Scope.Rule[1];
        JButton ok = Ui.primaryButton("OK", "add the prefix");
        JButton cancel = Ui.button("Cancel");
        ok.addActionListener(e -> {
            String p = Scope.normalisePrefix(prefix.getText());
            if (p == null) {
                JOptionPane.showMessageDialog(d, "Enter a URL prefix such as https://example.com/app.",
                        title, JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Scope.Rule r = editing == null ? new Scope.Rule() : editing;
            r.prefix = p;
            r.subdomains = include && subdomains.isSelected();
            r.enabled = editing == null || editing.enabled;
            result[0] = r;
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(ok); buttons.add(cancel);
        JPanel root = new JPanel(new BorderLayout());
        root.add(form, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.getRootPane().setDefaultButton(ok);
        d.pack();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
        return result[0];
    }

    private class RulePanel extends JPanel {
        private final List<Scope.Rule> rules;
        private final boolean include;
        private final boolean advancedMode;
        private final Model model;
        private final JTable table;
        // Advanced mode's inline rule entry.
        private final JComboBox<String> protocol = Ui.combo(new String[]{"any", "http", "https"});
        private final JTextField host = Ui.field("");
        private final JTextField port = Ui.shortField("");
        private final JTextField file = Ui.field("");
        private final JButton addBtn = Ui.button("Add", "add the item");
        private int editingRow = -1;

        RulePanel(List<Scope.Rule> rules, boolean include, boolean advancedMode) {
            super(new BorderLayout(8, 6));
            setOpaque(false);
            setAlignmentX(LEFT_ALIGNMENT);
            this.rules = rules;
            this.include = include;
            this.advancedMode = advancedMode;
            this.model = new Model();
            this.table = new JTable(model);
            Ui.styleTable(table);
            if (advancedMode) {
                Ui.setWidths(table, 36, 70, 180, 50, 200);
            } else if (include) {
                Ui.setWidths(table, 60, 420, 130);
            } else {
                Ui.setWidths(table, 60, 550);
            }
            table.getColumnModel().getColumn(0).setMaxWidth(advancedMode ? 36 : 60);

            JScrollPane sp = Ui.scroll(table);
            sp.setPreferredSize(new Dimension(620, 110));
            add(sp, BorderLayout.CENTER);

            // The button column sits to the left of the table, as in the reference.
            JPanel buttons = new JPanel(new GridLayout(5, 1, 0, 4));
            buttons.setOpaque(false);
            JButton edit = Ui.button("Edit", "edit the selected item");
            JButton remove = Ui.button("Remove", "remove the selected item");
            JButton pasteUrl = Ui.button("Paste URL", "add the URL on the clipboard as an item");
            JButton load = Ui.button("Load ...", "add every URL listed in a file, one per line");
            buttons.add(addBtn);
            buttons.add(edit);
            buttons.add(remove);
            buttons.add(pasteUrl);
            buttons.add(load);
            JPanel bwrap = new JPanel(new BorderLayout());
            bwrap.setOpaque(false);
            bwrap.add(buttons, BorderLayout.NORTH);
            add(bwrap, BorderLayout.WEST);
            pasteUrl.addActionListener(e -> {
                String text;
                try {
                    text = String.valueOf(java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                            .getData(java.awt.datatransfer.DataFlavor.stringFlavor));
                } catch (Exception ex) { text = ""; }
                if (!addUrl(text)) {
                    JOptionPane.showMessageDialog(this, "The clipboard does not hold a URL.",
                            "Paste URL", JOptionPane.INFORMATION_MESSAGE);
                }
            });
            load.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setDialogTitle("Load URLs");
                if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
                int n = 0;
                try {
                    for (String line : java.nio.file.Files.readAllLines(fc.getSelectedFile().toPath())) {
                        if (addUrl(line)) n++;
                    }
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "Could not read the file: " + ex.getMessage(),
                            "Load URLs", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (n == 0) JOptionPane.showMessageDialog(this, "No URLs were found in the file.",
                        "Load URLs", JOptionPane.INFORMATION_MESSAGE);
            });

            if (advancedMode) {
                protocol.setToolTipText("choose the protocol to match");
                host.setToolTipText("hostname regex or IP range to match (leave blank to match any)");
                port.setToolTipText("port regex to match (leave blank to match any)");
                file.setToolTipText("file regex to match (leave blank to match any)");
                JPanel entry = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 2));
                entry.setOpaque(false);
                entry.add(Ui.label("New rule:"));
                entry.add(protocol);
                entry.add(host);
                entry.add(port);
                entry.add(file);
                add(entry, BorderLayout.SOUTH);
                addBtn.addActionListener(e -> commit());
                edit.addActionListener(e -> beginEdit());
            } else {
                addBtn.addActionListener(e -> {
                    Scope.Rule r = prefixDialog(this, include, null);
                    if (r == null) return;
                    rules.add(r);
                    changed();
                });
                edit.addActionListener(e -> {
                    int i = table.getSelectedRow();
                    if (i < 0) return;
                    Scope.Rule r = rules.get(i);
                    if (!r.isPrefix()) {
                        JOptionPane.showMessageDialog(this,
                                "This rule matches by regex. Tick \"Use advanced scope control\" to edit it.",
                                "Edit rule", JOptionPane.INFORMATION_MESSAGE);
                        return;
                    }
                    if (prefixDialog(this, include, r) != null) changed();
                });
            }
            remove.addActionListener(e -> removeSelected());
            setMaximumSize(new Dimension(820, advancedMode ? 210 : 170));
        }

        /** Turn a URL into a prefix rule and add it. */
        private boolean addUrl(String text) {
            String p = Scope.normalisePrefix(text);
            if (p == null) return false;
            for (Scope.Rule r : rules) if (p.equals(r.prefix)) { r.enabled = true; changed(); return true; }
            rules.add(Scope.Rule.prefix(p, false));
            changed();
            return true;
        }

        private void changed() {
            editingRow = -1;
            model.fireTableDataChanged();
            ctx.scopeChanged();
        }

        private void commit() {
            Scope.Rule r = editingRow >= 0 ? rules.get(editingRow) : new Scope.Rule();
            r.protocol = String.valueOf(protocol.getSelectedItem());
            // The fields show the friendly, unquoted form of each pattern. If the
            // user left a field untouched, keep its exact stored pattern so an
            // auto-created rule (^\Qhost\E$) round-trips without quietly losing
            // its anchors or literal quoting; only a field they actually retyped
            // is stored as typed.
            r.host = keptOrTyped(host.getText().trim(), r.host, editingRow >= 0);
            r.port = keptOrTyped(port.getText().trim(), r.port, editingRow >= 0);
            r.file = keptOrTyped(file.getText().trim(), r.file, editingRow >= 0);
            // A rule written as regexes is no longer a prefix rule.
            r.prefix = null;
            r.subdomains = false;
            if (editingRow < 0) { r.enabled = true; rules.add(r); }
            editingRow = -1;
            addBtn.setText("Add");
            host.setText(""); port.setText(""); file.setText("");
            model.fireTableDataChanged();
            ctx.scopeChanged();
        }

        private void beginEdit() {
            int i = table.getSelectedRow();
            if (i < 0) return;
            Scope.Rule r = rules.get(i);
            if (r.isPrefix()) {
                // Seed the regex fields from the prefix, so editing it here turns it
                // into an equivalent advanced rule the user can then refine.
                try {
                    String p = r.prefix.contains("://") ? r.prefix : "https://" + r.prefix;
                    java.net.URI u = new java.net.URI(p);
                    protocol.setSelectedItem(r.prefix.contains("://") ? u.getScheme() : "any");
                    host.setText(r.subdomains ? "(^|\\.)" + java.util.regex.Pattern.quote(u.getHost()) + "$"
                            : "^" + java.util.regex.Pattern.quote(u.getHost()) + "$");
                    port.setText(u.getPort() > 0 ? "^" + u.getPort() + "$" : "");
                    String path = u.getRawPath();
                    file.setText(path == null || path.isEmpty() ? "" : "^" + java.util.regex.Pattern.quote(path));
                } catch (Exception ignore) {}
            } else {
                protocol.setSelectedItem(r.protocol);
                host.setText(Scope.display(r.host));
                port.setText(Scope.display(r.port));
                file.setText(Scope.display(r.file));
            }
            editingRow = i;
            addBtn.setText("Update");
        }

        /** On edit, keep a field's stored pattern verbatim when the user left the
         *  friendly form unchanged; otherwise store what they typed. A brand-new
         *  rule always stores the typed text. */
        private static String keptOrTyped(String typed, String stored, boolean editing) {
            if (editing && typed.equals(Scope.display(stored))) return stored;
            return typed;
        }

        private void removeSelected() {
            int[] sel = table.getSelectedRows();
            for (int i = sel.length - 1; i >= 0; i--) rules.remove(sel[i]);
            model.fireTableDataChanged();
            ctx.scopeChanged();
        }

        private class Model extends AbstractTableModel {
            private final String[] advancedCols = {"Enabled", "Protocol", "Host / IP range", "Port", "File"};
            private final String[] includeCols = {"Enabled", "Prefix", "Include subdomains"};
            private final String[] excludeCols = {"Enabled", "Prefix"};
            private String[] cols() { return advancedMode ? advancedCols : (include ? includeCols : excludeCols); }
            @Override public int getRowCount() { return rules.size(); }
            @Override public int getColumnCount() { return cols().length; }
            @Override public String getColumnName(int c) { return cols()[c]; }
            @Override public Class<?> getColumnClass(int c) {
                if (c == 0) return Boolean.class;
                if (!advancedMode && include && c == 2) return Boolean.class;
                return String.class;
            }
            @Override public boolean isCellEditable(int r, int c) {
                if (c == 0) return true;
                return !advancedMode && include && c == 2 && rules.get(r).isPrefix();
            }

            @Override public Object getValueAt(int r, int c) {
                Scope.Rule x = rules.get(r);
                if (!advancedMode) {
                    return switch (c) {
                        case 0 -> x.enabled;
                        case 1 -> x.describe();
                        default -> x.isPrefix() && x.subdomains;
                    };
                }
                return switch (c) {
                    case 0 -> x.enabled;
                    case 1 -> x.isPrefix() ? "" : x.protocol;
                    case 2 -> x.isPrefix() ? x.prefix + (x.subdomains ? " (and subdomains)" : "") : Scope.display(x.host);
                    case 3 -> x.isPrefix() ? "" : Scope.display(x.port);
                    default -> x.isPrefix() ? "" : Scope.display(x.file);
                };
            }

            @Override public void setValueAt(Object v, int r, int c) {
                if (c == 0) { rules.get(r).enabled = Boolean.TRUE.equals(v); ctx.scopeChanged(); }
                else if (!advancedMode && include && c == 2) { rules.get(r).subdomains = Boolean.TRUE.equals(v); ctx.scopeChanged(); }
            }
        }
    }
}
