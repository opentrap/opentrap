package opentrap.session;

import opentrap.core.*;
import opentrap.ui.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.List;
import java.util.Map;

/**
 * Session handling: manage the cookie jar, record login macros, and define the
 * rules that apply the jar and re-authenticate when a session expires. Requests
 * join a macro via the right-click "send to session macro" on any other tool.
 */
public class SessionPanel extends JPanel {

    static final String[] TOOLS = {"proxy", "repeater", "intruder", "sequencer", "scanner"};

    private final SuiteContext ctx;
    private final SessionManager sm;

    private final RuleModel ruleModel = new RuleModel();
    private final MacroListModel macroListModel = new MacroListModel();
    private final ItemModel itemModel = new ItemModel();
    private final ExtractionModel extractionModel = new ExtractionModel();
    private final CookieModel cookieModel = new CookieModel();

    private JList<Macro> macroList;
    private JTable itemTable;
    private MessageEditor itemPreview;
    private JLabel runStatus;

    public SessionPanel(SuiteContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.sm = ctx.sessions();
        JTabbedPane inner = new JTabbedPane();
        inner.setFont(Ui.PLAIN);
        inner.addTab("rules", buildRules());
        inner.addTab("macros", buildMacros());
        inner.addTab("cookie jar", buildCookieJar());
        add(inner, BorderLayout.CENTER);
        // Refresh when traffic is recorded into a macro via "send to session macro".
        sm.addChangeListener(() -> SwingUtilities.invokeLater(this::refreshAll));
    }

    /** Re-read the manager's state into every table and list. */
    public void refreshAll() {
        macroListModel.refresh();
        itemModel.fireTableDataChanged();
        extractionModel.fireTableDataChanged();
        cookieModel.refresh();
        ruleModel.fireTableDataChanged();
    }

    // ---------------- rules ----------------

    private JComponent buildRules() {
        JTable table = new JTable(ruleModel);
        Ui.styleTable(table);
        Ui.distributeWidths(table, 55, 160, 150, 150, 120, 150);

        JButton add = Ui.primaryButton("add rule", "create a session-handling rule");
        JButton edit = Ui.button("edit", "edit the selected rule");
        JButton del = Ui.button("delete", "delete the selected rule");
        add.addActionListener(e -> { SessionRule r = new SessionRule(); if (editRule(r)) { sm.rules().add(r); ruleModel.fireTableDataChanged(); } });
        edit.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i >= 0 && editRule(sm.rules().get(i))) ruleModel.fireTableDataChanged();
        });
        del.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i >= 0) { sm.rules().remove(i); ruleModel.fireTableDataChanged(); }
        });

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(add); buttons.add(edit); buttons.add(del);
        JPanel bar = withNote(buttons,
                "Rules apply the cookie jar to matching traffic and re-run a macro when a session expires.");

        JPanel root = new JPanel(new BorderLayout());
        root.add(bar, BorderLayout.NORTH);
        root.add(Ui.scroll(table), BorderLayout.CENTER);
        return root;
    }

    private boolean editRule(SessionRule r) {
        JTextField name = Ui.field(r.name, 18);
        JTextField host = Ui.field(r.hostPattern, 18);
        JCheckBox jar = Ui.check("apply the cookie jar to matching requests", r.useCookieJar);
        JComboBox<String> macro = Ui.combo(macroNames());
        macro.setSelectedItem(r.macroName);
        JComboBox<SessionRule.Trigger> trig = Ui.combo(SessionRule.Trigger.values());
        trig.setSelectedItem(r.trigger);
        JTextField trigVal = Ui.field(r.triggerValue, 18);

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        tools.setOpaque(false);
        JCheckBox[] toolBoxes = new JCheckBox[TOOLS.length];
        for (int i = 0; i < TOOLS.length; i++) {
            toolBoxes[i] = Ui.check(TOOLS[i], r.tools.isEmpty() || r.tools.contains(TOOLS[i]));
            tools.add(toolBoxes[i]);
        }

        JPanel form = Ui.form(
                "name", name,
                "host (blank = all, *.example.com ok)", host,
                "tools", tools,
                "cookies", jar,
                "re-auth macro", macro,
                "trigger", trig,
                "trigger value (codes, or text)", trigVal);

        int ok = JOptionPane.showConfirmDialog(ctx.frame(), form, "session rule",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return false;

        r.name = name.getText().trim().isEmpty() ? "rule" : name.getText().trim();
        r.hostPattern = host.getText().trim();
        r.useCookieJar = jar.isSelected();
        r.macroName = macro.getSelectedItem() == null ? "" : macro.getSelectedItem().toString();
        r.trigger = (SessionRule.Trigger) trig.getSelectedItem();
        r.triggerValue = trigVal.getText().trim();
        r.tools.clear();
        boolean all = true;
        for (JCheckBox b : toolBoxes) if (!b.isSelected()) all = false;
        if (!all) for (int i = 0; i < TOOLS.length; i++) if (toolBoxes[i].isSelected()) r.tools.add(TOOLS[i]);
        return true;
    }

    // ---------------- macros ----------------

    private JComponent buildMacros() {
        macroList = new JList<>(macroListModel);
        macroList.setFont(Ui.PLAIN);
        macroList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        JButton newM = Ui.primaryButton("new", "create an empty macro");
        JButton renM = Ui.button("rename");
        JButton delM = Ui.button("delete");
        newM.addActionListener(e -> {
            String n = JOptionPane.showInputDialog(ctx.frame(), "macro name", "login");
            if (n != null && !n.isBlank() && sm.macro(n) == null) {
                sm.macros().add(new Macro(n));
                macroListModel.refresh();
                macroList.setSelectedIndex(sm.macros().size() - 1);
            }
        });
        renM.addActionListener(e -> {
            Macro m = macroList.getSelectedValue();
            if (m == null) return;
            String n = JOptionPane.showInputDialog(ctx.frame(), "new name", m.name);
            if (n != null && !n.isBlank()) { m.name = n; macroListModel.refresh(); }
        });
        delM.addActionListener(e -> {
            Macro m = macroList.getSelectedValue();
            if (m != null) { sm.macros().remove(m); macroListModel.refresh(); refreshMacroDetail(); }
        });
        JPanel leftBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        leftBar.add(newM); leftBar.add(renM); leftBar.add(delM);
        JPanel left = new JPanel(new BorderLayout());
        left.add(leftBar, BorderLayout.NORTH);
        left.add(Ui.scroll(macroList), BorderLayout.CENTER);

        itemTable = new JTable(itemModel);
        Ui.styleTable(itemTable);
        itemTable.getSelectionModel().addListSelectionListener(e -> showSelectedItem());
        itemPreview = new MessageEditor(ctx, MessageEditor.REQUEST, false);

        JButton up = Ui.button("up"); JButton down = Ui.button("down"); JButton rm = Ui.button("remove");
        up.addActionListener(e -> moveItem(-1));
        down.addActionListener(e -> moveItem(1));
        rm.addActionListener(e -> {
            Macro m = macroList.getSelectedValue();
            int i = itemTable.getSelectedRow();
            if (m != null && i >= 0 && i < m.items.size()) { m.items.remove(i); itemModel.fireTableDataChanged(); }
        });
        JButton run = Ui.primaryButton("run now", "replay this macro and capture cookies");
        runStatus = Ui.label("");
        run.addActionListener(e -> runSelectedMacro());

        JPanel itemBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        itemBar.add(up); itemBar.add(down); itemBar.add(rm);
        itemBar.add(new JSeparator(SwingConstants.VERTICAL));
        itemBar.add(run); itemBar.add(runStatus);

        JPanel itemsPanel = new JPanel(new BorderLayout());
        itemsPanel.add(Ui.bold(" requests (send here with “send to session macro”)"), BorderLayout.NORTH);
        JSplitPane itemSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(itemTable), itemPreview);
        itemSplit.setDividerSize(5);
        Ui.balance(itemSplit, 0.4);
        itemsPanel.add(itemSplit, BorderLayout.CENTER);
        itemsPanel.add(itemBar, BorderLayout.SOUTH);

        JComponent extractions = buildExtractions();

        JSplitPane rightSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, itemsPanel, extractions);
        rightSplit.setDividerSize(5);
        Ui.balance(rightSplit, 0.65);

        macroList.addListSelectionListener(e -> refreshMacroDetail());

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightSplit);
        split.setDividerSize(5);
        Ui.balance(split, 0.25);
        macroListModel.refresh();
        return split;
    }

    private JComponent buildExtractions() {
        JTable table = new JTable(extractionModel);
        Ui.styleTable(table);
        JButton add = Ui.button("add"); JButton del = Ui.button("remove");
        add.addActionListener(e -> {
            Macro m = macroList.getSelectedValue();
            if (m == null) return;
            Extraction ex = new Extraction("token", Extraction.From.REGEX, "");
            if (editExtraction(ex)) { m.extractions.add(ex); extractionModel.fireTableDataChanged(); }
        });
        del.addActionListener(e -> {
            Macro m = macroList.getSelectedValue();
            int i = table.getSelectedRow();
            if (m != null && i >= 0 && i < m.extractions.size()) { m.extractions.remove(i); extractionModel.fireTableDataChanged(); }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                Macro m = macroList.getSelectedValue();
                int i = table.getSelectedRow();
                if (e.getClickCount() == 2 && m != null && i >= 0 && i < m.extractions.size()
                        && editExtraction(m.extractions.get(i))) extractionModel.fireTableDataChanged();
            }
        });
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        buttons.add(add); buttons.add(del);
        JPanel bar = withNote(buttons,
                "Values pulled from the macro's responses, for re-use (double-click to edit).");
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(Ui.bold(" extractions"), BorderLayout.NORTH);
        panel.add(Ui.scroll(table), BorderLayout.CENTER);
        panel.add(bar, BorderLayout.SOUTH);
        return panel;
    }

    private boolean editExtraction(Extraction ex) {
        JTextField name = Ui.field(ex.name, 16);
        JComboBox<Extraction.From> from = Ui.combo(Extraction.From.values());
        from.setSelectedItem(ex.from);
        JTextField pattern = Ui.field(ex.pattern, 22);
        JPanel form = Ui.form("name", name, "source", from, "pattern / name / regex", pattern);
        int ok = JOptionPane.showConfirmDialog(ctx.frame(), form, "extraction",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return false;
        ex.name = name.getText().trim();
        ex.from = (Extraction.From) from.getSelectedItem();
        ex.pattern = pattern.getText();
        return true;
    }

    private void moveItem(int dir) {
        Macro m = macroList.getSelectedValue();
        int i = itemTable.getSelectedRow();
        if (m == null || i < 0) return;
        int j = i + dir;
        if (j < 0 || j >= m.items.size()) return;
        Macro.Item tmp = m.items.get(i);
        m.items.set(i, m.items.get(j));
        m.items.set(j, tmp);
        itemModel.fireTableDataChanged();
        itemTable.getSelectionModel().setSelectionInterval(j, j);
    }

    private void showSelectedItem() {
        Macro m = macroList == null ? null : macroList.getSelectedValue();
        int i = itemTable.getSelectedRow();
        if (itemPreview == null) return;
        if (m == null || i < 0 || i >= m.items.size()) {
            itemPreview.setMessage(new HttpService("", 0, false), new HttpMessage(new byte[0]));
            return;
        }
        Macro.Item it = m.items.get(i);
        itemPreview.setMessage(it.service(), it.request());
    }

    private void refreshMacroDetail() {
        itemModel.fireTableDataChanged();
        extractionModel.fireTableDataChanged();
        showSelectedItem();
    }

    private void runSelectedMacro() {
        Macro m = macroList.getSelectedValue();
        if (m == null) { runStatus.setText("select a macro first"); return; }
        runStatus.setText("running...");
        final Macro macro = m;
        Thread t = new Thread(() -> {
            try {
                Map<String, String> vals = sm.runMacro(macro);
                SwingUtilities.invokeLater(() -> {
                    runStatus.setText(vals.isEmpty() ? "done (no extractions)" : "done, extracted " + vals);
                    cookieModel.refresh();
                });
            } catch (Exception e) {
                Debug.error("macro '" + macro.name + "' failed", e);
                SwingUtilities.invokeLater(() -> runStatus.setText("failed: " + e.getMessage()));
            }
        }, "session-macro-run");
        t.setDaemon(true);
        t.start();
    }

    private String[] macroNames() {
        List<Macro> ms = sm.macros();
        String[] out = new String[ms.size() + 1];
        out[0] = "";
        for (int i = 0; i < ms.size(); i++) out[i + 1] = ms.get(i).name;
        return out;
    }

    // ---------------- cookie jar ----------------

    private JComponent buildCookieJar() {
        JTable table = new JTable(cookieModel);
        Ui.styleTable(table);
        Ui.distributeWidths(table, 160, 120, 220, 90, 60);

        JButton add = Ui.primaryButton("add cookie", "add a cookie to the jar");
        JButton del = Ui.button("delete");
        JButton clear = Ui.button("clear all");
        JButton refresh = Ui.button("refresh");
        add.addActionListener(e -> {
            Cookie c = new Cookie("", "", "", "/", false, 0);
            if (editCookie(c) && !c.name.isEmpty()) { sm.jar().set(c); cookieModel.refresh(); }
        });
        del.addActionListener(e -> {
            int i = table.getSelectedRow();
            List<Cookie> all = sm.jar().all();
            if (i >= 0 && i < all.size()) { sm.jar().remove(all.get(i)); cookieModel.refresh(); }
        });
        clear.addActionListener(e -> { sm.jar().clear(); cookieModel.refresh(); });
        refresh.addActionListener(e -> cookieModel.refresh());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        buttons.add(add); buttons.add(del); buttons.add(clear); buttons.add(refresh);
        JPanel bar = withNote(buttons,
                "Cookies seen by any tool land here and are offered back to matching requests.");

        JPanel root = new JPanel(new BorderLayout());
        root.add(bar, BorderLayout.NORTH);
        root.add(Ui.scroll(table), BorderLayout.CENTER);
        cookieModel.refresh();
        return root;
    }

    private boolean editCookie(Cookie c) {
        JTextField name = Ui.field(c.name, 16);
        JTextField value = Ui.field(c.value, 22);
        JTextField domain = Ui.field(c.domain, 16);
        JTextField path = Ui.field(c.path, 10);
        JCheckBox secure = Ui.check("secure (https only)", c.secure);
        JPanel form = Ui.form("name", name, "value", value, "domain", domain, "path", path, "secure", secure);
        int ok = JOptionPane.showConfirmDialog(ctx.frame(), form, "cookie",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return false;
        c.name = name.getText().trim();
        c.value = value.getText();
        c.domain = domain.getText().trim();
        c.path = path.getText().trim().isEmpty() ? "/" : path.getText().trim();
        c.secure = secure.isSelected();
        return true;
    }

    // ---------------- table models ----------------

    /**
     * A button row with its explanatory note on the line beneath, so the note
     * wraps to the width available instead of being cut off at the row's end.
     */
    private static JPanel withNote(JPanel buttons, String note) {
        JTextArea n = Ui.note(note);
        n.setBorder(Ui.pad(0, 8, 6, 8));
        JPanel bar = new JPanel(new BorderLayout());
        bar.add(buttons, BorderLayout.NORTH);
        bar.add(n, BorderLayout.SOUTH);
        return bar;
    }

    private final class RuleModel extends AbstractTableModel {
        private final String[] cols = {"enabled", "name", "host", "tools", "macro", "trigger"};
        @Override public int getRowCount() { return sm == null ? 0 : sm.rules().size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c == 0) { sm.rules().get(r).enabled = Boolean.TRUE.equals(v); fireTableRowsUpdated(r, r); }
        }
        @Override public Object getValueAt(int r, int c) {
            SessionRule rule = sm.rules().get(r);
            return switch (c) {
                case 0 -> rule.enabled;
                case 1 -> rule.name;
                case 2 -> rule.hostPattern.isEmpty() ? "(all)" : rule.hostPattern;
                case 3 -> rule.tools.isEmpty() ? "(all)" : String.join(",", rule.tools);
                case 4 -> rule.macroName;
                default -> rule.trigger == SessionRule.Trigger.NONE ? "-" : rule.trigger + " " + rule.triggerValue;
            };
        }
    }

    private final class MacroListModel extends AbstractListModel<Macro> {
        @Override public int getSize() { return sm == null ? 0 : sm.macros().size(); }
        @Override public Macro getElementAt(int i) { return sm.macros().get(i); }
        void refresh() { fireContentsChanged(this, 0, Math.max(0, getSize())); }
    }

    private final class ItemModel extends AbstractTableModel {
        private final String[] cols = {"#", "method", "url"};
        private Macro current() { return macroList == null ? null : macroList.getSelectedValue(); }
        @Override public int getRowCount() { Macro m = current(); return m == null ? 0 : m.items.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Macro.Item it = current().items.get(r);
            HttpMessage req = it.request();
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> req.method();
                default -> (it.secure ? "https://" : "http://") + it.host + req.path();
            };
        }
    }

    private final class ExtractionModel extends AbstractTableModel {
        private final String[] cols = {"name", "source", "pattern"};
        private Macro current() { return macroList == null ? null : macroList.getSelectedValue(); }
        @Override public int getRowCount() { Macro m = current(); return m == null ? 0 : m.extractions.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Extraction ex = current().extractions.get(r);
            return switch (c) { case 0 -> ex.name; case 1 -> ex.from; default -> ex.pattern; };
        }
    }

    private final class CookieModel extends AbstractTableModel {
        private final String[] cols = {"domain", "name", "value", "path", "secure"};
        private List<Cookie> rows = java.util.List.of();
        void refresh() { rows = sm.jar().all(); fireTableDataChanged(); }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 4 ? Boolean.class : String.class; }
        @Override public Object getValueAt(int r, int c) {
            Cookie k = rows.get(r);
            return switch (c) { case 0 -> k.domain; case 1 -> k.name; case 2 -> k.value; case 3 -> k.path; default -> k.secure; };
        }
    }
}
