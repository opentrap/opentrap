package opentrap.target;

import opentrap.core.*;
import opentrap.ui.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/** The target tool: a site map tree and the suite-wide scope editor. */
public class TargetTool implements ToolModule {

    private SuiteContext ctx;
    private SiteMap siteMap = new SiteMap();
    private JTree tree;
    private DefaultTreeModel treeModel;
    private final java.util.Map<SiteMap.Node, DefaultMutableTreeNode> treeNodes = new java.util.IdentityHashMap<>();
    private ItemsModel itemsModel;
    private JTable itemsTable;
    private final opentrap.proxy.HistoryFilter itemsFilter = new opentrap.proxy.HistoryFilter();
    private JButton itemsFilterButton;
    private MessageEditor reqView, respView;
    private JTree issuesTree;
    private DefaultTreeModel issuesTreeModel;
    private javax.swing.JEditorPane issueAdvisory;
    private MessageEditor issueReq, issueResp;
    private SiteMap.Node selectedNode;
    private java.util.List<SiteMap.Node> selectedNodes = new java.util.ArrayList<>();

    public TargetTool() {}

    @Override public String name() { return "target"; }
    @Override public boolean alwaysTabbed() { return true; }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        ctx.addSiteMapListener(e -> SwingUtilities.invokeLater(() -> {
            siteMap.add(e);
            syncTree();
        }));
        ctx.addIssueListener(i -> SwingUtilities.invokeLater(this::refreshIssues));
    }

    @Override public Tab[] tabs() {
        return new Tab[]{
                new Tab("Site map", buildSiteMap()),
                new Tab("Scope", buildScope()),
                new Tab("Issues", new IssueDefinitionsPanel()),
        };
    }

    private JComponent buildSiteMap() {
        DefaultMutableTreeNode rootTn = new DefaultMutableTreeNode(siteMap.root());
        treeNodes.clear();
        treeNodes.put(siteMap.root(), rootTn);
        treeModel = new DefaultTreeModel(rootTn);
        tree = new JTree(treeModel);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Ui.PLAIN);
        tree.setRowHeight(18);

        itemsModel = new ItemsModel();
        itemsTable = new JTable(itemsModel);
        Ui.styleTable(itemsTable);
        // host, method, URL, status, length, MIME type, title
        Ui.distributeWidths(itemsTable, 180, 55, 300, 60, 70, 90, 200);
        Ui.centerCells(itemsTable);

        reqView = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        respView = new MessageEditor(ctx, MessageEditor.RESPONSE, false);

        tree.addTreeSelectionListener(e -> showTreeSelection());
        itemsTable.getSelectionModel().addListSelectionListener(e -> showItem());
        installTreeMenu();

        itemsFilter.scope = ctx.scope();
        JComponent contents = new JPanel(new BorderLayout());
        contents.add(buildItemsFilterBar(), BorderLayout.NORTH);
        contents.add(Ui.scroll(itemsTable), BorderLayout.CENTER);

        JSplitPane detail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqView, respView);
        detail.setDividerSize(5);
        Ui.balance(detail, 0.5);
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, contents, detail);
        center.setDividerSize(5);
        Ui.balance(center, 0.45);

        JComponent issuesPanel = buildIssuesPanel();

        JSplitPane rightmost = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, issuesPanel);
        rightmost.setDividerSize(5);
        Ui.balance(rightmost, 0.6);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(tree), rightmost);
        split.setDividerSize(5);
        Ui.balance(split, 0.22);
        return split;
    }

    private JComponent buildIssuesPanel() {
        DefaultMutableTreeNode iroot = new DefaultMutableTreeNode("Issues");
        issuesTreeModel = new DefaultTreeModel(iroot);
        issuesTree = new JTree(issuesTreeModel);
        issuesTree.setRootVisible(false);
        issuesTree.setShowsRootHandles(true);
        issuesTree.setFont(Ui.PLAIN);
        issuesTree.setRowHeight(18);
        // Colour each node's icon by severity (red High, purple Medium, blue Low,
        // grey Info), matching the dashboard badges, so severity reads at a glance.
        issuesTree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object value,
                    boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus);
                Issue.Severity sev = null;
                if (value instanceof DefaultMutableTreeNode n) {
                    Object o = n.getUserObject();
                    if (o instanceof IssueType it) sev = it.severity();
                    else if (o instanceof Issue i) sev = i.severity();
                }
                setIcon(sev == null ? null : Ui.severityDot(sev));
                return this;
            }
        });

        issueAdvisory = new javax.swing.JEditorPane();
        issueAdvisory.setEditable(false);
        issueAdvisory.setContentType("text/html");
        issueReq = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        issueResp = new MessageEditor(ctx, MessageEditor.RESPONSE, false);

        issuesTree.addTreeSelectionListener(e -> showIssue());

        JLabel header = Ui.bold(" Issues");
        header.setBorder(Ui.pad(3, 2, 3, 2));
        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(Ui.scroll(issuesTree), BorderLayout.CENTER);

        // One tabbed detail pane: Advisory, Request, Response.
        JTabbedPane detailTabs = new JTabbedPane();
        detailTabs.setFont(Ui.PLAIN);
        detailTabs.addTab("advisory", Ui.scroll(issueAdvisory));
        detailTabs.addTab("request", issueReq);
        detailTabs.addTab("response", issueResp);

        JSplitPane panel = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, detailTabs);
        panel.setDividerSize(5);
        Ui.balance(panel, 0.4);
        return panel;
    }

    /**
     * Issues for the selected node's host/path, grouped by type into a tree.
     * Each type expands to its distinct locations (path + parameter); the same
     * location never appears twice, matching how the site map dedups.
     */
    private void refreshIssues() {
        if (issuesTreeModel == null) return;
        // type -> (locationKey -> issue), insertion-ordered and deduped by location.
        java.util.Map<String, java.util.LinkedHashMap<String, Issue>> grouped = new java.util.LinkedHashMap<>();
        // Which issues to show: everything when the root (a node with no service)
        // is selected or nothing is selected, otherwise the union of issues under
        // all the selected host/path nodes. Selecting all sites shows all issues.
        boolean showAll = selectedNodes == null || selectedNodes.isEmpty();
        if (!showAll) {
            for (SiteMap.Node n : selectedNodes) {
                if (n.service == null) { showAll = true; break; }
            }
        }
        java.util.List<Issue> list = new java.util.ArrayList<>();
        if (showAll) {
            list.addAll(ctx.issues());
        } else {
            for (Issue i : ctx.issues()) {
                for (SiteMap.Node n : selectedNodes) {
                    if (n.service == null) continue;
                    if (!i.host().equalsIgnoreCase(n.service.host())) continue;
                    String prefix = n.isHost ? "" : n.path;
                    if (!prefix.isEmpty() && !i.path().startsWith(prefix)) continue;
                    list.add(i);
                    break;
                }
            }
        }
        list.sort(java.util.Comparator.comparingInt(Issue::rank));
        for (Issue i : list) {
            String locKey = i.host() + "\u0000" + i.path() + "\u0000"
                    + (i.param() == null ? "" : i.param());
            grouped.computeIfAbsent(i.title(), k -> new java.util.LinkedHashMap<>())
                   .putIfAbsent(locKey, i);
        }
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) issuesTreeModel.getRoot();
        root.removeAllChildren();
        for (var typeEntry : grouped.entrySet()) {
            java.util.Collection<Issue> locs = typeEntry.getValue().values();
            // The group's colour is its most severe location; because the list is
            // rank-sorted the first location is the most severe.
            Issue.Severity sev = locs.isEmpty()
                    ? Issue.Severity.INFORMATION : locs.iterator().next().severity();
            DefaultMutableTreeNode typeNode = new DefaultMutableTreeNode(
                    new IssueType(typeEntry.getKey(), locs.size(), sev));
            for (Issue i : locs) typeNode.add(new DefaultMutableTreeNode(i));
            root.add(typeNode);
        }
        // Remember what was selected so a newly arriving issue does not drag the
        // advisory away from whatever is being read.
        String previouslySelected = selectionKey(issuesTree.getSelectionPath());

        issuesTreeModel.reload();

        if (root.getChildCount() > 0) {
            TreePath restore = findByKey(root, previouslySelected);
            if (restore != null) {
                issuesTree.setSelectionPath(restore);
                issuesTree.scrollPathToVisible(restore);
            } else {
                // Select the first issue type so the advisory populates, but leave
                // it closed. The type node already shows everything the advisory
                // needs, so expanding it only to select a child is noise; the
                // locations are there when the tester wants them.
                DefaultMutableTreeNode type0 = (DefaultMutableTreeNode) root.getChildAt(0);
                issuesTree.setSelectionPath(new TreePath(type0.getPath()));
            }
        } else {
            issueAdvisory.setText("");
            issueReq.setMessage(null, new HttpMessage(new byte[0]));
            issueResp.setMessage(null, new HttpMessage(new byte[0]));
        }
    }

    /** Issue-type grouping node: title, a count of distinct locations, severity. */
    private record IssueType(String title, int count, Issue.Severity severity) {
        @Override public String toString() { return title + "  [" + count + "]"; }
    }

    /**
     * The issue a selected node should display.
     *
     * <p>Selecting a leaf shows that location. Selecting the type node shows its
     * first location rather than nothing, so clicking a finding's title is
     * enough to read it. Expanding to pick a specific location stays available
     * but is no longer required to see anything.
     */
    private static Issue issueFor(DefaultMutableTreeNode node) {
        if (node == null) return null;
        if (node.getUserObject() instanceof Issue i) return i;
        if (node.getUserObject() instanceof IssueType && node.getChildCount() > 0) {
            DefaultMutableTreeNode first = (DefaultMutableTreeNode) node.getChildAt(0);
            if (first.getUserObject() instanceof Issue i) return i;
        }
        return null;
    }

    /**
     * A stable identifier for a selected node, used to hold the selection
     * steady while the tree is rebuilt.
     */
    private static String selectionKey(TreePath path) {
        if (path == null) return null;
        Object last = path.getLastPathComponent();
        if (!(last instanceof DefaultMutableTreeNode tn)) return null;
        Object o = tn.getUserObject();
        if (o instanceof Issue i) return "issue " + i.dedupeKey();
        if (o instanceof IssueType t) return "type " + t.title();
        return null;
    }

    /** Find the rebuilt node matching a remembered selection, or null. */
    private static TreePath findByKey(DefaultMutableTreeNode root, String key) {
        if (key == null) return null;
        for (int t = 0; t < root.getChildCount(); t++) {
            DefaultMutableTreeNode typeNode = (DefaultMutableTreeNode) root.getChildAt(t);
            if (key.equals(selectionKey(new TreePath(typeNode.getPath())))) {
                return new TreePath(typeNode.getPath());
            }
            for (int c = 0; c < typeNode.getChildCount(); c++) {
                DefaultMutableTreeNode leaf = (DefaultMutableTreeNode) typeNode.getChildAt(c);
                if (key.equals(selectionKey(new TreePath(leaf.getPath())))) {
                    return new TreePath(leaf.getPath());
                }
            }
        }
        return null;
    }

    private void showIssue() {
        TreePath path = issuesTree.getSelectionPath();
        if (path == null) return;
        DefaultMutableTreeNode tn = (DefaultMutableTreeNode) path.getLastPathComponent();
        Issue i = issueFor(tn);
        if (i == null) return;
        issueAdvisory.setText(opentrap.ui.Advisory.render(i));
        issueAdvisory.setCaretPosition(0);
        issueReq.setMessage(i.service(),
                i.request() == null ? new HttpMessage(new byte[0]) : i.request().copy());
        issueResp.setMessage(i.service(),
                i.response() == null ? new HttpMessage(new byte[0]) : i.response().copy(), i.request());
        // Auto-highlight the evidence (the matched text) in the request and
        // response, the way Burp does, so the relevant part is obvious.
        String ev = i.evidence();
        issueReq.highlight(ev);
        issueResp.highlight(ev);
    }




    private JComponent buildScope() {
        OptionsPage page = new OptionsPage();
        JTextArea note = Ui.note(
            "Define the in-scope targets for your current work. This configuration affects the "
            + "behaviour of tools throughout the suite. All fields take regex strings. The easiest "
            + "way to configure scope is to browse to your target and use the context menus in the "
            + "site map to include or exclude URL paths.");
        note.setPreferredSize(new Dimension(600, 80));
        page.section("target scope", note, new ScopeEditor(ctx, ctx.scope()));
        return page;
    }

    /** Insert only newly-discovered nodes, preserving expansion and selection. */
    private void syncTree() {
        if (treeModel == null || tree == null) return;
        sync(siteMap.root());
        // With an invisible root, its children (hosts) only show once the root
        // path is expanded; expand it whenever new hosts appear.
        javax.swing.tree.TreeNode rootTn = (javax.swing.tree.TreeNode) treeModel.getRoot();
        tree.expandPath(new TreePath(rootTn));
        // Auto-select the first host so the panes populate when the tab is first viewed.
        if (tree.getSelectionPath() == null && tree.getRowCount() > 0) {
            tree.setSelectionRow(0);
        }
        refreshSelectedItems();
    }

    private void sync(SiteMap.Node sm) {
        DefaultMutableTreeNode tn = treeNodes.get(sm);
        if (tn == null) return;
        for (SiteMap.Node child : sm.children.values()) {
            DefaultMutableTreeNode childTn = treeNodes.get(child);
            if (childTn == null) {
                childTn = new DefaultMutableTreeNode(child);
                treeNodes.put(child, childTn);
                treeModel.insertNodeInto(childTn, tn, tn.getChildCount());
            }
            sync(child);
        }
    }

    /**
     * The site-map Contents filter bar: a clickable summary that opens the full
     * filter and full-text search, plus a quick URL search, matching the proxy
     * history.
     */
    private JComponent buildItemsFilterBar() {
        itemsFilterButton = Ui.filterButton();
        itemsFilterButton.setText("Filter:  " + itemsFilter.summary());
        itemsFilterButton.addActionListener(e -> {
            java.awt.Window owner = SwingUtilities.getWindowAncestor(itemsTable);
            new opentrap.proxy.FilterDialog(owner, "site map filter",
                    itemsFilter, TargetTool.this::applyItemsFilter).setVisible(true);
        });
        JTextField urlSearch = Ui.field("", 22);
        urlSearch.setToolTipText("quick search of the URL only");
        urlSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void go() { itemsFilter.urlSearch = urlSearch.getText(); applyItemsFilter(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        return Ui.filterBar(itemsFilterButton, Ui.label("URL search:"), urlSearch);
    }

    private void applyItemsFilter() {
        if (itemsModel == null) return;
        itemsModel.setFilter(itemsFilter::test);
        if (itemsFilterButton != null) itemsFilterButton.setText("Filter:  " + itemsFilter.summary());
    }

    /** Refresh the items table for the selected node without stealing selection. */
    private void refreshSelectedItems() {
        if (tree == null || itemsModel == null) return;
        if (selectedNodes == null || selectedNodes.isEmpty()) return;
        int sel = itemsTable.getSelectedRow();
        itemsModel.setEntries(unionEntries(selectedNodes));
        if (sel >= 0 && sel < itemsModel.getRowCount()) {
            itemsTable.setRowSelectionInterval(sel, sel);
        }
    }

    private void showTreeSelection() {
        TreePath[] paths = tree.getSelectionPaths();
        selectedNodes = new java.util.ArrayList<>();
        if (paths != null) {
            for (TreePath p : paths) {
                DefaultMutableTreeNode tn = (DefaultMutableTreeNode) p.getLastPathComponent();
                if (tn.getUserObject() instanceof SiteMap.Node node) selectedNodes.add(node);
            }
        }
        selectedNode = selectedNodes.isEmpty() ? null : selectedNodes.get(selectedNodes.size() - 1);
        itemsModel.setEntries(unionEntries(selectedNodes));
        if (itemsModel.getRowCount() > 0) itemsTable.setRowSelectionInterval(0, 0);
        refreshIssues();
    }

    /** Deduplicated union of the request entries under all the given nodes. */
    private java.util.List<LogEntry> unionEntries(java.util.List<SiteMap.Node> nodes) {
        java.util.LinkedHashMap<String, LogEntry> union = new java.util.LinkedHashMap<>();
        for (SiteMap.Node n : nodes) {
            for (LogEntry e : n.entries()) {
                if (e.service() == null || e.request() == null) continue;
                String k = e.service().url() + "|" + e.request().method() + " " + e.pathAndQuery();
                union.putIfAbsent(k, e);
            }
        }
        return new java.util.ArrayList<>(union.values());
    }

    private void showItem() {
        int row = itemsTable.getSelectedRow();
        LogEntry e = itemsModel.at(row);
        if (e == null) return;
        reqView.setMessage(e.service(),
                e.request() == null ? new HttpMessage(new byte[0]) : e.request().copy());
        respView.setMessage(e.service(),
                e.response() == null ? new HttpMessage(new byte[0]) : e.response().copy(), e.request());
    }

    private void installTreeMenu() {
        tree.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e)  { maybe(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { maybe(e); }
            private void maybe(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                TreePath p = tree.getPathForLocation(e.getX(), e.getY());
                if (p == null) return;
                tree.setSelectionPath(p);
                DefaultMutableTreeNode tn = (DefaultMutableTreeNode) p.getLastPathComponent();
                SiteMap.Node node = (SiteMap.Node) tn.getUserObject();
                buildNodeMenu(node).show(tree, e.getX(), e.getY());
            }
        });
    }

    private JPopupMenu buildNodeMenu(SiteMap.Node node) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem t = new JMenuItem(Ui.truncate(node.isHost ? node.service.url() : node.path, 60));
        t.setEnabled(false);
        menu.add(t);
        menu.addSeparator();
        addItem(menu, "add to scope", () -> {
            ctx.scope().includeUrl(node.service, node.path, node.isHost);
            ctx.scopeChanged();
        });
        addItem(menu, "remove from scope", () -> {
            ctx.scope().excludeUrl(node.service, node.path, node.isHost);
            ctx.scopeChanged();
        });
        java.util.List<LogEntry> ents = node.entries();
        LogEntry e = node.entry != null ? node.entry : (ents.isEmpty() ? null : ents.get(0));
        if (e != null) {
            menu.addSeparator();
            for (String tool : ctx.sinkNames()) {
                final LogEntry le = e;
                addItem(menu, "send to " + tool, () -> ctx.sendTo(tool, le.service(),
                        le.request() == null ? null : le.request().copy(),
                        le.response() == null ? null : le.response().copy(), null));
            }
        }
        return menu;
    }

    private void addItem(JPopupMenu menu, String label, Runnable r) {
        JMenuItem mi = new JMenuItem(label);
        mi.setFont(Ui.PLAIN);
        mi.addActionListener(e -> r.run());
        menu.add(mi);
    }

    private static class ItemsModel extends AbstractTableModel {
        private final String[] cols = {"host", "method", "URL", "status", "length", "MIME type", "title"};
        private List<LogEntry> all = new ArrayList<>();
        private List<LogEntry> entries = new ArrayList<>();
        private java.util.function.Predicate<LogEntry> filter = e -> true;

        void setEntries(List<LogEntry> e) { this.all = new ArrayList<>(e); reapply(); }

        void setFilter(java.util.function.Predicate<LogEntry> f) {
            this.filter = f == null ? e -> true : f;
            reapply();
        }

        private void reapply() {
            entries = new ArrayList<>();
            for (LogEntry e : all) if (filter.test(e)) entries.add(e);
            fireTableDataChanged();
        }

        LogEntry at(int r) { return (r >= 0 && r < entries.size()) ? entries.get(r) : null; }

        @Override public int getRowCount() { return entries.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            LogEntry e = entries.get(r);
            return switch (c) {
                case 0 -> e.service().url();
                case 1 -> e.request() == null ? "" : e.request().method();
                case 2 -> e.pathAndQuery();
                case 3 -> e.status() == 0 ? "" : String.valueOf(e.status());
                case 4 -> e.responseLen() == 0 ? "" : String.valueOf(e.responseLen());
                case 5 -> e.mimeType();
                default -> e.title();
            };
        }
    }
}
