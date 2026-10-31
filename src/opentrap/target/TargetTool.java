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
    // One filter for the tree and the contents table, as the reference has it.
    private final opentrap.proxy.HistoryFilter itemsFilter = opentrap.proxy.HistoryFilter.siteMapDefaults();
    private JButton itemsFilterButton;
    private JPanel layoutMarks;
    private boolean crawlPathsView;
    private MessageEditor reqView, respView;
    private JTree issuesTree;
    private DefaultTreeModel issuesTreeModel;
    private javax.swing.JEditorPane issueAdvisory;
    private JTextArea pathToIssue;
    private IssueLogModel issueLogModel;
    private MessageEditor issueReq, issueResp;
    private SiteMap.Node selectedNode;
    private java.util.List<SiteMap.Node> selectedNodes = new java.util.ArrayList<>();

    public TargetTool() {}

    @Override public String name() { return "target"; }
    @Override public boolean alwaysTabbed() { return true; }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        ctx.addSiteMapListener(new SuiteContext.SiteMapListener() {
            @Override public void siteMapChanged(LogEntry e) {
                // Coalesce: queue the entry and sync the tree once per tick, so a
                // burst of traffic (or a crawl) costs one tree sync, not one each.
                pendingEntries.add(e);
                SwingUtilities.invokeLater(() -> { if (!syncTimer.isRunning()) syncTimer.start(); });
            }
            @Override public void siteMapReloaded(List<LogEntry> all) {
                pendingEntries.clear();
                SwingUtilities.invokeLater(() -> reloadSiteMap(all));
            }
        });
        ctx.addIssueListener(new SuiteContext.IssueListener() {
            @Override public void issueAdded(Issue i) { SwingUtilities.invokeLater(TargetTool.this::refreshIssues); }
            @Override public void issuesReloaded()    { SwingUtilities.invokeLater(TargetTool.this::refreshIssues); }
        });
        // "Show only in-scope items" depends on the scope, so a scope edit
        // re-filters the tree and the table.
        ctx.addScopeListener(() -> SwingUtilities.invokeLater(() -> {
            if (itemsFilter.inScopeOnly) applyItemsFilter();
            else if (tree != null) tree.repaint();   // in-scope and muted icons
        }));
    }

    // ---- tree filtering -----------------------------------------------------

    /**
     * Whether a site map node is shown under the current filter. A leaf shows
     * when its own exchange passes; a folder shows when anything beneath it
     * passes, or, with "hide empty folders" off, when its own URL is not
     * filtered out by scope.
     */
    private boolean nodeVisible(SiteMap.Node n) {
        if (itemsFilter.isEverything() && !itemsFilter.hideEmptyFolders) return true;
        if (n.entry != null && itemsFilter.test(n.entry)) return true;
        for (LogEntry e : n.entries()) if (itemsFilter.test(e)) return true;
        if (n.entry != null) return false;   // a requested item that is filtered out
        if (itemsFilter.hideEmptyFolders) return false;
        return !itemsFilter.inScopeOnly || n.service == null
                || ctx.scope().isInScope(n.service, n.isHost ? "/" : n.path);
    }

    /** Rebuild the whole tree under the current filter, keeping what was expanded and selected. */
    private void rebuildTree() {
        if (treeModel == null || tree == null) return;
        java.util.Set<SiteMap.Node> expanded = new java.util.HashSet<>();
        for (int i = 0; i < tree.getRowCount(); i++) {
            TreePath p = tree.getPathForRow(i);
            if (tree.isExpanded(p) && ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject() instanceof SiteMap.Node n)
                expanded.add(n);
        }
        java.util.List<SiteMap.Node> wasSelected = new ArrayList<>(selectedNodes == null ? List.of() : selectedNodes);
        DefaultMutableTreeNode rootTn = (DefaultMutableTreeNode) treeModel.getRoot();
        rootTn.removeAllChildren();
        treeNodes.clear();
        treeNodes.put(siteMap.root(), rootTn);
        buildTree(siteMap.root(), rootTn);
        treeModel.reload();
        tree.expandPath(new TreePath(rootTn));
        for (SiteMap.Node n : expanded) {
            DefaultMutableTreeNode tn = treeNodes.get(n);
            if (tn != null) tree.expandPath(new TreePath(tn.getPath()));
        }
        java.util.List<TreePath> reselect = new ArrayList<>();
        for (SiteMap.Node n : wasSelected) {
            DefaultMutableTreeNode tn = treeNodes.get(n);
            if (tn != null) reselect.add(new TreePath(tn.getPath()));
        }
        if (!reselect.isEmpty()) tree.setSelectionPaths(reselect.toArray(new TreePath[0]));
        else if (tree.getRowCount() > 0) tree.setSelectionRow(0);
        else { selectedNodes = new ArrayList<>(); selectedNode = null; itemsModel.setEntries(List.of()); refreshIssues(); }
    }

    /** Entries recorded since the last tree sync, drained by {@link #syncTimer}. */
    private final java.util.concurrent.ConcurrentLinkedQueue<LogEntry> pendingEntries =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** Fires once per burst of traffic: adds every queued entry, then syncs the tree once. */
    private final javax.swing.Timer syncTimer = new javax.swing.Timer(120, e -> {
        LogEntry le;
        boolean any = false;
        while ((le = pendingEntries.poll()) != null) { siteMap.add(le); any = true; }
        if (any) syncTree();
        if (pendingEntries.isEmpty()) ((javax.swing.Timer) e.getSource()).stop();
    });

    /**
     * Rebuild the site map and its tree from a restored history in one pass:
     * every node is created up front and the model is reloaded once, instead of
     * a tree sync per entry, which made opening a large project take minutes.
     */
    private void reloadSiteMap(List<LogEntry> all) {
        siteMap.clear();
        for (LogEntry e : all) siteMap.add(e);
        if (treeModel == null || tree == null) return;
        DefaultMutableTreeNode rootTn = (DefaultMutableTreeNode) treeModel.getRoot();
        rootTn.removeAllChildren();
        treeNodes.clear();
        treeNodes.put(siteMap.root(), rootTn);
        buildTree(siteMap.root(), rootTn);
        selectedNodes = new ArrayList<>();
        selectedNode = null;
        treeModel.reload();
        tree.expandPath(new TreePath(rootTn));
        if (tree.getRowCount() > 0) tree.setSelectionRow(0);
        refreshIssues();
    }

    private void buildTree(SiteMap.Node sm, DefaultMutableTreeNode tn) {
        // Crawl paths view drops the folder hierarchy: under each host every
        // requested item hangs directly off the host, labelled by its full
        // path, so the paths that were actually reached read as one list.
        if (crawlPathsView && sm.isHost) {
            java.util.List<SiteMap.Node> leaves = new ArrayList<>();
            collectRequested(sm, leaves);
            for (SiteMap.Node leaf : leaves) {
                if (!nodeVisible(leaf)) continue;
                DefaultMutableTreeNode c = new DefaultMutableTreeNode(leaf);
                treeNodes.put(leaf, c);
                tn.add(c);
            }
            return;
        }
        for (SiteMap.Node child : sm.children.values()) {
            if (!nodeVisible(child)) continue;
            DefaultMutableTreeNode c = new DefaultMutableTreeNode(child);
            treeNodes.put(child, c);
            tn.add(c);
            buildTree(child, c);
        }
    }

    /** Every node at or below {@code n} that stands for a request that was made. */
    private void collectRequested(SiteMap.Node n, java.util.List<SiteMap.Node> out) {
        for (SiteMap.Node c : n.children.values()) {
            if (c.entry != null) out.add(c);
            collectRequested(c, out);
        }
    }

    /** The "URL view" / "Crawl paths view" switch above the filter bar. */
    private JComponent buildViewSwitch() {
        return Ui.segmented(i -> { crawlPathsView = i == 1; rebuildTree(); },
                "URL view", "Crawl paths view");
    }

    /**
     * The "Path to issue" tab: the chain of requests that led to the finding,
     * or a plain note for a passively detected one, which has no chain.
     */
    private JComponent buildPathToIssue() {
        pathToIssue = Ui.note("");
        pathToIssue.setBorder(Ui.pad(10, 12, 10, 12));
        return Ui.scroll(pathToIssue);
    }

    /** The "Log" tab: when the issue was recorded, by what, and against what path. */
    private JComponent buildIssueLog() {
        issueLogModel = new IssueLogModel();
        JTable t = new JTable(issueLogModel);
        Ui.styleTable(t);
        Ui.distributeWidths(t, 150, 90, 160, 300);
        return Ui.scroll(t);
    }

    /** One row per recorded step for the selected issue. */
    private static final class IssueLogModel extends AbstractTableModel {
        private final String[] cols = {"Time", "Source", "Action", "Path"};
        private final java.text.DateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss d MMM yyyy");
        private java.util.List<Object[]> rows = new ArrayList<>();

        void show(Issue i) {
            rows = new ArrayList<>();
            if (i != null) {
                rows.add(new Object[]{fmt.format(new java.util.Date(i.time())), i.source(), "Issue recorded", i.path()});
                if (i.request() != null) rows.add(new Object[]{fmt.format(new java.util.Date(i.time())), i.source(), "Request sent", i.request().startLine()});
            }
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) { return rows.get(r)[c]; }
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
        tree.setRowHeight(20);
        // Hosts carry a padlock (closed for https, open and red for http) and
        // are labelled by host and port, the scheme being what the padlock
        // says; folders and requests get flat icons. Out-of-scope nodes are
        // muted when a scope is set, so the target stands out.
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree t, Object value,
                    boolean sel, boolean exp, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(t, value, sel, exp, leaf, row, focus);
                if (value instanceof DefaultMutableTreeNode tn && tn.getUserObject() instanceof SiteMap.Node n) {
                    boolean muted = !ctx.scope().isEmpty() && n.service != null
                            && !ctx.scope().isInScope(n.service, n.isHost ? "/" : n.path);
                    if (n.isHost && n.service != null) {
                        setText(n.service.host() + (n.service.defaultPort() ? "" : ":" + n.service.port()));
                        setIcon(SiteIcons.host(n.service.secure(), muted));
                    } else {
                        if (crawlPathsView) setText(n.label.startsWith("?") ? n.path + n.label : n.path);
                        setIcon(n.children.isEmpty() || crawlPathsView ? SiteIcons.page(muted) : SiteIcons.folder(muted));
                    }
                    if (!sel) setForeground(muted ? Theme.MUTED : Theme.TEXT);
                }
                setBackgroundNonSelectionColor(t.getBackground());
                return this;
            }
        });

        itemsModel = new ItemsModel();
        itemsTable = new JTable(itemsModel);
        Ui.styleTable(itemsTable);
        // host, method, URL, params, status code, length, MIME type, title
        Ui.distributeWidths(itemsTable, 180, 55, 300, 50, 70, 70, 90, 200);
        Ui.centerCells(itemsTable);
        itemsTable.getColumnModel().getColumn(3).setCellRenderer(new Ui.TickRenderer());

        reqView = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        respView = new MessageEditor(ctx, MessageEditor.RESPONSE, false);

        tree.addTreeSelectionListener(e -> showTreeSelection());
        itemsTable.getSelectionModel().addListSelectionListener(e -> showItem());
        installTreeMenu();

        itemsFilter.scope = ctx.scope();
        JComponent contents = new JPanel(new BorderLayout());
        JLabel contentsHeader = Ui.bold(" Contents");
        contentsHeader.setBorder(Ui.pad(3, 2, 3, 2));
        contents.add(contentsHeader, BorderLayout.NORTH);
        contents.add(Ui.scroll(itemsTable), BorderLayout.CENTER);

        JSplitPane detail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqView, respView);
        detail.setDividerSize(5);
        Ui.balance(detail, 0.5);
        JSplitPane center = new JSplitPane(JSplitPane.VERTICAL_SPLIT, contents, detail);
        center.setDividerSize(5);
        Ui.balance(center, 0.45);

        JComponent issuesPanel = buildIssuesPanel();

        final JSplitPane rightmost = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, issuesPanel);
        rightmost.setDividerSize(5);
        Ui.balance(rightmost, 0.6);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(tree), rightmost);
        split.setDividerSize(5);
        Ui.balance(split, 0.22);

        // Two layout marks at the right of the filter bar: the issues pane
        // beside the contents, or the contents alone across the full width.
        layoutMarks = Ui.segmented(i -> {
            if (i == 0) { rightmost.setRightComponent(issuesPanel); rightmost.setDividerSize(5); Ui.balance(rightmost, 0.6); }
            else { rightmost.setRightComponent(null); rightmost.setDividerSize(0); }
        }, "Split", "Single");

        // The filter bar spans the tree and the contents, as in the reference,
        // because one filter drives both; above it the view switch, and above
        // that the out-of-scope logging notice when the proxy has stopped
        // recording other sites.
        JPanel top = new JPanel(new BorderLayout());
        top.add(new OutOfScopeBanner(ctx), BorderLayout.NORTH);
        JPanel viewRow = new JPanel(new BorderLayout());
        viewRow.setBorder(Ui.pad(4, 4, 0, 4));
        viewRow.add(buildViewSwitch(), BorderLayout.WEST);
        JPanel stacked = new JPanel(new BorderLayout());
        stacked.add(viewRow, BorderLayout.NORTH);
        stacked.add(buildItemsFilterBar(), BorderLayout.CENTER);
        top.add(stacked, BorderLayout.CENTER);
        JPanel page = new JPanel(new BorderLayout());
        page.add(top, BorderLayout.NORTH);
        page.add(split, BorderLayout.CENTER);
        applyItemsFilter();
        return page;
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
        issueAdvisory.setEditorKit(Theme.htmlKit());
        issueReq = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        issueResp = new MessageEditor(ctx, MessageEditor.RESPONSE, false);

        issuesTree.addTreeSelectionListener(e -> showIssue());

        JLabel header = Ui.bold(" Issues");
        header.setBorder(Ui.pad(3, 2, 3, 2));
        JPanel top = new JPanel(new BorderLayout());
        top.add(header, BorderLayout.NORTH);
        top.add(Ui.scroll(issuesTree), BorderLayout.CENTER);

        // One tabbed detail pane: Advisory, Request, Response, Path to issue, Log.
        JTabbedPane detailTabs = new JTabbedPane();
        detailTabs.setFont(Ui.PLAIN);
        detailTabs.addTab("Advisory", Ui.scroll(issueAdvisory));
        detailTabs.addTab("Request", issueReq);
        detailTabs.addTab("Response", issueResp);
        detailTabs.addTab("Path to issue", buildPathToIssue());
        detailTabs.addTab("Log", buildIssueLog());

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
            if (pathToIssue != null) pathToIssue.setText("");
            if (issueLogModel != null) issueLogModel.show(null);
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
        if (o instanceof Issue i) return "issue\0" + i.dedupeKey();
        if (o instanceof IssueType t) return "type\0" + t.title();
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
        // response, so the relevant part is obvious.
        String ev = i.evidence();
        issueReq.highlight(ev);
        issueResp.highlight(ev);
        if (pathToIssue != null) {
            pathToIssue.setText(i.request() == null
                    ? "No path available\n\nThis is a passively detected issue. No modified requests were "
                      + "made to find it, so there is no chain of requests to show."
                    : "The issue was found against this request:\n\n" + i.request().startLine()
                      + "\n\nHost: " + (i.service() == null ? i.host() : i.service().url())
                      + (i.param() == null ? "" : "\nParameter: " + i.param()));
            pathToIssue.setCaretPosition(0);
        }
        if (issueLogModel != null) issueLogModel.show(i);
    }




    private JComponent buildScope() {
        // Heading, one-line description and the editor, as in the reference.
        JTextArea note = Ui.note(
            "Use these settings to define exactly what hosts and URLs constitute the target for "
            + "your current work. This configuration affects the behaviour of tools throughout "
            + "the suite. Add a URL prefix here, or browse to your target and use the site map's "
            + "context menu; advanced scope control takes regex rules instead.");
        note.setMaximumSize(new Dimension(900, note.getPreferredSize().height));
        JPanel body = Ui.vbox(Ui.heading("Target scope"), Box.createVerticalStrut(4), note,
                Box.createVerticalStrut(14), new ScopeEditor(ctx, ctx.scope()));
        body.setBorder(Ui.pad(10, 10, 10, 10));
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(body, BorderLayout.NORTH);
        return Ui.scroll(panel);
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
            boolean visible = nodeVisible(child);
            if (childTn == null) {
                if (!visible) continue;
                childTn = new DefaultMutableTreeNode(child);
                treeNodes.put(child, childTn);
                treeModel.insertNodeInto(childTn, tn, tn.getChildCount());
            } else if (!visible) {
                // A new exchange can take a node out of view (a folder that is
                // now all not-found items, say); a later one can bring it back.
                treeModel.removeNodeFromParent(childTn);
                removeTreeNodes(child);
                continue;
            }
            sync(child);
        }
    }

    private void removeTreeNodes(SiteMap.Node n) {
        treeNodes.remove(n);
        for (SiteMap.Node c : n.children.values()) removeTreeNodes(c);
    }

    /**
     * The site map filter bar: a clickable summary that opens the full filter
     * and full-text search, plus a quick URL search, matching the proxy history.
     */
    private JComponent buildItemsFilterBar() {
        itemsFilterButton = Ui.filterButton();
        itemsFilterButton.setText("Site map filter: " + itemsFilter.summary());
        itemsFilterButton.addActionListener(e -> {
            java.awt.Window owner = SwingUtilities.getWindowAncestor(itemsTable);
            new opentrap.proxy.FilterDialog(owner, "Site map filter",
                    itemsFilter, true, TargetTool.this::applyItemsFilter).setVisible(true);
        });
        JTextField urlSearch = Ui.searchField(22);
        urlSearch.setToolTipText("quick search of the URL only");
        urlSearch.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            void go() { itemsFilter.urlSearch = urlSearch.getText(); applyItemsFilter(); }
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        JButton kebab = opentrap.ui.Glyphs.menuButton("Table layout and custom columns", () -> opentrap.ui.TableLayoutMenu.build(itemsTable));
        JButton help = Ui.helpButton("Site map",
                "The site map records every host, folder and request the suite has seen.\n"
                + "The filter above decides which of them the tree and the contents table show.");
        return Ui.filterBar(itemsFilterButton, urlSearch, help, layoutMarks, kebab);
    }

    private void applyItemsFilter() {
        if (itemsModel == null) return;
        itemsModel.setFilter(itemsFilter::test);
        if (itemsFilterButton != null) itemsFilterButton.setText("Site map filter: " + itemsFilter.summary());
        rebuildTree();
    }

    /** The filter travels with the project. */
    @Override public java.io.Serializable saveState() { return itemsFilter.copy(); }

    @Override public void restoreState(java.io.Serializable state) {
        if (!(state instanceof opentrap.proxy.HistoryFilter f)) return;
        f.scope = ctx.scope();
        itemsFilter.copyFrom(f);
        SwingUtilities.invokeLater(this::applyItemsFilter);
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
        reqView.showRequest(e);
        respView.showResponse(e);
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
        // One item that reads as the opposite of the node's state, as in the
        // reference: a node already in scope offers to leave it.
        boolean inScope = node.service != null
                && ctx.scope().isInScope(node.service, node.isHost ? "/" : node.path);
        if (inScope) {
            addItem(menu, "remove from scope", () -> {
                ctx.scope().removeUrl(node.service, node.path, node.isHost);
                ctx.scopeChanged();
            });
        } else {
            addItem(menu, "add to scope", () -> {
                ctx.scope().includeUrl(node.service, node.path, node.isHost);
                ctx.scopeChanged();
            });
        }
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
        JMenuItem mi = new JMenuItem(Ui.sentence(label));
        mi.setFont(Ui.PLAIN);
        mi.addActionListener(e -> r.run());
        menu.add(mi);
    }

    private static class ItemsModel extends AbstractTableModel {
        private final String[] cols = {"Host", "Method", "URL", "Params", "Status code", "Length", "MIME type", "Title"};
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
        @Override public Class<?> getColumnClass(int c) { return c == 3 ? Boolean.class : String.class; }

        @Override public Object getValueAt(int r, int c) {
            LogEntry e = entries.get(r);
            return switch (c) {
                case 0 -> e.service().url();
                case 1 -> e.request() == null ? "" : e.request().method();
                case 2 -> e.pathAndQuery();
                case 3 -> e.request() != null && e.request().hasParameters();
                case 4 -> e.status() == 0 ? "" : String.valueOf(e.status());
                case 5 -> e.responseLen() == 0 ? "" : String.valueOf(e.responseLen());
                case 6 -> e.mimeType();
                default -> e.title();
            };
        }
    }
}
