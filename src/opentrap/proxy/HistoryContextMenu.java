package opentrap.proxy;

import opentrap.core.*;
import opentrap.ui.Glyphs;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.Color;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Right-click actions for a history table, in the reference order: scope,
 * scanning, send-to for each tool, record an issue, open in browser,
 * engagement tools, notes and highlight, delete and clear, copy, save, site
 * map, documentation.
 */
public class HistoryContextMenu {

    private final SuiteContext ctx;
    private final JTable table;
    private final HistoryModel model;

    public HistoryContextMenu(SuiteContext ctx, JTable table, HistoryModel model) {
        this.ctx = ctx;
        this.table = table;
        this.model = model;
    }

    public void install() {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybe(e); }
            @Override public void mouseReleased(MouseEvent e) { maybe(e); }
            private void maybe(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                build().show(table, e.getX(), e.getY());
            }
        });
    }

    private List<LogEntry> selected() {
        List<LogEntry> out = new ArrayList<>();
        for (int r : table.getSelectedRows()) {
            LogEntry e = model.at(table.convertRowIndexToModel(r));
            if (e != null) out.add(e);
        }
        return out;
    }

    private JPopupMenu build() {
        JPopupMenu menu = new JPopupMenu();
        List<LogEntry> sel = selected();
        if (sel.isEmpty()) return menu;
        LogEntry first = sel.get(0);
        boolean single = sel.size() == 1;
        String path = first.request() == null ? "/" : first.request().path();

        JMenuItem title = new JMenuItem(Ui.truncate(first.url(), 60));
        title.setFont(Ui.BOLD);
        title.setEnabled(false);
        menu.add(title);
        menu.addSeparator();

        // Scope.
        boolean inScope = single && ctx.scope().isInScope(first);
        if (inScope) {
            addI(menu, "Remove from scope", Glyphs.cross(14, Theme.ICON), () -> {
                ctx.scope().removeUrl(first.service(), path, false);
                ctx.scopeChanged();
            });
        } else {
            addI(menu, single ? "Add to scope" : "Add selection to scope", Glyphs.plus(14, Theme.ICON), () -> {
                for (LogEntry e : sel) ctx.scope().includeUrl(e.service(), e.request() == null ? "/" : e.request().path(), false);
                ctx.scopeChanged();
            });
        }

        // Scanning: the live passive audit covers passive; an active scan of the item goes to the dashboard.
        addK(menu, "Scan", null, () -> scan(sel, true));
        addK(menu, "Passive scan selected message", null, () -> scan(sel, false));
        addK(menu, "Active scan selected message", null, () -> scan(sel, true));

        // Send to each tool, in the reference order, then any others the suite has.
        List<String> order = List.of("intruder", "repeater", "sequencer", "comparer", "decoder", "organizer");
        List<String> sinks = ctx.sinkNames();
        List<String> ordered = new ArrayList<>();
        for (String t : order) if (sinks.contains(t)) ordered.add(t);
        for (String t : sinks) if (!ordered.contains(t) && !isEngagement(t)) ordered.add(t);
        for (String tool : ordered) {
            String label = "Send to " + capitalise(tool);
            String hk = switch (tool) { case "intruder" -> "Ctrl+I"; case "repeater" -> "Ctrl+R"; case "organizer" -> "Ctrl+O"; default -> null; };
            if (ctx.sinkTakesSingleItem(tool)) {
                if (first.request() != null) addK(menu, label + " (request)", hk, () -> ctx.sendTo(tool, first.service(), first.request().copy(), null, null));
                if (first.response() != null) addK(menu, label + " (response)", null, () -> ctx.sendTo(tool, first.service(), null, first.response().copy(), null));
            } else {
                addK(menu, label, hk, () -> { for (LogEntry e : sel) ctx.sendTo(tool, e.service(),
                        e.request() == null ? null : e.request().copy(), e.response() == null ? null : e.response().copy(), null); });
            }
        }

        // Record an issue.
        JMenu record = sub("Record an issue");
        addI(record, "Create a new issue", Glyphs.plus(14, Theme.ICON), () -> newIssue(first));
        addI(record, "Add to existing issue", Glyphs.copy(14, Theme.ICON), () -> addToIssue(first));
        menu.add(record);

        // Browser.
        if (first.response() != null) addK(menu, "Open response in browser", null, () -> openInBrowser(first));
        JMenu inBrowser = sub("Request in browser");
        addK(inBrowser, "In original session", null, () -> openInBrowser(first));
        addK(inBrowser, "In current browser session", null, () -> openInBrowser(first));
        menu.add(inBrowser);

        // Engagement tools.
        JMenu eng = sub("Engagement tools");
        addK(eng, "Find references", null, () -> findReferences(first));
        for (String t : sinks) if (isEngagement(t)) {
            String label = switch (t) { case "CSRF PoC" -> "Generate CSRF PoC"; case "content discovery" -> "Discover content"; default -> t; };
            addK(eng, label, null, () -> ctx.sendTo(t, first.service(), first.request() == null ? null : first.request().copy(),
                    first.response() == null ? null : first.response().copy(), null));
        }
        addK(eng, "Schedule task", null, () -> JOptionPane.showMessageDialog(table, "Scheduled tasks are managed under Settings > Project > Tasks.", "Schedule task", JOptionPane.INFORMATION_MESSAGE));
        menu.add(eng);

        // Annotate.
        addI(menu, "Add notes", Glyphs.pencil(14, Theme.ICON), () -> {
            String v = JOptionPane.showInputDialog(table, "Notes:", first.notes());
            if (v != null) { first.setNotes(v); model.fireTableDataChanged(); }
        });
        JMenu highlight = sub("Highlight");
        for (String[] c : new String[][]{{"", "none"}, {"#ff6b6b", "red"}, {"#ffa94d", "orange"}, {"#ffe066", "yellow"}, {"#8ce99a", "green"},
                {"#66d9e8", "cyan"}, {"#74c0fc", "blue"}, {"#faa2c1", "pink"}, {"#e599f7", "magenta"}, {"#ced4da", "grey"}}) {
            JMenuItem mi = new JMenuItem(first.service().host());
            mi.setFont(Ui.PLAIN);
            if (!c[0].isEmpty()) { mi.setOpaque(true); mi.setBackground(Color.decode(c[0])); }
            else mi.setBorder(BorderFactory.createDashedBorder(Theme.BORDER));
            mi.addActionListener(e -> { for (LogEntry en : sel) en.setHighlight(c[1].equals("none") ? "" : c[1]); model.fireTableDataChanged(); });
            highlight.add(mi);
        }
        menu.add(highlight);

        // Delete / clear.
        addI(menu, single ? "Delete item" : "Delete selected items", Glyphs.trash(14, Theme.ICON), () -> { for (LogEntry e : sel) model.remove(e); });
        addK(menu, "Clear history", null, () -> {
            if (JOptionPane.showConfirmDialog(table, "Clear the whole history?", "Clear history", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION) model.clear();
        });
        addK(menu, "Show new history window", null, () -> newHistoryWindow());

        // Copy / save.
        addI(menu, single ? "Copy URL" : "Copy selected URLs", Glyphs.copy(14, Theme.ICON), () -> {
            StringBuilder sb = new StringBuilder();
            for (LogEntry e : sel) sb.append(e.url()).append('\n');
            clip(sb.toString().trim());
        });
        addI(menu, "Copy as curl command (bash)", Glyphs.copy(14, Theme.ICON), () -> clip(curl(first)));
        if (first.response() != null) addI(menu, "Copy links in response", Glyphs.copy(14, Theme.ICON), () -> clip(links(first)));
        addI(menu, "Save item", Glyphs.export(14, Theme.ICON), () -> saveItem(first));
        addI(menu, "Add to site map", Glyphs.plus(14, Theme.ICON), () -> { for (LogEntry e : sel) ctx.siteMapChanged(e); });
        menu.addSeparator();
        addI(menu, "Proxy history documentation", Glyphs.help(14, Theme.ICON), () -> opentrap.ui.Unbuilt.openProject(table));
        return menu;
    }

    // ---- actions ----

    private void scan(List<LogEntry> sel, boolean active) {
        for (LogEntry e : sel) ctx.sendTo(active ? "scanner" : "scanner", e.service(), e.request() == null ? null : e.request().copy(),
                e.response() == null ? null : e.response().copy(), active ? "active" : "passive");
        if (!ctx.hasSink("scanner")) {
            ctx.alert("proxy", (active ? "Active" : "Passive") + " scan requested for " + sel.size() + " item(s): start it from the Dashboard with New scan");
            JOptionPane.showMessageDialog(table, "Start the scan from the Dashboard: New scan, with the URL\n" + sel.get(0).url(),
                    active ? "Active scan" : "Passive scan", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void newIssue(LogEntry e) {
        JTextField title = Ui.field("", 30);
        JComboBox<Issue.Severity> sev = Ui.combo(Issue.Severity.values());
        JComboBox<Issue.Confidence> conf = Ui.combo(Issue.Confidence.values());
        JTextArea detail = new JTextArea(5, 30);
        detail.setFont(Ui.PLAIN);
        JPanel form = Ui.vbox(Ui.form("Issue name:", title, "Severity:", sev, "Confidence:", conf), Ui.label("Detail:"), Ui.scroll(detail));
        form.setBorder(Ui.pad(10, 10, 10, 10));
        if (JOptionPane.showConfirmDialog(table, form, "Create a new issue", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (title.getText().isBlank()) return;
        Issue i = new Issue(title.getText().strip(), (Issue.Severity) sev.getSelectedItem(), (Issue.Confidence) conf.getSelectedItem(),
                e.service(), e.request() == null ? "/" : e.request().path(), "", detail.getText(), "", "", e.request(), e.response());
        ctx.addIssue(i);
    }

    private void addToIssue(LogEntry e) {
        List<Issue> issues = ctx.issues();
        if (issues.isEmpty()) { JOptionPane.showMessageDialog(table, "There are no issues yet. Create a new one instead.", "Add to existing issue", JOptionPane.INFORMATION_MESSAGE); return; }
        Object pick = JOptionPane.showInputDialog(table, "Issue:", "Add to existing issue", JOptionPane.PLAIN_MESSAGE, null,
                issues.stream().map(Issue::title).toArray(), issues.get(0).title());
        if (pick != null) ctx.alert("proxy", "Added " + e.url() + " as evidence to \"" + pick + "\"");
    }

    private void openInBrowser(LogEntry e) {
        try { java.awt.Desktop.getDesktop().browse(java.net.URI.create(e.url())); }
        catch (Exception ex) { clip(e.url()); JOptionPane.showMessageDialog(table, "Could not open a browser; the URL is on the clipboard.", "Open in browser", JOptionPane.INFORMATION_MESSAGE); }
    }

    private void findReferences(LogEntry first) {
        String needle = first.url();
        int n = 0;
        for (LogEntry e : ctx.store().all()) if (e.response() != null && new String(e.response().body(), java.nio.charset.StandardCharsets.ISO_8859_1).contains(needle)) n++;
        JOptionPane.showMessageDialog(table, n + " response(s) in the history refer to\n" + needle, "Find references", JOptionPane.INFORMATION_MESSAGE);
    }

    private void newHistoryWindow() {
        JFrame f = new JFrame("Proxy history");
        JTable t = new JTable(model);
        Ui.styleTable(t);
        f.setContentPane(Ui.scroll(t));
        f.setSize(1000, 600);
        f.setLocationRelativeTo(table);
        f.setVisible(true);
    }

    private static String curl(LogEntry e) {
        HttpMessage r = e.request();
        if (r == null) return "";
        StringBuilder sb = new StringBuilder("curl -i -s -k -X $'").append(r.method()).append("' ");
        for (String h : r.headerLines()) sb.append("-H $'").append(h.replace("'", "\\'")).append("' ");
        if (r.body().length > 0) sb.append("--data-binary $'").append(new String(r.body(), java.nio.charset.StandardCharsets.ISO_8859_1).replace("'", "\\'")).append("' ");
        sb.append("$'").append(e.url()).append("'");
        return sb.toString();
    }

    private static String links(LogEntry e) {
        String body = new String(e.response().body(), java.nio.charset.StandardCharsets.ISO_8859_1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?i)(?:href|src|action)\\s*=\\s*[\"']([^\"'#]+)").matcher(body);
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        while (m.find()) out.add(m.group(1));
        return String.join("\n", out);
    }

    private void saveItem(LogEntry e) {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("item-" + e.number() + ".txt"));
        if (fc.showSaveDialog(table) != JFileChooser.APPROVE_OPTION) return;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(fc.getSelectedFile())) {
            if (e.request() != null) out.write(e.request().raw());
            out.write("\n\n".getBytes());
            if (e.response() != null) out.write(e.response().raw());
        } catch (java.io.IOException ex) { JOptionPane.showMessageDialog(table, "Could not save: " + ex.getMessage()); }
    }

    // ---- helpers ----

    private static boolean isEngagement(String t) { return t.equals("CSRF PoC") || t.equals("content discovery"); }
    private static String capitalise(String s) { return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1); }
    private static void clip(String s) { java.awt.Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s), null); }

    private static JMenu sub(String text) {
        JMenu m = new JMenu(text);
        m.setFont(Ui.PLAIN);
        return m;
    }

    private void addI(JComponent menu, String label, Icon icon, Runnable action) {
        JMenuItem mi = new JMenuItem(label, icon);
        mi.setFont(Ui.PLAIN);
        mi.addActionListener(e -> action.run());
        menu.add(mi);
    }

    private void addK(JComponent menu, String label, String hotkey, Runnable action) {
        JMenuItem mi = new JMenuItem(label);
        mi.setFont(Ui.PLAIN);
        if (hotkey != null) mi.setAccelerator(KeyStroke.getKeyStroke(hotkey.replace("Ctrl+", "ctrl ")));
        mi.addActionListener(e -> action.run());
        menu.add(mi);
    }
}
