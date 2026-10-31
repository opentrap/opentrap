package opentrap.dashboard;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.Issue;
import opentrap.core.LogEntry;
import opentrap.core.MessageStore;
import opentrap.core.ScanTask;
import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.ui.MessageEditor;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The suite dashboard: the front tab and owner of the scanning engine.
 *
 * <p>It replaces the old scanner tab entirely. The left panel lists every scan
 * task as a card (live passive audit plus any active audits); the right panel
 * shows a summary, a sortable issue table with request/response detail, and the
 * selected task's event log. All model changes arrive on background threads and
 * are marshalled onto the EDT before touching Swing.
 */
public class DashboardTool implements ToolModule {

    // Severity palette (shared with the rest of the suite).
    private static final Color HIGH_COL   = Ui.SEV_HIGH;
    private static final Color MEDIUM_COL = Ui.SEV_MEDIUM;
    private static final Color LOW_COL    = Ui.SEV_LOW;
    private static final Color INFO_COL   = Ui.SEV_INFO;

    private static final Color CARD_BG      = Theme.CARD;
    private static final Color CARD_SEL_BG  = Ui.SELECTION;
    private static final Color CARD_BORDER  = Ui.GRID;
    private static final Color GREY_TEXT    = Theme.MUTED;
    private static final Color FINISHED_GREEN = Theme.OK;

    private SuiteContext ctx;
    /** The shared ZAP scan engine (same instance the passive-rules tab uses). */
    private opentrap.engine.ZapEngine zapEngine() {
        return opentrap.engine.ZapEngine.shared();
    }

    private final JPanel root = new JPanel(new BorderLayout());

    // ---- left ----
    private final JPanel taskList = new JPanel();
    private JTextField searchField;
    private final Map<ScanTask, TaskCard> cards = new IdentityHashMap<>();
    /** Re-run action per task, so "Scan again" relaunches the original scan. */
    private final Map<ScanTask, Runnable> rerun = new IdentityHashMap<>();
    // The engine settings each scan ran with, for "View configuration".
    private final Map<ScanTask, opentrap.engine.ScanConfig> taskConfigs = new IdentityHashMap<>();
    // Tasks whose finish has already triggered a project save.
    private final java.util.Set<ScanTask> savedOnFinish =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private ScanTask selected;
    /** Drives the sliding progress-bar animation while a crawl is running. */
    private javax.swing.Timer cardAnimTimer;

    // Background scan threads report progress, issues and log lines far faster
    // than the UI needs to redraw. These coalescers collapse each storm into at
    // most one refresh per interval so the event-dispatch thread never floods.
    private final Coalescer tasksRefresh  = new Coalescer(200, this::rebuildTasks);
    private final Coalescer issuesRefresh = new Coalescer(200, this::onIssuesChanged);
    private final Coalescer logRefresh     = new Coalescer(200, this::flushScanLog);
    private final java.util.Queue<LogEntry> logAddQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private volatile boolean logHasUpdates;

    // ---- right ----
    private final JLabel rightHeader = Ui.heading("");
    private final java.awt.CardLayout rightCards = new java.awt.CardLayout();
    private final JPanel rightBody = new JPanel(rightCards);
    private JTextArea crawlLog;
    private JLabel crawlStats;
    // The crawl window's live Logger: a table of the scan's HTTP traffic with a
    // request/response viewer, fed from the shared store as the engine runs.
    private final ScanLogModel scanLogModel = new ScanLogModel();
    private JTable scanLogTable;
    private MessageEditor scanReq, scanResp;
    private final IssueTableModel summaryModel =
            new IssueTableModel(new String[]{"Issue type", "Host", "Time"});
    private final IssueTableModel issuesModel =
            new IssueTableModel(new String[]{"Time", "Source", "Issue type", "Host", "Path",
                    "Insertion point", "Severity", "Confidence", "Comment"});
    private JTable summaryTable;
    private JTable issuesTable;
    private final AuditLogModel auditLogModel = new AuditLogModel();
    private JTable auditLogTable;

    private final JLabel cfgType   = Ui.label("");
    private final JLabel cfgScope  = Ui.label("");
    private final JLabel cfgConfig = Ui.label("");

    // Task progress: the six metrics we can source from the ZAP engine, shown
    // in two columns matching the reference layout.
    private final JLabel progTotalItems   = Ui.label("");
    private final JLabel progItemsActive  = Ui.label("");
    private final JLabel progItemsDone    = Ui.label("");
    private final JLabel progDiscovered   = Ui.label("");
    private final JLabel progRequests     = Ui.label("");
    private final JLabel progPerSecond    = Ui.label("");
    private final JTextArea summaryTaskLog = new JTextArea();

    private JEditorPane issueDetail;
    private MessageEditor issueRequest;
    private MessageEditor issueResponse;

    private final JTextArea eventLog = new JTextArea();

    public DashboardTool() {
        buildUi();
    }

    // ----------------------------------------------------------------- module

    @Override public String name() { return "dashboard"; }

    @Override public boolean alwaysTabbed() { return false; }

    @Override public Tab[] tabs() { return new Tab[]{ new Tab("dashboard", root) }; }

    private ScanTask liveAudit;
    private opentrap.engine.LivePassiveScanner passiveScanner;

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        ctx.addIssueListener(new SuiteContext.IssueListener() {
            @Override public void issueAdded(Issue i) { issuesRefresh.request(); }
            @Override public void issuesReloaded()    { issuesRefresh.request(); }
        });
        ctx.addTaskListener(t -> {
            tasksRefresh.request();
            // A scan that has just finished is worth keeping: write the project
            // now rather than waiting for the next autosave or for exit.
            if (t.type() != ScanTask.Type.LIVE_PASSIVE && t.state() == ScanTask.State.FINISHED
                    && savedOnFinish.add(t)) {
                opentrap.ui.StateIO.autosave(ctx);
            }
        });

        // Feed the crawl window's live Logger from the shared store: every crawl
        // or audit request/response the engine records shows up there as it runs.
        ctx.store().addListener(new MessageStore.Listener() {
            @Override public void entryAdded(LogEntry e) {
                if (isScanEntry(e)) { logAddQueue.add(e); logRefresh.request(); }
                else countLiveRequest(e);
            }
            @Override public void entryUpdated(LogEntry e) {
                if (isScanEntry(e)) { logHasUpdates = true; logRefresh.request(); }
            }
            @Override public void reloaded(List<LogEntry> all) {
                for (LogEntry e : all) if (isScanEntry(e)) logAddQueue.add(e);
                logRefresh.request();
            }
        });

        issueRequest  = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        issueResponse = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
        issueSplit.setTopComponent(issueRequest);
        issueSplit.setBottomComponent(issueResponse);

        // The one default task: a live passive audit capturing from the Proxy.
        // Scans the user starts add their own tasks on top of this.
        liveAudit = new ScanTask(ScanTask.Type.LIVE_PASSIVE,
                "Live audit from Proxy (all traffic)", "Audit checks - passive");
        liveAudit.log("Capturing in-scope traffic from the Proxy; the passive checks run on every exchange");
        ctx.addTask(liveAudit);

        // Live passive scanning: route in-scope proxy traffic through the engine
        // and poll its alerts into issues. On by default.
        passiveScanner = new opentrap.engine.LivePassiveScanner(ctx);
        ctx.setLiveScanRouter(passiveScanner::routeFor);
        passiveScanner.setEnabled(true);

        rebuildTasks();
        onIssuesChanged();
    }

    @Override public void shutdown() {
        // The ZAP scan engine is shared and shuts itself down via its own hook.
        ensureCardAnimation(false);
    }

    // ----------------------------------------------------------------- layout

    private JSplitPane issueSplit;

    private void buildUi() {
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT,
                buildLeft(), buildRight());
        split.setBorder(null);
        Ui.balance(split, 0.28);

        root.add(split, BorderLayout.CENTER);
    }

    private JComponent buildLeft() {
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBorder(Ui.pad(8, 8, 8, 8));

        // Header: bold "Tasks" + action buttons.
        JButton newScan = Ui.primaryButton("New scan", "crawl and audit a target");
        JButton newLiveTask = Ui.primaryButton("New live task", "start a live task");
        JButton spiderBtn = Ui.button("Spider");
        accentise(newScan);
        accentise(newLiveTask);
        accentise(spiderBtn);
        newScan.addActionListener(e -> newScan());
        opentrap.ui.Unbuilt.DASHBOARD_LIVE_TASK.attach(newLiveTask);
        spiderBtn.addActionListener(e -> startSpider());

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Ui.heading("Tasks"), BorderLayout.WEST);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.setOpaque(false);
        btns.add(newScan);
        btns.add(newLiveTask);
        btns.add(spiderBtn);
        header.add(btns, BorderLayout.EAST);

        // Filter + search row.
        searchField = Ui.field("", 12);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { rebuildTasks(); }
            @Override public void removeUpdate(DocumentEvent e)  { rebuildTasks(); }
            @Override public void changedUpdate(DocumentEvent e) { rebuildTasks(); }
        });
        JComboBox<String> filter = Ui.combo(new String[]{"All tasks", "Running", "Paused", "Finished"});
        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        filterRow.setOpaque(false);
        filterRow.add(filter, BorderLayout.WEST);
        filterRow.add(searchField, BorderLayout.CENTER);

        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setOpaque(false);
        header.setAlignmentX(Component.LEFT_ALIGNMENT);
        filterRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        top.add(header);
        top.add(Box.createVerticalStrut(6));
        top.add(filterRow);

        // Scrollable list of cards.
        taskList.setLayout(new BoxLayout(taskList, BoxLayout.Y_AXIS));
        taskList.setOpaque(false);
        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(taskList, BorderLayout.NORTH);

        left.add(top, BorderLayout.NORTH);
        left.add(Ui.scroll(holder), BorderLayout.CENTER);
        left.setPreferredSize(new Dimension(340, 600));
        return left;
    }

    private JComponent buildRight() {
        JPanel right = new JPanel(new BorderLayout(0, 6));
        right.setBorder(Ui.pad(8, 8, 8, 8));

        // Task header bar: a pennant glyph and the task title on the left, three
        // round control buttons (play/pause, settings, help) on the right.
        rightHeader.setText("Dashboard");
        rightHeader.setIcon(pennantIcon());
        rightHeader.setIconTextGap(10);

        JPanel headerControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        headerControls.setOpaque(false);
        headerControls.add(new HeaderIconButton("play", "Pause or resume the selected task", () -> {
            if (selected != null && selected.state() != ScanTask.State.FINISHED) {
                selected.setState(selected.state() == ScanTask.State.RUNNING
                        ? ScanTask.State.PAUSED : ScanTask.State.RUNNING);
                if (ctx != null) ctx.taskChanged(selected);
            }
        }));
        headerControls.add(new HeaderIconButton("gear", "View the selected task's configuration",
                () -> showTaskConfig(selected, rightHeader)));
        headerControls.add(new HeaderIconButton("help", "Getting started",
                () -> { if (ctx != null && ctx.frame() instanceof opentrap.ui.SuiteFrame sf) sf.selectTool("discover"); }));

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(rightHeader, BorderLayout.WEST);
        header.add(headerControls, BorderLayout.EAST);
        right.add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Summary", buildSummaryTab());
        tabs.addTab("Issues", buildIssuesTab());
        tabs.addTab("Event log", buildEventLogTab());
        tabs.addTab("Audit log", buildAuditLogTab());
        tabs.addTab("Live crawl view", opentrap.ui.Unbuilt.DASHBOARD_LIVE_CRAWL.panel());

        rightBody.add(tabs, "scan");
        rightBody.add(buildCrawlCard(), "crawl");
        right.add(rightBody, BorderLayout.CENTER);
        return right;
    }

    /**
     * Crawl view: a live Logger of the scan's HTTP traffic (table + request and
     * response viewer), plus an Event log tab, like the reference crawl window.
     */
    private JComponent buildCrawlCard() {
        JPanel p = new JPanel(new BorderLayout(0, 6));

        crawlStats = Ui.bold("Crawling");
        crawlStats.setBorder(Ui.pad(2, 2, 4, 2));
        p.add(crawlStats, BorderLayout.NORTH);

        // ---- Logger: live request table over a request/response viewer ----
        scanLogTable = new JTable(scanLogModel);
        Ui.styleTable(scanLogTable);
        Ui.distributeWidths(scanLogTable, 48, 95, 60, 70, 170, 200, 150, 60, 70);
        lightHeader(scanLogTable);
        scanLogTable.setRowSorter(new javax.swing.table.TableRowSorter<>(scanLogModel));
        Ui.newestFirst(scanLogTable, 0);
        scanReq  = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        scanResp = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
        scanLogTable.getSelectionModel().addListSelectionListener(e -> showScanSelected());
        JScrollPane logScroll = new JScrollPane(scanLogTable);
        logScroll.setBorder(BorderFactory.createLineBorder(Ui.GRID));
        JSplitPane detail = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, scanReq, scanResp);
        detail.setDividerSize(5);
        Ui.balance(detail, 0.5);
        JSplitPane logger = new JSplitPane(JSplitPane.VERTICAL_SPLIT, logScroll, detail);
        logger.setDividerSize(5);
        Ui.balance(logger, 0.5);

        // ---- Event log: the crawl's own progress lines ----
        crawlLog = new JTextArea();
        crawlLog.setEditable(false);
        crawlLog.setFont(Ui.MONO);
        crawlLog.setLineWrap(true);
        crawlLog.setWrapStyleWord(false);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Logger", logger);
        tabs.addTab("Event log", Ui.scroll(crawlLog));
        p.add(tabs, BorderLayout.CENTER);
        return p;
    }

    private void showScanSelected() {
        int row = scanLogTable.getSelectedRow();
        if (row < 0) return;
        LogEntry e = scanLogModel.at(scanLogTable.convertRowIndexToModel(row));
        if (e == null) return;
        scanReq.setMessage(e.service(), e.request() == null ? new HttpMessage(new byte[0]) : e.request().copy());
        scanResp.setMessage(e.service(), e.response() == null ? new HttpMessage(new byte[0]) : e.response().copy(), e.request());
    }

    /** True for traffic the engine's crawl or audit produced. */
    private static boolean isScanEntry(LogEntry e) {
        String t = e.tool();
        return "crawl".equals(t) || "audit".equals(t);
    }

    // Distinct method + URL pairs the live passive audit has seen: its "locations".
    private final java.util.Set<String> liveLocations = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The live passive audit's figures: every in-scope exchange that reaches the
     * engine while capture is on counts as a request, and each distinct method
     * and URL as a discovered location.
     */
    private void countLiveRequest(LogEntry e) {
        if (liveAudit == null || liveAudit.state() != ScanTask.State.RUNNING) return;
        if (e.service() == null || e.request() == null || !ctx.scope().isInScope(e)) return;
        liveAudit.addRequests(1);
        liveLocations.add(e.request().method() + " " + e.url());
        liveAudit.setDiscoveredLocations(liveLocations.size());
        ctx.taskChanged(liveAudit);
    }

    private JComponent buildSummaryTab() {
        // ---- left card: most serious vulnerabilities (all issues, by rank) ----
        summaryTable = new JTable(summaryModel);
        Ui.styleTable(summaryTable);
        summaryTable.setAutoCreateRowSorter(true);
        Ui.distributeWidths(summaryTable, 260, 230, 170);
        summaryIssueIconColumn(summaryTable, 0);   // glyph icon before the issue type
        lightHeader(summaryTable);
        JScrollPane sumScroll = new JScrollPane(summaryTable);
        sumScroll.setBorder(BorderFactory.createLineBorder(Ui.GRID));
        sumScroll.getVerticalScrollBar().setUnitIncrement(16);
        summaryTable.setFillsViewportHeight(true);
        sumScroll.getViewport().setBackground(Theme.CARD);

        JPanel vulnsCard = card();
        vulnsCard.setLayout(new BorderLayout(0, 10));
        vulnsCard.add(sectionHeader("Most serious vulnerabilities found (live)",
                sectionIcon("warning"), "View all", this::selectIssuesTab), BorderLayout.NORTH);
        vulnsCard.add(sumScroll, BorderLayout.CENTER);

        // ---- right card: configuration / progress / log, one card with rules ----
        summaryTaskLog.setEditable(false);
        summaryTaskLog.setFont(Ui.MONO);
        summaryTaskLog.setLineWrap(false);
        summaryTaskLog.setOpaque(false);
        summaryTaskLog.setBorder(null);
        JScrollPane logScroll = new JScrollPane(summaryTaskLog);
        logScroll.setBorder(null);
        logScroll.setOpaque(false);
        logScroll.getViewport().setOpaque(false);

        JPanel top = new JPanel();
        top.setOpaque(false);
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        addLeft(top, sectionHeader("Task configuration", sectionIcon("pencil"),
                "View configuration",
                () -> showTaskConfig(selected, top)));
        top.add(Box.createVerticalStrut(10));
        addLeft(top, configGrid());
        top.add(Box.createVerticalStrut(16));
        addLeft(top, divider());
        top.add(Box.createVerticalStrut(16));
        addLeft(top, sectionHeader("Task progress", sectionIcon("pie"), null, null));
        top.add(Box.createVerticalStrut(10));
        addLeft(top, progressGrid());
        top.add(Box.createVerticalStrut(16));
        addLeft(top, divider());
        top.add(Box.createVerticalStrut(16));
        addLeft(top, sectionHeader("Task log", sectionIcon("clock"), null, null));
        top.add(Box.createVerticalStrut(8));

        JPanel rightCard = card();
        rightCard.setLayout(new BorderLayout());
        rightCard.add(top, BorderLayout.NORTH);
        rightCard.add(logScroll, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, vulnsCard, rightCard);
        split.setDividerSize(12);
        split.setBorder(null);
        split.setOpaque(false);
        Ui.balance(split, 0.52);

        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.setBorder(Ui.pad(6, 6, 6, 6));
        holder.add(split, BorderLayout.CENTER);
        return holder;
    }

    /** A human-readable label for a scan task type, as the reference shows. */
    private static String typeLabel(ScanTask.Type type) {
        return switch (type) {
            case LIVE_PASSIVE -> "Live passive audit";
            case LIVE_ACTIVE  -> "Crawl";
            case AUDIT        -> "Audit";
        };
    }

    private void selectIssuesTab() {
        // "View all" jumps to the full Issues tab.
        if (summaryTable == null) return;
        java.awt.Container c = summaryTable;
        while (c != null && !(c instanceof JTabbedPane)) c = c.getParent();
        if (c instanceof JTabbedPane tp) {
            int idx = tp.indexOfTab("Issues");
            if (idx >= 0) tp.setSelectedIndex(idx);
        }
    }

    /** Add a component to a vertical box, left-aligned and capped to its height. */
    private static void addLeft(JPanel box, JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        box.add(c);
    }

    /** A thin horizontal rule between card sections. */
    private static JComponent divider() {
        JSeparator s = new JSeparator(SwingConstants.HORIZONTAL);
        s.setForeground(Ui.GRID);
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return s;
    }

    /** A rounded, white card with a subtle border, like the reference panels. */
    private static JPanel card() {
        JPanel p = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(getBackground());
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.setColor(Ui.GRID);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
                g2.dispose();
            }
        };
        p.setBackground(Theme.CARD);
        p.setOpaque(false);
        p.setBorder(Ui.pad(12, 14, 14, 14));
        return p;
    }

    /** A section heading row: icon + bold title, with an optional accent link. */
    private JPanel sectionHeader(String title, javax.swing.Icon icon, String linkText, Runnable onLink) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        JLabel h = Ui.bold(title);
        if (icon != null) { h.setIcon(icon); h.setIconTextGap(8); }
        p.add(h, BorderLayout.WEST);
        if (linkText != null) p.add(accentLink(linkText, onLink), BorderLayout.EAST);
        return p;
    }

    /** A plain accent-coloured clickable link, as the reference uses for actions. */
    private static JLabel accentLink(String text, Runnable onClick) {
        JLabel l = new JLabel(text);
        l.setFont(Ui.PLAIN);
        l.setForeground(Ui.ACCENT);
        l.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        l.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                if (onClick != null) onClick.run();
            }
        });
        return l;
    }

    /** Task configuration: label/value rows aligned in two columns. */
    /**
     * A read-only view of the engine settings a task ran with. Settings cannot
     * change once a scan is under way, so this shows them rather than editing them.
     */
    private void showTaskConfig(ScanTask task, Component near) {
        if (task == null) {
            JOptionPane.showMessageDialog(near, "Select a task first.", "Task configuration",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        opentrap.engine.ScanConfig c = taskConfigs.get(task);
        JPanel g = new JPanel(new GridBagLayout());
        g.setOpaque(false);
        int row = 0;
        addKv(g, row++, "Task:", Ui.label(task.title()));
        addKv(g, row++, "Type:", Ui.label(task.configuration()));
        addKv(g, row++, "Scope:", Ui.label(task.scope()));
        if (c == null) {
            addKv(g, row, "Configuration:", Ui.label("not recorded for this task"));
        } else {
            addKv(g, row++, "Configuration:", Ui.label(c.name()));
            addKv(g, row++, "Crawl depth:", Ui.label(c.crawlDepth() == 0 ? "unlimited" : String.valueOf(c.crawlDepth())));
            addKv(g, row++, "Crawl time limit:", Ui.label(minutes(c.crawlDurationMin())));
            addKv(g, row++, "Submit forms:", Ui.label(c.submitForms() ? "yes" : "no"));
            addKv(g, row++, "AJAX crawl:", Ui.label(c.ajaxSpider() ? "on" : "off"));
            if (c.ajaxSpider()) {
                addKv(g, row++, "AJAX time limit:", Ui.label(minutes(c.ajaxMaxDurationMin())));
                addKv(g, row++, "AJAX depth:", Ui.label(c.ajaxMaxDepth() == 0 ? "unlimited" : String.valueOf(c.ajaxMaxDepth())));
                addKv(g, row++, "AJAX states:", Ui.label(c.ajaxMaxStates() == 0 ? "unlimited" : String.valueOf(c.ajaxMaxStates())));
            }
            addKv(g, row++, "Attack strength:", Ui.label(c.attackStrength()));
            addKv(g, row++, "Alert threshold:", Ui.label(c.alertThreshold()));
            addKv(g, row++, "Injection points:", Ui.label(mask(c.injectableMask(),
                    new int[]{opentrap.engine.ScanConfig.INJECT_QUERY, opentrap.engine.ScanConfig.INJECT_BODY,
                            opentrap.engine.ScanConfig.INJECT_PATH, opentrap.engine.ScanConfig.INJECT_HEADERS,
                            opentrap.engine.ScanConfig.INJECT_COOKIE},
                    new String[]{"query string", "body", "path", "headers", "cookies"})));
            addKv(g, row++, "Body types:", Ui.label(mask(c.rpcMask(),
                    new int[]{opentrap.engine.ScanConfig.RPC_MULTIPART, opentrap.engine.ScanConfig.RPC_XML,
                            opentrap.engine.ScanConfig.RPC_JSON, opentrap.engine.ScanConfig.RPC_GWT,
                            opentrap.engine.ScanConfig.RPC_ODATA},
                    new String[]{"multipart", "XML", "JSON", "GWT", "OData"})));
            addKv(g, row++, "Request delay:", Ui.label(c.delayMs() == 0 ? "none" : c.delayMs() + " ms"));
            addKv(g, row, "Audit time limit:", Ui.label(minutes(c.scanDurationMin())));
        }
        JPanel body = new JPanel(new BorderLayout());
        body.setBorder(Ui.pad(16, 20, 12, 20));
        body.add(g, BorderLayout.NORTH);
        Window owner = near == null ? null : SwingUtilities.getWindowAncestor(near);
        JDialog d = new JDialog(owner, "Task configuration", Dialog.ModalityType.APPLICATION_MODAL);
        JButton close = Ui.button("Close");
        close.addActionListener(e -> d.dispose());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bar.add(close);
        body.add(bar, BorderLayout.SOUTH);
        d.setContentPane(body);
        d.pack();
        d.setSize(Math.max(460, d.getWidth()), d.getHeight());
        d.setLocationRelativeTo(near);
        d.setVisible(true);
    }

    private static String minutes(int m) { return m == 0 ? "none" : m + " min"; }

    private static String mask(int value, int[] bits, String[] names) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < bits.length; i++) {
            if ((value & bits[i]) == 0) continue;
            if (b.length() > 0) b.append(", ");
            b.append(names[i]);
        }
        return b.length() == 0 ? "none" : b.toString();
    }

    private JPanel configGrid() {
        JPanel g = new JPanel(new GridBagLayout());
        g.setOpaque(false);
        addKv(g, 0, "Task type:", cfgType);
        addKv(g, 1, "Scope:", cfgScope);
        addKv(g, 2, "Configuration:", cfgConfig);
        return g;
    }

    /** Task progress: the eight metrics in two aligned label/value columns. */
    private JPanel progressGrid() {
        JPanel g = new JPanel(new GridBagLayout());
        g.setOpaque(false);
        addKv2(g, 0, "Total audit items:", progTotalItems, "Discovered locations:", progDiscovered);
        addKv2(g, 1, "Audit items in progress:", progItemsActive, "Requests:", progRequests);
        addKv2(g, 2, "Audit items completed:", progItemsDone, "Requests per second:", progPerSecond);
        return g;
    }

    private static void addKv(JPanel g, int row, String caption, JLabel value) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row; c.anchor = GridBagConstraints.WEST; c.insets = new Insets(2, 0, 2, 0);
        c.gridx = 0; c.insets = new Insets(2, 0, 2, 14); g.add(Ui.bold(caption), c);
        c.gridx = 1; c.insets = new Insets(2, 0, 2, 0); c.weightx = 1; g.add(value, c);
    }

    private static void addKv2(JPanel g, int row, String cap1, JLabel v1, String cap2, JLabel v2) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = row; c.anchor = GridBagConstraints.WEST;
        c.gridx = 0; c.insets = new Insets(2, 0, 2, 10); g.add(Ui.bold(cap1), c);
        c.gridx = 1; c.insets = new Insets(2, 0, 2, 26); g.add(v1, c);
        c.gridx = 2; c.insets = new Insets(2, 0, 2, 10); g.add(Ui.bold(cap2), c);
        c.gridx = 3; c.insets = new Insets(2, 0, 2, 0); c.weightx = 1; g.add(v2, c);
    }

    /** Give the summary table a light header with a bottom rule, like the reference. */
    private static void lightHeader(JTable t) {
        javax.swing.table.JTableHeader h = t.getTableHeader();
        h.setBackground(Theme.HEADER);
        h.setForeground(Theme.TEXT);
        h.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.GRID));
        h.setReorderingAllowed(false);
    }

    /**
     * Put a severity glyph (a coloured disc with "!" for serious
     * findings, "i" for informational ones) in front of the issue-type text.
     */
    private void summaryIssueIconColumn(JTable table, int textCol) {
        table.getColumnModel().getColumn(textCol).setCellRenderer(
            new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v,
                        boolean sel, boolean focus, int row, int column) {
                    super.getTableCellRendererComponent(t, v, sel, focus, row, column);
                    int mr = t.convertRowIndexToModel(row);
                    setIcon(issueGlyphIcon(summaryModel.severityAt(mr), summaryModel.confidenceAt(mr)));
                    setIconTextGap(7);
                    return this;
                }
            });
    }

    /**
     * A filled severity disc with a white glyph, matching the reference: "?" for
     * a tentative (unconfirmed) finding, "i" for informational, "!" otherwise.
     * The disc is coloured by severity.
     */
    private static javax.swing.Icon issueGlyphIcon(Issue.Severity sev, Issue.Confidence conf) {
        final Color col = Ui.severityColor(sev);
        final String glyph = conf == Issue.Confidence.TENTATIVE ? "?"
                : (sev == Issue.Severity.INFORMATION ? "i" : "!");
        return new javax.swing.Icon() {
            @Override public int getIconWidth()  { return 15; }
            @Override public int getIconHeight() { return 15; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(col);
                g2.fillOval(x + 1, y + 1, 13, 13);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("Dialog", Font.BOLD, 11));
                FontMetrics fm = g2.getFontMetrics();
                int tx = x + 1 + (13 - fm.stringWidth(glyph)) / 2;
                int ty = y + 1 + (13 + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(glyph, tx, ty);
                g2.dispose();
            }
        };
    }

    /** A small pennant/flag glyph shown to the left of the task title. */
    private static javax.swing.Icon pennantIcon() {
        int s = 18;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.MUTED);
        g.setStroke(new BasicStroke(1.4f));
        g.drawLine(5, 2, 5, 16);
        g.drawPolygon(new int[]{5, 15, 5}, new int[]{3, 7, 11}, 3);
        g.dispose();
        return new javax.swing.ImageIcon(img);
    }

    /** A round header control button (play/pause, settings or help glyph). */
    private static final class HeaderIconButton extends JComponent {
        private final String kind;
        HeaderIconButton(String kind, String tip, Runnable onClick) {
            this.kind = kind;
            Dimension d = new Dimension(26, 26);
            setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(tip);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if (onClick != null) onClick.run();
                }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight(), cx = w / 2, cy = h / 2;
            g2.setColor(Theme.ICON);
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawOval(2, 2, w - 5, h - 5);
            switch (kind) {
                case "play" -> g2.fillPolygon(new int[]{cx - 3, cx - 3, cx + 5},
                        new int[]{cy - 5, cy + 5, cy}, 3);
                case "gear" -> {
                    // A cog: eight short, thick teeth on a ring with a solid hub.
                    g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
                    for (int a = 0; a < 360; a += 45) {
                        double r = Math.toRadians(a);
                        g2.draw(new java.awt.geom.Line2D.Double(
                                cx + 5.0 * Math.cos(r), cy + 5.0 * Math.sin(r),
                                cx + 7.5 * Math.cos(r), cy + 7.5 * Math.sin(r)));
                    }
                    g2.setStroke(new BasicStroke(1.6f));
                    g2.draw(new java.awt.geom.Ellipse2D.Double(cx - 5, cy - 5, 10, 10));
                    g2.fill(new java.awt.geom.Ellipse2D.Double(cx - 2, cy - 2, 4, 4));
                }
                case "help" -> {
                    g2.setFont(new Font("Dialog", Font.BOLD, 13));
                    FontMetrics fm = g2.getFontMetrics();
                    String q = "?";
                    g2.drawString(q, cx - fm.stringWidth(q) / 2,
                            cy + (fm.getAscent() - fm.getDescent()) / 2);
                }
            }
            g2.dispose();
        }
    }

    private JComponent buildIssuesTab() {
        issuesTable = new JTable(issuesModel);
        Ui.styleTable(issuesTable);
        Ui.distributeWidths(issuesTable, 95, 70, 190, 140, 130, 110, 80, 80, 110);
        Ui.severityColumn(issuesTable, 6);           // Severity column
        Ui.severityIconColumn(issuesTable, 2, 6);    // severity dot before the issue type
        issuesTable.getSelectionModel().addListSelectionListener(this::onIssueRowSelected);

        issueDetail = new JEditorPane();
        issueDetail.setEditorKit(Theme.htmlKit());
        issueDetail.setEditable(false);
        issueDetail.setFont(Ui.PLAIN);

        // Request/response editors are created in init() once ctx exists.
        issueSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        issueSplit.setBorder(null);
        Ui.balance(issueSplit, 0.5);

        JTabbedPane detailTabs = new JTabbedPane();
        detailTabs.addTab("Advisory", Ui.scroll(issueDetail));
        detailTabs.addTab("Request / response", issueSplit);

        JButton exportReport = Ui.button("export report", "save all findings as an HTML or XML report");
        exportReport.addActionListener(e -> exportIssuesReport());
        JPanel issuesBar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 3));
        issuesBar.add(exportReport);
        JPanel issuesPanel = new JPanel(new BorderLayout());
        issuesPanel.add(issuesBar, BorderLayout.NORTH);
        issuesPanel.add(Ui.scroll(issuesTable), BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, issuesPanel, detailTabs);
        split.setBorder(null);
        Ui.balance(split, 0.4);
        return split;
    }

    private void exportIssuesReport() {
        List<Issue> all = ctx == null ? new ArrayList<>() : ctx.issues();
        if (all.isEmpty()) {
            JOptionPane.showMessageDialog(ctx == null ? null : ctx.frame(),
                    "There are no findings to export yet.", "export report", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object[] options = {"HTML", "XML", "Cancel"};
        int choice = JOptionPane.showOptionDialog(ctx.frame(), "Export the findings as:", "export report",
                JOptionPane.DEFAULT_OPTION, JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        if (choice == 0) opentrap.report.Export.save(ctx.frame(), "opentrap-report.html", opentrap.report.Export.issuesHtml(all));
        else if (choice == 1) opentrap.report.Export.save(ctx.frame(), "opentrap-report.xml", opentrap.report.Export.issuesXml(all));
    }

    private JComponent buildEventLogTab() {
        eventLog.setEditable(false);
        eventLog.setFont(Ui.MONO);
        eventLog.setLineWrap(false);
        return Ui.scroll(eventLog);
    }

    /** A chronological log of findings as they were detected. */
    private JComponent buildAuditLogTab() {
        auditLogTable = new JTable(auditLogModel);
        Ui.styleTable(auditLogTable);
        Ui.distributeWidths(auditLogTable, 48, 150, 80, 90, 200, 150, 130, 130, 85, 85);
        lightHeader(auditLogTable);
        auditLogGlyphColumn(auditLogTable, 4);   // severity/confidence glyph before the issue type
        // Severity column stays plain dark text: the glyph carries the colour.

        javax.swing.table.TableRowSorter<AuditLogModel> sorter =
                new javax.swing.table.TableRowSorter<>(auditLogModel);
        auditLogTable.setRowSorter(sorter);
        Ui.newestFirst(auditLogTable, 0);

        // Filter state shared by the Filter popup and the Search box.
        final java.util.Set<Issue.Severity> enabled = java.util.EnumSet.allOf(Issue.Severity.class);
        final String[] query = { "" };
        final Runnable apply = () -> sorter.setRowFilter(new RowFilter<AuditLogModel, Integer>() {
            @Override public boolean include(Entry<? extends AuditLogModel, ? extends Integer> e) {
                Issue is = auditLogModel.issueAt(e.getIdentifier());
                if (is != null && !enabled.contains(is.severity())) return false;
                String q = query[0].toLowerCase();
                if (q.isEmpty()) return true;
                for (int c = 0; c < e.getValueCount(); c++) {
                    Object v = e.getValue(c);
                    if (v != null && v.toString().toLowerCase().contains(q)) return true;
                }
                return false;
            }
        });

        // ---- Filter button (left): a real per-severity filter ----
        JButton filter = new JButton("Filter", funnelIcon());
        filter.setFont(Ui.PLAIN);
        filter.setFocusPainted(false);
        filter.addActionListener(e -> {
            JPopupMenu m = new JPopupMenu();
            for (Issue.Severity s : Issue.Severity.values()) {
                JCheckBoxMenuItem it = new JCheckBoxMenuItem(s.label(), enabled.contains(s));
                it.addActionListener(a -> {
                    if (it.isSelected()) enabled.add(s); else enabled.remove(s);
                    apply.run();
                });
                m.add(it);
            }
            m.show(filter, 0, filter.getHeight());
        });

        // ---- Search box (right): a real text filter over all columns ----
        JTextField search = new JTextField(16);
        search.setBorder(null);
        search.setOpaque(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            private void upd() { query[0] = search.getText().trim(); apply.run(); }
            @Override public void insertUpdate(DocumentEvent e) { upd(); }
            @Override public void removeUpdate(DocumentEvent e) { upd(); }
            @Override public void changedUpdate(DocumentEvent e) { upd(); }
        });
        JPanel searchWrap = new JPanel(new BorderLayout(6, 0));
        searchWrap.setBackground(Theme.BG);
        searchWrap.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.GRID), Ui.pad(3, 8, 3, 8)));
        searchWrap.add(search, BorderLayout.CENTER);
        searchWrap.add(new JLabel(magnifierIcon()), BorderLayout.EAST);

        JPanel bar = new JPanel(new BorderLayout());
        bar.setOpaque(false);
        bar.setBorder(Ui.pad(0, 0, 6, 0));
        bar.add(filter, BorderLayout.WEST);
        bar.add(searchWrap, BorderLayout.EAST);

        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        p.setBorder(Ui.pad(4, 4, 4, 4));
        p.add(bar, BorderLayout.NORTH);
        p.add(Ui.scroll(auditLogTable), BorderLayout.CENTER);
        return p;
    }

    /** The severity/confidence glyph disc in front of an audit-log issue type. */
    private void auditLogGlyphColumn(JTable table, int textCol) {
        table.getColumnModel().getColumn(textCol).setCellRenderer(
            new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v,
                        boolean sel, boolean focus, int row, int column) {
                    super.getTableCellRendererComponent(t, v, sel, focus, row, column);
                    Issue is = auditLogModel.issueAt(t.convertRowIndexToModel(row));
                    if (is != null) setIcon(issueGlyphIcon(is.severity(), is.confidence()));
                    setIconTextGap(7);
                    return this;
                }
            });
    }

    /** A small funnel glyph for the Filter button. */
    private static javax.swing.Icon funnelIcon() {
        int s = 14;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.MUTED);
        g.setStroke(new BasicStroke(1.3f));
        g.drawPolygon(new int[]{1, 13, 8, 6}, new int[]{2, 2, 8, 13}, 4);
        g.dispose();
        return new javax.swing.ImageIcon(img);
    }

    /** A small magnifier glyph for the Search box. */
    private static javax.swing.Icon magnifierIcon() {
        int s = 14;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.MUTED);
        g.setStroke(new BasicStroke(1.4f));
        g.drawOval(2, 2, 7, 7);
        g.drawLine(9, 9, 13, 13);
        g.dispose();
        return new javax.swing.ImageIcon(img);
    }

    // ---------------------------------------------------------------- helpers

    private static void accentise(JButton b) {
        // Plain, like every other button in the app (no orange).
        b.setFocusPainted(false);
    }

    /** Small grey line icons for the summary section headings. */
    private static javax.swing.Icon sectionIcon(String kind) {
        int s = 16;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.MUTED);
        g.setStroke(new BasicStroke(1.3f));
        switch (kind) {
            case "folder" -> {
                g.drawLine(2, 5, 6, 5); g.drawLine(6, 5, 7, 4);
                g.drawRoundRect(2, 5, 12, 8, 2, 2);
            }
            case "gear" -> {
                g.drawOval(5, 5, 6, 6);
                for (int a = 0; a < 360; a += 45) {
                    double r = Math.toRadians(a);
                    g.drawLine((int) (8 + 4 * Math.cos(r)), (int) (8 + 4 * Math.sin(r)),
                               (int) (8 + 6.5 * Math.cos(r)), (int) (8 + 6.5 * Math.sin(r)));
                }
            }
            case "clock" -> {
                g.drawOval(2, 2, 12, 12);
                g.drawLine(8, 8, 8, 4); g.drawLine(8, 8, 11, 9);
            }
            case "warning" -> {
                // A rounded triangle with an exclamation mark.
                g.drawPolygon(new int[]{8, 2, 14}, new int[]{2, 13, 13}, 3);
                g.drawLine(8, 6, 8, 9);
                g.fillOval(7, 10, 2, 2);
            }
            case "pencil" -> {
                g.drawLine(3, 13, 11, 5);
                g.drawLine(11, 5, 13, 7);
                g.drawLine(13, 7, 5, 15);
                g.drawLine(3, 13, 5, 15);
            }
            case "pie" -> {
                g.drawOval(2, 2, 12, 12);
                g.drawLine(8, 8, 8, 2);
                g.drawLine(8, 8, 13, 10);
            }
        }
        g.dispose();
        return new javax.swing.ImageIcon(img);
    }

    // ----------------------------------------------------------- task cards

    /**
     * Collapses a burst of refresh requests into at most one refresh per
     * interval on the event-dispatch thread. {@link #request()} may be called
     * from any thread; the action always runs on the EDT.
     */
    private static final class Coalescer {
        private final javax.swing.Timer timer;
        private volatile boolean dirty;
        Coalescer(int intervalMs, Runnable action) {
            timer = new javax.swing.Timer(intervalMs, e -> {
                if (dirty) { dirty = false; action.run(); }
                else ((javax.swing.Timer) e.getSource()).stop();
            });
            timer.setRepeats(true);
            timer.setInitialDelay(0);   // the first refresh is prompt, not delayed
        }
        void request() {
            dirty = true;
            SwingUtilities.invokeLater(() -> { if (!timer.isRunning()) timer.start(); });
        }
    }

    /** Apply queued scan-log entries to the table in one batch (runs on the EDT). */
    private void flushScanLog() {
        List<LogEntry> batch = new ArrayList<>();
        for (LogEntry e; (e = logAddQueue.poll()) != null; ) batch.add(e);
        if (!batch.isEmpty()) scanLogModel.addAll(batch);
        if (logHasUpdates) { logHasUpdates = false; scanLogModel.refreshRows(); }
    }

    private void rebuildTasks() {
        List<ScanTask> tasks = ctx == null ? List.of() : ctx.tasks();
        String q = searchField == null ? "" : searchField.getText().trim().toLowerCase();

        taskList.removeAll();
        cards.clear();

        // Keep the previous selection alive if it still exists.
        boolean selectionStillPresent = false;

        for (ScanTask t : tasks) {
            if (!q.isEmpty()) {
                String hay = (t.number + " " + t.title() + " " + t.configuration()
                        + " " + t.stateLabel()).toLowerCase();
                if (!hay.contains(q)) continue;
            }
            TaskCard card = new TaskCard(t);
            cards.put(t, card);
            card.setAlignmentX(Component.LEFT_ALIGNMENT);
            taskList.add(card);
            taskList.add(Box.createVerticalStrut(6));
            if (t == selected) selectionStillPresent = true;
        }

        if (!selectionStillPresent) {
            selected = tasks.isEmpty() ? null : tasks.get(0);
        }
        for (Map.Entry<ScanTask, TaskCard> e : cards.entrySet()) {
            e.getValue().setSelectedLook(e.getKey() == selected);
        }

        taskList.revalidate();
        taskList.repaint();

        // Run the shared animation timer only while a crawl is actively running,
        // so an idle dashboard does no repaint work.
        boolean animating = tasks.stream().anyMatch(t ->
                t.type() == ScanTask.Type.LIVE_ACTIVE && t.state() == ScanTask.State.RUNNING);
        ensureCardAnimation(animating);

        refreshRight();
    }

    /** Start or stop the single timer that animates running crawl progress bars. */
    private void ensureCardAnimation(boolean animating) {
        if (animating) {
            if (cardAnimTimer == null) {
                cardAnimTimer = new javax.swing.Timer(30, e -> taskList.repaint());
                cardAnimTimer.start();
            }
        } else if (cardAnimTimer != null) {
            cardAnimTimer.stop();
            cardAnimTimer = null;
        }
    }

    private void selectTask(ScanTask t) {
        selected = t;
        for (Map.Entry<ScanTask, TaskCard> e : cards.entrySet()) {
            e.getValue().setSelectedLook(e.getKey() == selected);
        }
        refreshRight();
    }

    private Window ownerFrame() { return ctx == null ? null : ctx.frame(); }

    /** Rename a task to any label the user types. */
    private void renameTask(ScanTask t) {
        Object input = JOptionPane.showInputDialog(ownerFrame(), "Rename task:",
                "Rename task", JOptionPane.PLAIN_MESSAGE, null, null, t.title());
        if (input == null) return;
        String name = input.toString().trim();
        if (name.isEmpty()) return;
        t.setTitle(name);
        if (ctx != null) ctx.taskChanged(t);
    }

    /** Remove a task from the dashboard after confirmation. */
    private void deleteTask(ScanTask t) {
        int ok = JOptionPane.showConfirmDialog(ownerFrame(),
                "Remove task \"" + t.number + ". " + t.title() + "\" from the dashboard?\n"
                + "Any findings it has already reported are kept.",
                "Delete task", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        // Really stop the task: cancel() signals its scan loop to stop the engine
        // (spider/active scan/AJAX spider) and exit, so nothing keeps running or
        // accumulating memory in the background after the card is gone.
        t.cancel();
        if (t == liveAudit && passiveScanner != null) passiveScanner.setEnabled(false);
        rerun.remove(t);
        // The task's config and its already-saved marker go with it; leaving
        // them held every task (deleted included) for the whole session.
        taskConfigs.remove(t);
        savedOnFinish.remove(t);
        if (selected == t) selected = null;
        if (ctx != null) ctx.removeTask(t);
    }

    /** Open a task's live details in a separate, read-only window. */
    private void openTaskWindow(ScanTask t) {
        JFrame f = new JFrame(t.number + ". " + t.title());
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setBorder(Ui.pad(12, 12, 12, 12));

        JPanel meta = new JPanel(new GridLayout(0, 1, 0, 3));
        meta.add(Ui.bold(t.number + ". " + t.title()));
        JLabel type   = Ui.label("");
        JLabel config = Ui.label("Configuration: " + t.configuration());
        JLabel scope  = Ui.label("Scope: " + (t.scope().isEmpty() ? "-" : t.scope()));
        JLabel status = Ui.label("");
        type.setText("Type: " + typeLabel(t.type()));
        meta.add(type);
        meta.add(config);
        meta.add(scope);
        meta.add(status);

        JTextArea log = new JTextArea();
        log.setEditable(false);
        log.setFont(Ui.MONO);
        JScrollPane sp = Ui.scroll(log);
        sp.setPreferredSize(new Dimension(560, 300));

        body.add(meta, BorderLayout.NORTH);
        body.add(sp, BorderLayout.CENTER);
        f.setContentPane(body);
        f.setSize(600, 440);
        f.setLocationRelativeTo(ownerFrame());

        Runnable refresh = () -> {
            status.setText("Status: " + t.stateLabel()
                    + "   ·   Issues: " + (t.high() + t.medium() + t.low() + t.info()));
            String text = String.join("\n", t.logLines());
            if (!text.equals(log.getText())) {
                log.setText(text);
                log.setCaretPosition(log.getDocument().getLength());
            }
        };
        refresh.run();
        // Keep the window live while it is open, then stop the timer on close.
        javax.swing.Timer timer = new javax.swing.Timer(1000, e -> refresh.run());
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { timer.stop(); }
        });
        timer.start();
        f.setVisible(true);
    }

    /** A single task card in the left-hand list. */
    private final class TaskCard extends JPanel {
        private final ScanTask task;

        private boolean selectedLook;

        TaskCard(ScanTask task) {
            super(new BorderLayout(0, 4));
            this.task = task;
            setOpaque(false);
            setBorder(Ui.pad(12, 16, 12, 14));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredHeight()));

            boolean passive  = task.type() == ScanTask.Type.LIVE_PASSIVE;
            boolean finished = task.state() == ScanTask.State.FINISHED;
            boolean running  = task.state() == ScanTask.State.RUNNING;

            // ---- header: title (truncates), a play/pause circle, an overflow menu.
            JLabel title = Ui.bold(task.number + ". " + task.title());
            title.setToolTipText(task.number + ". " + task.title());

            JPanel controls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            controls.setOpaque(false);
            if (!finished) controls.add(new CircleButton(running, this::toggleState));
            controls.add(new MenuButton());

            JPanel titleRow = new JPanel(new BorderLayout(6, 0));
            titleRow.setOpaque(false);
            titleRow.add(title, BorderLayout.CENTER);
            titleRow.add(controls, BorderLayout.EAST);

            JLabel config = Ui.label(task.configuration());
            config.setForeground(GREY_TEXT);
            config.setFont(Ui.PLAIN.deriveFont(11f));

            JPanel head = new JPanel();
            head.setOpaque(false);
            head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
            titleRow.setAlignmentX(LEFT_ALIGNMENT);
            config.setAlignmentX(LEFT_ALIGNMENT);
            head.add(titleRow);
            head.add(config);

            // The passive audit is a simple on/off capture stage, so it shows a
            // "Capturing" toggle and no bar. Active work (a crawl, or an audit)
            // keeps a progress bar while it runs, but a finished task shows no bar.
            boolean crawl = task.type() == ScanTask.Type.LIVE_ACTIVE;
            JComponent progress = (passive || finished) ? null : new ProgressStrip(task);

            JPanel stateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            stateRow.setOpaque(false);
            if (passive && !finished) {
                // On/off toggle for the continuous passive capture stage.
                stateRow.add(Ui.label("Capturing"));
                ToggleSwitch sw = new ToggleSwitch(running);
                sw.setOnToggle(this::toggleState);
                stateRow.add(sw);
            } else {
                // Active task, or a finished one: show the status word next to a
                // small state icon. A finished task reads in green.
                JLabel st = Ui.label(statusText(finished, crawl));
                st.setIcon(statusIcon(task.state()));
                st.setIconTextGap(5);
                if (finished) st.setForeground(FINISHED_GREEN);
                stateRow.add(st);
            }

            // A crawl discovers, it does not detect: its card carries its real
            // figures (locations and requests) rather than issue pills that could
            // only ever read zero. Audits show their issue pills; the live passive
            // card counts every issue found so far, and says so.
            JComponent tail = crawl ? crawlFigures() : issuesRow();

            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            head.setAlignmentX(LEFT_ALIGNMENT);
            stateRow.setAlignmentX(LEFT_ALIGNMENT);
            body.add(head);
            if (progress != null) {
                progress.setAlignmentX(LEFT_ALIGNMENT);
                body.add(Box.createVerticalStrut(6));
                body.add(progress);
            }
            body.add(Box.createVerticalStrut(6));
            body.add(stateRow);
            if (tail != null) {
                tail.setAlignmentX(LEFT_ALIGNMENT);
                tail.setMaximumSize(new Dimension(Integer.MAX_VALUE, tail.getPreferredSize().height));
                body.add(Box.createVerticalStrut(8));
                body.add(tail);
            }

            add(body, BorderLayout.CENTER);

            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) { selectTask(task); }
            });

            setSelectedLook(task == selected);
        }

        /** The status word shown beside the state icon. */
        private String statusText(boolean finished, boolean crawl) {
            if (!finished) return task.stateLabel();
            return crawl ? "Crawl finished"
                    : (task.type() == ScanTask.Type.AUDIT ? "Audit finished" : "Finished");
        }

        /** Flip the task between running and paused, driving live scanning too. */
        private void toggleState() {
            task.setState(task.state() == ScanTask.State.RUNNING
                    ? ScanTask.State.PAUSED : ScanTask.State.RUNNING);
            if (task == liveAudit) {
                boolean on = task.state() == ScanTask.State.RUNNING;
                if (passiveScanner != null) passiveScanner.setEnabled(on);
                task.log(on ? "Capture resumed" : "Capture paused");
            }
            ctx.taskChanged(task);
        }

        /** The right-aligned "Issues:" label followed by one pill per severity. */
        private JComponent issuesRow() {
            JPanel row = new JPanel();
            row.setOpaque(false);
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.add(Box.createHorizontalGlue());
            JLabel lbl = Ui.label(task.type() == ScanTask.Type.LIVE_PASSIVE ? "All issues:" : "Issues:");
            lbl.setForeground(GREY_TEXT);
            row.add(lbl);
            row.add(Box.createHorizontalStrut(6));
            row.add(pill(task.high(),   HIGH_COL));   row.add(Box.createHorizontalStrut(5));
            row.add(pill(task.medium(), MEDIUM_COL)); row.add(Box.createHorizontalStrut(5));
            row.add(pill(task.low(),    LOW_COL));    row.add(Box.createHorizontalStrut(5));
            row.add(pill(task.info(),   INFO_COL));
            return row;
        }

        /** What a crawl found, right-aligned where an audit shows its pills. */
        private JComponent crawlFigures() {
            JPanel row = new JPanel();
            row.setOpaque(false);
            row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
            row.add(Box.createHorizontalGlue());
            int loc = Math.max(0, task.discoveredLocations());
            JLabel lbl = Ui.label("Discovered " + loc + (loc == 1 ? " location, " : " locations, ")
                    + task.requests() + (task.requests() == 1 ? " request" : " requests"));
            lbl.setForeground(GREY_TEXT);
            row.add(lbl);
            return row;
        }

        private int getPreferredHeight() {
            boolean finished = task.state() == ScanTask.State.FINISHED;
            if (task.type() == ScanTask.Type.LIVE_PASSIVE) return 118;
            if (finished) return 120;            // figures or issues row, no progress bar
            return 148;
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            // A clean rounded card, the same whether or not it is selected.
            g2.setColor(Theme.CARD);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 12, 12);
            g2.setColor(Ui.GRID);
            g2.drawRoundRect(0, 0, w - 1, h - 1, 12, 12);
            // The card stays a clean white tile whether or not it is selected;
            // selection shows only as a slim accent bar on the left edge.
            if (selectedLook) {
                g2.setColor(Ui.ACCENT);
                g2.fillRoundRect(2, 8, 4, h - 17, 4, 4);
            }
            g2.dispose();
        }

        /** The task's three-dot overflow menu. */
        private final class MenuButton extends JComponent {
            /** When the menu last closed, so a click that dismissed it toggles shut
             *  instead of immediately reopening a fresh menu. */
            private long menuHiddenAt;

            MenuButton() {
                Dimension d = new Dimension(16, 22);
                setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                setToolTipText("Task actions");
                addMouseListener(new java.awt.event.MouseAdapter() {
                    @Override public void mousePressed(java.awt.event.MouseEvent e) {
                        // The click that closes an open popup arrives here right
                        // after the popup hides; ignore it so a second click acts
                        // as "close" rather than reopening the menu.
                        if (System.currentTimeMillis() - menuHiddenAt < 250) return;
                        showMenu();
                    }
                });
            }

            private void showMenu() {
                JPopupMenu m = new JPopupMenu();
                boolean finished = task.state() == ScanTask.State.FINISHED;
                boolean running  = task.state() == ScanTask.State.RUNNING;
                if (!finished) m.add(mi(running ? "Pause task" : "Resume task", this::toggle));
                m.add(mi("View task details", () -> selectTask(task)));
                m.add(mi("Open in new window", () -> openTaskWindow(task)));
                m.addSeparator();
                m.add(mi("Rename task", () -> renameTask(task)));
                if (rerun.containsKey(task)) m.add(mi("Scan again", () -> {
                    Runnable r = rerun.get(task);
                    if (r != null) r.run();
                }));
                m.add(mi("View configuration", () -> showTaskConfig(task, TaskCard.this)));
                m.addSeparator();
                m.add(mi("Delete", () -> deleteTask(task)));
                m.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
                    @Override public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {}
                    @Override public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
                        menuHiddenAt = System.currentTimeMillis();
                    }
                    @Override public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
                        menuHiddenAt = System.currentTimeMillis();
                    }
                });
                m.show(MenuButton.this, 0, getHeight());
            }

            private void toggle() { toggleState(); }

            private JMenuItem mi(String text, Runnable action) {
                JMenuItem it = new JMenuItem(text);
                it.addActionListener(a -> action.run());
                return it;
            }
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(Theme.ICON);
                int cx = getWidth() / 2, cy = getHeight() / 2, r = 2;
                for (int i = -1; i <= 1; i++) g2.fillOval(cx - r, cy - r + i * 6, r * 2, r * 2);
                g2.dispose();
            }
        }

        void setSelectedLook(boolean on) {
            this.selectedLook = on;
            repaint();
        }
    }

    /** A round play/pause button, like the control on each scan-task tile. */
    private static final class CircleButton extends JComponent {
        private final boolean running;
        CircleButton(boolean running, Runnable onClick) {
            this.running = running;
            Dimension d = new Dimension(22, 22);
            setPreferredSize(d); setMinimumSize(d); setMaximumSize(d);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setToolTipText(running ? "Pause" : "Resume");
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    if (onClick != null) onClick.run();
                }
            });
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.ICON);
            g2.setStroke(new BasicStroke(1.4f));
            g2.drawOval(1, 1, w - 3, h - 3);
            int cx = w / 2, cy = h / 2;
            if (running) {
                // Two pause bars.
                g2.fillRect(cx - 4, cy - 4, 2, 8);
                g2.fillRect(cx + 2, cy - 4, 2, 8);
            } else {
                // A play triangle, nudged right so it reads as centred.
                int[] xs = {cx - 3, cx - 3, cx + 5};
                int[] ys = {cy - 5, cy + 5, cy};
                g2.fillPolygon(xs, ys, 3);
            }
            g2.dispose();
        }
    }

    /** A slim, rounded progress bar for active tasks (no inline text). */
    private static final class ProgressStrip extends JComponent {
        // A shared clock so the slide stays continuous even when the card list is
        // rebuilt (which recreates the strip) during a scan - otherwise the
        // segment would snap back to the start on every rebuild.
        private static final long ANIM_EPOCH = System.nanoTime();
        private static final double ANIM_SPEED = 2.2;   // radians per second

        private final ScanTask task;
        // A continuous task (a running crawl) has no percentage, so its bar shows
        // a segment sliding back and forth instead of a proportional fill. The
        // repaint ticks come from a single dashboard-level timer (see
        // ensureCardAnimation), not a per-strip one, so the slide never stalls
        // when the card list is rebuilt during a scan.
        private final boolean indeterminate;

        ProgressStrip(ScanTask task) {
            this.task = task;
            this.indeterminate = task.progress() < 0 && task.state() == ScanTask.State.RUNNING;
            setPreferredSize(new Dimension(220, 9));
            setMinimumSize(new Dimension(40, 9));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 9));
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(Theme.SURFACE_3);
            g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);
            g2.setColor(Ui.ACCENT);
            if (indeterminate) {
                // A short segment eased smoothly to and fro across the track. The
                // phase comes from the wall clock, so it never resets on rebuild.
                double phase = (System.nanoTime() - ANIM_EPOCH) / 1_000_000_000.0 * ANIM_SPEED;
                int segW = Math.max(h * 3, (int) (w * 0.28));
                double frac = (1 - Math.cos(phase)) / 2;   // 0..1..0, slowing at each end
                int x = (int) Math.round((w - 1 - segW) * frac);
                g2.fillRoundRect(x, 0, segW, h - 1, h, h);
            } else {
                double p = task.progress();
                int fill = p >= 0 ? (int) Math.round(p * (w - 1))
                        : (task.state() == ScanTask.State.RUNNING ? w - 1 : 0);
                if (fill > 0) g2.fillRoundRect(0, 0, Math.max(h, fill), h - 1, h, h);
            }
            g2.dispose();
        }
    }

    /** A small icon marking a task's run state, shown beside the status word. */
    private static Icon statusIcon(ScanTask.State state) {
        return new Icon() {
            @Override public int getIconWidth()  { return 12; }
            @Override public int getIconHeight() { return 12; }
            @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(x, y);
                switch (state) {
                    case RUNNING -> {
                        g2.setColor(Ui.ACCENT);
                        g2.fillPolygon(new int[]{2, 2, 11}, new int[]{1, 11, 6}, 3);
                    }
                    case PAUSED -> {
                        g2.setColor(Theme.ICON);
                        g2.fillRect(2, 1, 3, 10);
                        g2.fillRect(7, 1, 3, 10);
                    }
                    case FINISHED -> {
                        g2.setColor(Theme.OK);
                        g2.setStroke(new BasicStroke(2f));
                        g2.drawPolyline(new int[]{1, 5, 11}, new int[]{6, 11, 1}, 3);
                    }
                }
                g2.dispose();
            }
        };
    }

    /** A small on/off pill toggle (blue when on, grey when off). */
    private static final class ToggleSwitch extends JComponent {
        private boolean on;
        private Runnable onToggle;

        ToggleSwitch(boolean on) {
            this.on = on;
            Dimension d = new Dimension(34, 18);
            setPreferredSize(d);
            setMinimumSize(d);
            setMaximumSize(d);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) {
                    ToggleSwitch.this.on = !ToggleSwitch.this.on;
                    repaint();
                    if (onToggle != null) onToggle.run();
                }
            });
        }

        void setOnToggle(Runnable r) { this.onToggle = r; }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            g2.setColor(on ? Ui.ACCENT : Theme.TOGGLE_OFF);
            g2.fillRoundRect(0, 1, w - 1, h - 3, h - 2, h - 2);
            int knob = h - 8;
            int x = on ? w - knob - 4 : 4;
            g2.setColor(Color.WHITE);
            g2.fillOval(x, 4, knob, knob);
            g2.dispose();
        }
    }

    /** A capsule-shaped, coloured issue-count pill with white text. */
    private static JComponent pill(int count, Color bg) {
        final String text = Integer.toString(count);
        JComponent c = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, w - 1, h - 1, h, h);   // radius = height: a capsule
                g2.setColor(Color.WHITE);
                g2.setFont(Ui.BOLD.deriveFont(11f));
                FontMetrics fm = g2.getFontMetrics();
                int tw = fm.stringWidth(text);
                int tx = (w - tw) / 2;
                int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(text, tx, ty);
                g2.dispose();
            }
            @Override public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(Ui.BOLD.deriveFont(11f));
                int w = Math.max(26, fm.stringWidth(text) + 16);
                return new Dimension(w, 20);
            }
            @Override public Dimension getMinimumSize() { return getPreferredSize(); }
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        return c;
    }

    // --------------------------------------------------------------- right

    private void refreshRight() {
        ScanTask t = selected;
        if (t == null) {
            rightCards.show(rightBody, "scan");
            applyIssueTables();
            rightHeader.setText("Dashboard");
            cfgType.setText("-");
            cfgScope.setText("-");
            cfgConfig.setText("-");
            for (JLabel l : new JLabel[]{progTotalItems, progItemsActive, progItemsDone,
                    progDiscovered, progRequests, progPerSecond}) {
                l.setText("0");
            }
            eventLog.setText("");
            summaryTaskLog.setText("");
            return;
        }

        rightHeader.setText(t.number + ". " + t.title());

        if (t.type() == ScanTask.Type.LIVE_ACTIVE) {
            // Crawl task: show the crawl view, not the scan/issues view.
            rightCards.show(rightBody, "crawl");
            crawlStats.setText("Discovered locations: " + t.discoveredLocations()
                    + "   ·   Requests: " + t.requests());
            crawlLog.setText(String.join("\n", t.logLines()));
            crawlLog.setCaretPosition(crawlLog.getDocument().getLength());
            return;
        }
        rightCards.show(rightBody, "scan");
        applyIssueTables();

        cfgType.setText(typeLabel(t.type()));
        cfgScope.setText(t.scope().isEmpty() ? "-" : t.scope());
        cfgConfig.setText(t.configuration());

        // Audit items: the active-scan rule count (real), split into completed
        // and in-progress by ZAP's live scan percentage.
        boolean running = t.state() == ScanTask.State.RUNNING;
        int ruleTotal = t.auditItemsTotal();
        double frac = t.progress() < 0 ? 0 : t.progress();   // -1 for continuous tasks
        int completed = (int) Math.round(ruleTotal * frac);
        int active = running ? Math.max(0, ruleTotal - completed) : 0;
        // The live passive audit has no fixed work list, so its audit-item
        // figures do not apply; its requests and locations are what it has seen.
        boolean passive = t.type() == ScanTask.Type.LIVE_PASSIVE;
        progTotalItems.setText(passive ? "-" : Integer.toString(ruleTotal));
        progItemsActive.setText(passive ? "-" : Integer.toString(active));
        progItemsDone.setText(passive ? "-" : Integer.toString(completed));
        progDiscovered.setText(Integer.toString(t.discoveredLocations()));
        progRequests.setText(Integer.toString(t.requests()));
        long secs = Math.max(1, (System.currentTimeMillis() - t.startedAt()) / 1000);
        progPerSecond.setText(running ? Integer.toString((int) (t.requests() / secs)) : "0");

        summaryTaskLog.setText(String.join("\n", t.logLines()));
        summaryTaskLog.setCaretPosition(summaryTaskLog.getDocument().getLength());

        eventLog.setText(String.join("\n", t.logLines()));
        eventLog.setCaretPosition(eventLog.getDocument().getLength());
    }

    // The shared issue list as last seen, and which task's slice the tables show.
    private List<Issue> allIssues = new ArrayList<>();
    private int issuesStamp;
    private ScanTask tablesFor;
    private int tablesStamp = -1;

    /**
     * Fill the Summary, Issues and Audit log tables for the selected task: a
     * scan task shows only what it found, the live audit shows the shared set.
     * Rebuilt only when the task or the issue list has changed, so the
     * once-a-second progress refresh does not reset the tables' selection.
     */
    private void applyIssueTables() {
        ScanTask t = selected;
        if (t == tablesFor && issuesStamp == tablesStamp) return;
        tablesFor = t;
        tablesStamp = issuesStamp;
        List<Issue> rows = allIssues;
        if (t != null && t.type() != ScanTask.Type.LIVE_PASSIVE) {
            String source = "Scan " + t.number;
            rows = new ArrayList<>();
            for (Issue i : allIssues) if (source.equals(i.source())) rows.add(i);
        }
        summaryModel.setIssues(rows);
        issuesModel.setIssues(rows);
        auditLogModel.setIssues(rows);
    }

    private void onIssuesChanged() {
        List<Issue> all = ctx == null ? new ArrayList<>() : ctx.issues();
        all.sort(java.util.Comparator.comparingInt(Issue::rank));
        allIssues = all;
        issuesStamp++;
        // The live audit task reflects the shared issue set.
        if (liveAudit != null) {
            int h = 0, m = 0, l = 0, i = 0;
            for (Issue is : all) switch (is.severity()) {
                case HIGH -> h++; case MEDIUM -> m++; case LOW -> l++; default -> i++;
            }
            liveAudit.setCounts(h, m, l, i);
            if (ctx != null) ctx.taskChanged(liveAudit);
        }
        refreshRight();
    }

    private void onIssueRowSelected(ListSelectionEvent e) {
        if (e.getValueIsAdjusting()) return;
        int viewRow = issuesTable.getSelectedRow();
        if (viewRow < 0 || viewRow >= issuesModel.getRowCount()) return;
        Issue is = issuesModel.issueAt(viewRow);
        if (is == null) return;

        issueDetail.setText(opentrap.ui.Advisory.renderCompact(is));
        issueDetail.setCaretPosition(0);

        // Findings on the same engine message now share one request/response
        // pair, so hand the viewer its own copy: the editors are read-only here,
        // but an action that ever mutated the shown message in place must not
        // reach back into the sibling findings' evidence.
        HttpMessage req = is.request() == null ? null : is.request().copy();
        HttpMessage resp = is.response() == null ? null : is.response().copy();
        if (issueRequest != null) {
            issueRequest.setMessage(is.service(), req);
        }
        if (issueResponse != null) {
            issueResponse.setMessage(is.service(), resp, req == null ? null : req.copy());
        }
    }


    // ------------------------------------------------------------- actions

    private void newScan() {
        // The scan wizard: scan type, URLs, configuration, login, resource pool.
        NewScanWizard w = new NewScanWizard(ctx, ctx == null ? null : ctx.frame());
        w.setVisible(true);
        if (!w.started()) return;
        // One job for every URL listed: a single crawl task and audit task.
        startSpiderFor(w.targetUrls(), w.crawlAndAudit(), w.config(), w.threads());
    }

    private void startSpider() {
        String def = "https://";
        // Default to the first in-scope host if scope is set.
        if (!ctx.scope().include().isEmpty()) {
            opentrap.core.Scope.Rule first = ctx.scope().include().get(0);
            if (first.isPrefix()) {
                def = first.prefix.contains("://") ? first.prefix : "https://" + first.prefix + "/";
            } else {
                String host = opentrap.core.Scope.display(first.host);
                if (!host.isBlank()) def = "https://" + host + "/";
            }
        }
        String url = (String) JOptionPane.showInputDialog(ctx.frame(),
                "URL to spider (the crawl stays within the target scope):", "Spider",
                JOptionPane.PLAIN_MESSAGE, null, null, def);
        if (url == null || url.trim().isEmpty()) return;
        startSpiderFor(url.trim());
    }

    /** Crawl the given URL. The spider button calls this directly (crawl only). */
    private void startSpiderFor(String url) {
        startSpiderFor(url, false, opentrap.engine.ScanConfig.crawlDefault(), 10);
    }

    private void startSpiderFor(String url, boolean thenAudit) {
        startSpiderFor(url, thenAudit, opentrap.engine.ScanConfig.crawlDefault(), 10);
    }

    private void startSpiderFor(String url, boolean thenAudit, opentrap.engine.ScanConfig cfg, int threads) {
        startSpiderFor(java.util.List.of(url), thenAudit, cfg, threads);
    }

    /**
     * Crawl every URL with the ZAP engine as one job, and when {@code thenAudit}
     * is set, audit what the crawl discovered. All the URLs share one crawl task
     * and one audit task, scanned one after another, so the thread setting is
     * applied once to the engine rather than multiplied per target. The engine is
     * required: detection is ZAP's job, so there is no home-grown fall-back
     * scanner. If the engine is not installed, scanning is unavailable until it
     * is fetched.
     */
    private void startSpiderFor(java.util.List<String> urls, boolean thenAudit,
                                opentrap.engine.ScanConfig cfg, int threads) {
        opentrap.engine.ZapEngine ze = zapEngine();
        if (ze == null || !ze.available()) {
            JOptionPane.showMessageDialog(ctx.frame(),
                    "The scan engine (OWASP ZAP) is not installed.\n"
                    + "Run ./fetch-zap.sh to add it, then try again.",
                    "Scan engine required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        // Parse every URL, add each host to scope, and collect the seeds.
        java.util.List<opentrap.engine.ZapScanner.Seed> seeds = new ArrayList<>();
        for (String raw : urls) {
            String url = raw.trim();
            if (url.isEmpty()) continue;
            HttpService svc = HttpService.parse(url);
            if (svc == null) {
                JOptionPane.showMessageDialog(ctx.frame(),
                        "Could not parse this URL, skipping it:\n" + url
                        + "\n\nUse the form https://host/path.",
                        "Scan", JOptionPane.WARNING_MESSAGE);
                continue;
            }
            String pathq = "/";
            try {
                java.net.URI u = java.net.URI.create(url);
                if (u.getRawPath() != null && !u.getRawPath().isEmpty()) pathq = u.getRawPath();
            } catch (Exception ignored) {}
            String seedUrl = svc.url() + (pathq.startsWith("/") ? pathq : "/" + pathq);
            if (!ctx.scope().isInScope(svc, pathq)) {
                ctx.scope().includeHost(svc);
                ctx.scopeChanged();
            }
            seeds.add(new opentrap.engine.ZapScanner.Seed(svc, seedUrl));
        }
        if (seeds.isEmpty()) return;

        // The card reads like the reference: what the job is, of what, and the
        // configuration it runs with, e.g. "Crawl and audit of host" over
        // "Crawl and audit - Lightweight".
        String first = seeds.get(0).service().host();
        String what = thenAudit ? "Crawl and audit" : "Crawl";
        String title = seeds.size() == 1 ? what + " of " + first
                : what + " of " + first + " +" + (seeds.size() - 1) + " more";
        opentrap.core.ScanTask task = new opentrap.core.ScanTask(
                opentrap.core.ScanTask.Type.LIVE_ACTIVE, title, what + " - " + cfg.name());
        task.setScope(seeds.size() == 1 ? seeds.get(0).service().url() : seeds.size() + " targets");
        ctx.addTask(task);
        taskConfigs.put(task, cfg);
        // Remember how to run this exact scan again, for the task's "Scan again".
        final java.util.List<String> againUrls = new ArrayList<>(urls);
        final boolean againAudit = thenAudit;
        final opentrap.engine.ScanConfig againCfg = cfg;
        final int againThreads = threads;
        rerun.put(task, () -> startSpiderFor(againUrls, againAudit, againCfg, againThreads));
        task.log("Starting scan engine (first run can take ~30s)...");
        ctx.taskChanged(task);
        final java.util.List<opentrap.engine.ZapScanner.Seed> fseeds = seeds;
        Thread zth = new Thread(() -> {
            try {
                ze.start();
                // Threads are set once here, not per target.
                try { ze.api().setSpiderThreads(threads); ze.api().setActiveThreadPerHost(threads); }
                catch (Exception ignored) {}
                task.log("Engine ready. Crawling " + fseeds.size() + " target(s)");
                ctx.taskChanged(task);
                new opentrap.engine.ZapScanner(ctx, ze)
                        .crawlAndAudit(fseeds, thenAudit, cfg, task);
            } catch (Exception ex) {
                task.log("Scan engine error: " + ex.getMessage());
                task.setState(opentrap.core.ScanTask.State.FINISHED);
                ctx.taskChanged(task);
            }
        }, "zap-scan");
        zth.setDaemon(true);
        zth.start();
    }

    // --------------------------------------------------------- table model

    /** Backs both the summary and issues tables from a shared issue list. */
    private static final class IssueTableModel extends AbstractTableModel {
        private final String[] columns;
        private final boolean full;   // the Issues tab: the full column set
        private List<Issue> rows = new ArrayList<>();
        private final java.text.DateFormat fmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM);
        private final java.text.DateFormat dateFmt = new java.text.SimpleDateFormat("HH:mm:ss dd MMM yyyy");

        IssueTableModel(String[] columns) {
            this.columns = columns;
            this.full = columns.length >= 9;
        }

        void setIssues(List<Issue> issues) {
            this.rows = issues;
            fireTableDataChanged();
        }

        Issue issueAt(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int c) { return columns[c]; }

        // Only the Comment column of the full Issues table is editable.
        @Override public boolean isCellEditable(int r, int c) { return full && c == 8; }
        @Override public void setValueAt(Object v, int r, int c) {
            if (full && c == 8) rows.get(r).setComment(v == null ? "" : v.toString());
        }

        @Override public Object getValueAt(int r, int c) {
            Issue i = rows.get(r);
            if (full) {
                return switch (c) {
                    case 0 -> fmt.format(new java.util.Date(i.time()));
                    case 1 -> i.source();
                    case 2 -> i.title();
                    case 3 -> i.host();
                    case 4 -> i.path();
                    case 5 -> i.param() == null ? "" : i.param();
                    case 6 -> i.severity().label();
                    case 7 -> i.confidence().label();
                    default -> i.comment();
                };
            }
            return switch (c) {
                case 0 -> i.title();
                case 1 -> i.host();
                case 2 -> dateFmt.format(new java.util.Date(i.time()));
                default -> "";
            };
        }

        Issue.Severity severityAt(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row).severity() : null;
        }

        Issue.Confidence confidenceAt(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row).confidence() : null;
        }
    }

    /** The Audit log: every finding in the order it was detected. */
    private static final class AuditLogModel extends AbstractTableModel {
        private final String[] cols = {"#", "Time", "Source", "Action", "Issue type",
                "Host", "Path", "Insertion point", "Severity", "Confidence"};
        private List<Issue> rows = new ArrayList<>();
        private final java.text.DateFormat fmt = new java.text.SimpleDateFormat("HH:mm:ss dd MMM yyyy");
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : String.class; }

        void setIssues(List<Issue> issues) {
            List<Issue> copy = new ArrayList<>(issues);
            copy.sort(java.util.Comparator.comparingLong(Issue::time));
            rows = copy;
            fireTableDataChanged();
        }

        Issue issueAt(int row) {
            return row >= 0 && row < rows.size() ? rows.get(row) : null;
        }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Issue i = rows.get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> fmt.format(new java.util.Date(i.time()));
                case 2 -> i.source();
                case 3 -> "Issue present";
                case 4 -> i.title();
                case 5 -> i.host();
                case 6 -> i.path();
                case 7 -> i.param() == null ? "" : i.param();
                case 8 -> i.severity().label();
                default -> i.confidence().label();
            };
        }
    }

    /** Live model for the crawl window's Logger: the engine's HTTP traffic. */
    private static final class ScanLogModel extends AbstractTableModel {
        /** Upper bound on live-log rows held in memory. A long scan can stream a
         *  huge amount of traffic; keeping only the most recent rows bounds the
         *  table's memory while still showing the live tail. */
        private static final int MAX_ROWS = 50_000;

        private final String[] cols = {"#", "Time", "Tool", "Method", "Host",
                "Path", "Query", "Status", "Length"};
        private final List<LogEntry> rows = new ArrayList<>();
        private final java.text.DateFormat fmt =
                java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM);

        void add(LogEntry e) {
            rows.add(e);
            fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            trim();
        }
        void addAll(List<LogEntry> es) {
            if (es.isEmpty()) return;
            int from = rows.size();
            rows.addAll(es);
            fireTableRowsInserted(from, rows.size() - 1);
            trim();
        }
        /** Drop the oldest rows once the cap is exceeded, so the log cannot grow
         *  without bound during a very long scan. */
        private void trim() {
            if (rows.size() <= MAX_ROWS) return;
            rows.subList(0, rows.size() - MAX_ROWS).clear();
            fireTableDataChanged();
        }
        /** Repaint existing rows in one event, for a batch of in-place updates. */
        void refreshRows() {
            if (!rows.isEmpty()) fireTableRowsUpdated(0, rows.size() - 1);
        }
        void updated(LogEntry e) {
            int i = rows.indexOf(e);
            if (i >= 0) fireTableRowsUpdated(i, i);
        }
        LogEntry at(int r) { return (r >= 0 && r < rows.size()) ? rows.get(r) : null; }

        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : String.class; }
        @Override public Object getValueAt(int r, int c) {
            LogEntry e = rows.get(r);
            return switch (c) {
                case 0 -> e.number();
                case 1 -> fmt.format(e.time());
                case 2 -> e.tool();
                case 3 -> e.request() == null ? "" : e.request().method();
                case 4 -> e.service() == null ? "" : e.service().host();
                case 5 -> e.request() == null ? "" : e.request().path();
                case 6 -> e.request() == null ? "" : e.request().query();
                case 7 -> e.status() == 0 ? "" : String.valueOf(e.status());
                default -> e.responseLen() == 0 ? "" : String.valueOf(e.responseLen());
            };
        }
    }
}
