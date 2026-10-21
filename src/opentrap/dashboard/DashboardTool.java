package opentrap.dashboard;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.Issue;
import opentrap.core.LogEntry;
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

    private static final Color CARD_BG      = Color.WHITE;
    private static final Color CARD_SEL_BG  = Ui.SELECTION;
    private static final Color CARD_BORDER  = Ui.GRID;
    private static final Color GREY_TEXT    = new Color(0x77, 0x77, 0x77);

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
    private ScanTask selected;

    // ---- right ----
    private final JLabel rightHeader = Ui.heading("");
    private final java.awt.CardLayout rightCards = new java.awt.CardLayout();
    private final JPanel rightBody = new JPanel(rightCards);
    private JTextArea crawlLog;
    private JLabel crawlStats;
    private JLabel crawlImage;
    private volatile byte[] latestShot;
    private final IssueTableModel summaryModel =
            new IssueTableModel(new String[]{"Issue type", "Host", "Severity", "Confidence"});
    private final IssueTableModel issuesModel =
            new IssueTableModel(new String[]{"Time", "Source", "Issue type", "Host", "Path",
                    "Insertion point", "Severity", "Confidence", "Comment"});
    private JTable summaryTable;
    private JTable issuesTable;

    private final JLabel cfgType   = Ui.label("");
    private final JLabel cfgScope  = Ui.label("");
    private final JLabel cfgConfig = Ui.label("");

    private final JLabel progRequests = Ui.label("");
    private final JLabel progItems    = Ui.label("");
    private final JLabel progIssues   = Ui.label("");

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
        ctx.addIssueListener(i -> SwingUtilities.invokeLater(this::onIssuesChanged));
        ctx.addTaskListener(t -> SwingUtilities.invokeLater(this::rebuildTasks));

        issueRequest  = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        issueResponse = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
        issueSplit.setTopComponent(issueRequest);
        issueSplit.setBottomComponent(issueResponse);

        // The one default task: a live passive audit capturing from the Proxy.
        // Scans the user starts add their own tasks on top of this.
        liveAudit = new ScanTask(ScanTask.Type.LIVE_PASSIVE,
                "Live audit from Proxy (all traffic)", "Audit checks - passive");
        ctx.addTask(liveAudit);

        // Live passive scanning: route in-scope proxy traffic through the engine
        // and poll its alerts into issues. On by default, like Burp's live audit.
        passiveScanner = new opentrap.engine.LivePassiveScanner(ctx);
        ctx.setLiveScanRouter(passiveScanner::routeFor);
        passiveScanner.setEnabled(true);

        rebuildTasks();
        onIssuesChanged();
    }

    @Override public void shutdown() {
        // The ZAP scan engine is shared and shuts itself down via its own hook.
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
        JButton spiderBtn = Ui.button("Spider");
        accentise(newScan);
        accentise(spiderBtn);
        newScan.addActionListener(e -> newScan());
        spiderBtn.addActionListener(e -> startSpider());

        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Ui.heading("Tasks"), BorderLayout.WEST);
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        btns.setOpaque(false);
        btns.add(newScan);
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

        rightHeader.setText("Dashboard");
        right.add(rightHeader, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Summary", buildSummaryTab());
        tabs.addTab("Issues", buildIssuesTab());
        tabs.addTab("Event log", buildEventLogTab());

        rightBody.add(tabs, "scan");
        rightBody.add(buildCrawlCard(), "crawl");
        right.add(rightBody, BorderLayout.CENTER);
        return right;
    }

    /** Crawl view: progress and the live list of URLs being visited. */
    private JComponent buildCrawlCard() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        crawlStats = Ui.bold("Crawling");
        crawlStats.setBorder(Ui.pad(2, 2, 4, 2));
        p.add(crawlStats, BorderLayout.NORTH);
        crawlLog = new JTextArea();
        crawlLog.setEditable(false);
        crawlLog.setFont(Ui.MONO);
        // Wrap long URLs instead of scrolling the view horizontally off-screen.
        crawlLog.setLineWrap(true);
        crawlLog.setWrapStyleWord(false);
        JPanel listWrap = new JPanel(new BorderLayout());
        listWrap.add(Ui.bold(" Requests discovered (live)"), BorderLayout.NORTH);
        listWrap.add(Ui.scroll(crawlLog), BorderLayout.CENTER);

        crawlImage = new JLabel("", SwingConstants.CENTER);
        crawlImage.setVerticalAlignment(SwingConstants.TOP);
        JPanel imgWrap = new JPanel(new BorderLayout());
        imgWrap.add(Ui.bold(" Rendered page (live)"), BorderLayout.NORTH);
        imgWrap.add(Ui.scroll(crawlImage), BorderLayout.CENTER);

        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listWrap, imgWrap);
        sp.setDividerSize(5);
        Ui.balance(sp, 0.40);
        p.add(sp, BorderLayout.CENTER);
        return p;
    }

    /** Update the live rendered-page image (called from the crawler on the EDT). */
    private void setCrawlScreenshot(byte[] png) {
        this.latestShot = png;
        if (crawlImage == null || png == null) return;
        try {
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (img == null) return;
            int w = Math.max(200, crawlImage.getWidth() > 0 ? crawlImage.getWidth() - 8 : 500);
            int sh = (int) (img.getHeight() * (w / (double) img.getWidth()));
            Image scaled = img.getScaledInstance(w, sh, Image.SCALE_SMOOTH);
            crawlImage.setIcon(new ImageIcon(scaled));
            crawlImage.setText("");
        } catch (Exception ignored) {}
    }

    private JComponent buildSummaryTab() {
        // Section 1: most serious vulnerabilities (all issues, by rank).
        summaryTable = new JTable(summaryModel);
        Ui.styleTable(summaryTable);
        Ui.distributeWidths(summaryTable, 220, 160, 90, 90);
        Ui.centerCells(summaryTable);
        Ui.severityColumn(summaryTable, 2);   // Severity column
        JScrollPane sumScroll = Ui.scroll(summaryTable);
        JPanel vulns = section("Most serious vulnerabilities found (live)", sumScroll);

        // Task configuration and progress: small, so they sit at the bottom and
        // leave the vulnerabilities table all the room it can take.
        JPanel cfg = section("Task configuration", Ui.vbox(
                labelled("Task type: ", cfgType),
                labelled("Scope: ", cfgScope),
                labelled("Configuration: ", cfgConfig)));
        JPanel prog = section("Task progress", Ui.vbox(
                progRequests, progItems, progIssues));

        JPanel bottom = new JPanel(new GridLayout(1, 2, 12, 0));
        bottom.setOpaque(false);
        bottom.add(cfg);
        bottom.add(prog);

        // The table expands with the window; the task info is a fixed strip below.
        JPanel holder = new JPanel(new BorderLayout(0, 10));
        holder.setOpaque(false);
        holder.setBorder(Ui.pad(2, 2, 2, 2));
        holder.add(vulns, BorderLayout.CENTER);
        holder.add(bottom, BorderLayout.SOUTH);
        return holder;
    }

    private JComponent buildIssuesTab() {
        issuesTable = new JTable(issuesModel);
        Ui.styleTable(issuesTable);
        Ui.distributeWidths(issuesTable, 95, 70, 190, 140, 130, 110, 80, 80, 110);
        Ui.severityColumn(issuesTable, 6);   // Severity column
        issuesTable.getSelectionModel().addListSelectionListener(this::onIssueRowSelected);

        issueDetail = new JEditorPane();
        issueDetail.setContentType("text/html");
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

    // ---------------------------------------------------------------- helpers

    private static void accentise(JButton b) {
        // Plain, like every other button in the app (no orange).
        b.setFocusPainted(false);
    }

    /** A bordered section with a bold heading above its content. */
    private static JPanel section(String title, JComponent content) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.GRID),
                Ui.pad(8, 8, 8, 8)));
        p.add(Ui.bold(title), BorderLayout.NORTH);
        content.setOpaque(false);
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private static JPanel labelled(String caption, JLabel value) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 1));
        p.setOpaque(false);
        p.add(Ui.bold(caption));
        p.add(value);
        return p;
    }

    // ----------------------------------------------------------- task cards

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
        refreshRight();
    }

    private void selectTask(ScanTask t) {
        selected = t;
        for (Map.Entry<ScanTask, TaskCard> e : cards.entrySet()) {
            e.getValue().setSelectedLook(e.getKey() == selected);
        }
        refreshRight();
    }

    /** A single task card in the left-hand list. */
    private final class TaskCard extends JPanel {
        private final ScanTask task;

        TaskCard(ScanTask task) {
            super(new BorderLayout(0, 4));
            this.task = task;
            setBorder(Theme.cardBorder());
            setMaximumSize(new Dimension(Integer.MAX_VALUE, getPreferredHeight()));

            JLabel title = Ui.bold(task.number + ". " + task.title());
            JLabel config = Ui.label(task.configuration());
            config.setForeground(GREY_TEXT);
            config.setFont(Ui.PLAIN.deriveFont(11f));

            JPanel head = new JPanel();
            head.setOpaque(false);
            head.setLayout(new BoxLayout(head, BoxLayout.Y_AXIS));
            title.setAlignmentX(LEFT_ALIGNMENT);
            config.setAlignmentX(LEFT_ALIGNMENT);
            head.add(title);
            head.add(config);

            boolean passive = task.type() == ScanTask.Type.LIVE_PASSIVE;
            boolean finished = task.state() == ScanTask.State.FINISHED;
            boolean running = task.state() == ScanTask.State.RUNNING;

            // The passive audit is a simple on/off capture stage, so it shows a
            // "Capturing" toggle and no bar. Active work (a crawl, or an audit)
            // keeps a progress bar that moves while it is doing something.
            JComponent progress = passive ? null : buildProgress();

            JPanel stateRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            stateRow.setOpaque(false);
            if (passive) {
                // On/off toggle for the continuous passive capture stage.
                if (finished) {
                    stateRow.add(Ui.label(task.stateLabel()));
                } else {
                    stateRow.add(Ui.label("Capturing"));
                    ToggleSwitch sw = new ToggleSwitch(running);
                    sw.setOnToggle(() -> {
                        task.setState(task.state() == ScanTask.State.RUNNING
                                ? ScanTask.State.PAUSED : ScanTask.State.RUNNING);
                        // The live audit toggle drives live passive scanning.
                        if (task == liveAudit && passiveScanner != null)
                            passiveScanner.setEnabled(task.state() == ScanTask.State.RUNNING);
                        ctx.taskChanged(task);
                    });
                    stateRow.add(sw);
                }
            } else {
                // Active task: state label + pause/resume.
                stateRow.add(Ui.label(task.stateLabel()));
                if (!finished) {
                    JButton toggle = Ui.button(running ? "pause" : "resume");
                    toggle.setFont(Ui.PLAIN.deriveFont(11f));
                    toggle.addActionListener(e -> {
                        task.setState(task.state() == ScanTask.State.RUNNING
                                ? ScanTask.State.PAUSED : ScanTask.State.RUNNING);
                        ctx.taskChanged(task);
                    });
                    stateRow.add(toggle);
                }
            }

            // Crawl tasks map pages rather than find issues: show crawl stats,
            // not issue badges.
            JPanel badges = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            badges.setOpaque(false);
            if (task.type() == ScanTask.Type.LIVE_ACTIVE) {
                JLabel stats = Ui.label(task.itemsDone() + " pages crawled, "
                        + Math.max(0, task.itemsTotal() - task.itemsDone()) + " queued");
                stats.setFont(Ui.PLAIN.deriveFont(11f));
                stats.setForeground(GREY_TEXT);
                badges.add(stats);
            } else {
                badges.add(badge(task.high(),   HIGH_COL));
                badges.add(badge(task.medium(), MEDIUM_COL));
                badges.add(badge(task.low(),    LOW_COL));
                badges.add(badge(task.info(),   INFO_COL));
            }

            JPanel body = new JPanel();
            body.setOpaque(false);
            body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
            head.setAlignmentX(LEFT_ALIGNMENT);
            stateRow.setAlignmentX(LEFT_ALIGNMENT);
            badges.setAlignmentX(LEFT_ALIGNMENT);
            body.add(head);
            if (progress != null) {
                progress.setAlignmentX(LEFT_ALIGNMENT);
                body.add(Box.createVerticalStrut(4));
                body.add(progress);
            }
            body.add(Box.createVerticalStrut(4));
            body.add(stateRow);
            body.add(Box.createVerticalStrut(2));
            body.add(badges);

            add(body, BorderLayout.CENTER);

            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override public void mousePressed(java.awt.event.MouseEvent e) { selectTask(task); }
            });

            setSelectedLook(task == selected);
        }

        private int getPreferredHeight() {
            return task.type() == ScanTask.Type.LIVE_PASSIVE ? 120 : 150;
        }

        private JComponent buildProgress() {
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setFont(Ui.PLAIN.deriveFont(11f));
            double p = task.progress();
            ScanTask.State st = task.state();
            if (p < 0) {
                // Continuous task (live passive audit, or a crawl). Its bar shows
                // a word, not a percentage, and "Finished" once it is done rather
                // than falling back to "Paused".
                boolean running = st == ScanTask.State.RUNNING;
                bar.setIndeterminate(running);
                bar.setStringPainted(true);
                String word = switch (st) {
                    case FINISHED -> "Finished";
                    case PAUSED   -> "Paused";
                    case RUNNING  -> task.type() == ScanTask.Type.LIVE_PASSIVE ? "Capturing" : "Crawling";
                };
                bar.setString(word);
            } else {
                bar.setIndeterminate(false);
                bar.setValue((int) Math.round(p * 100));
                bar.setStringPainted(true);
                bar.setString(st == ScanTask.State.FINISHED ? "Finished"
                        : (int) Math.round(p * 100) + "%");
            }
            return bar;
        }

        void setSelectedLook(boolean on) {
            setBackground(on ? CARD_SEL_BG : CARD_BG);
            setOpaque(true);
        }
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
            g2.setColor(on ? Ui.ACCENT : new Color(0xB8, 0xBC, 0xC2));
            g2.fillRoundRect(0, 1, w - 1, h - 3, h - 2, h - 2);
            int knob = h - 8;
            int x = on ? w - knob - 4 : 4;
            g2.setColor(Color.WHITE);
            g2.fillOval(x, 4, knob, knob);
            g2.dispose();
        }
    }

    /** A rounded, coloured issue-count badge with white text. */
    private static JComponent badge(int count, Color bg) {
        final String text = Integer.toString(count);
        JComponent c = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(bg);
                g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
                g2.setColor(Color.WHITE);
                g2.setFont(Ui.BOLD.deriveFont(11f));
                FontMetrics fm = g2.getFontMetrics();
                int tw = fm.stringWidth(text);
                int tx = (getWidth() - tw) / 2;
                int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(text, tx, ty);
                g2.dispose();
            }
            @Override public Dimension getPreferredSize() {
                FontMetrics fm = getFontMetrics(Ui.BOLD.deriveFont(11f));
                int w = Math.max(22, fm.stringWidth(text) + 12);
                return new Dimension(w, 18);
            }
            @Override public Dimension getMaximumSize() { return getPreferredSize(); }
        };
        return c;
    }

    // --------------------------------------------------------------- right

    private void refreshRight() {
        ScanTask t = selected;
        if (t == null) {
            rightCards.show(rightBody, "scan");
            rightHeader.setText("Dashboard");
            cfgType.setText("-");
            cfgScope.setText("-");
            cfgConfig.setText("-");
            progRequests.setText("Requests: 0");
            progItems.setText("Items: 0 of 0");
            progIssues.setText("Issues: 0");
            eventLog.setText("");
            return;
        }

        rightHeader.setText(t.number + ". " + t.title());

        if (t.type() == ScanTask.Type.LIVE_ACTIVE) {
            // Crawl task: show the crawl view, not the scan/issues view.
            rightCards.show(rightBody, "crawl");
            int queued = Math.max(0, t.itemsTotal() - t.itemsDone());
            crawlStats.setText(t.itemsDone() + " pages crawled, " + queued
                    + " queued, " + t.requests() + " requests");
            crawlLog.setText(String.join("\n", t.logLines()));
            crawlLog.setCaretPosition(crawlLog.getDocument().getLength());
            // Restore the latest rendered-page image for this task (so selecting
            // a crawl shows its last frame, not a blank panel).
            if (t.lastShot() != null) {
                setCrawlScreenshot(t.lastShot());
            } else {
                crawlImage.setIcon(null);
                crawlImage.setText("(waiting for first page…)");
            }
            return;
        }
        rightCards.show(rightBody, "scan");

        cfgType.setText(t.type().toString());
        cfgScope.setText(t.scope().isEmpty() ? "-" : t.scope());
        cfgConfig.setText(t.configuration());

        progRequests.setText("Requests: " + t.requests());
        progItems.setText("Items: " + t.itemsDone() + " of " + t.itemsTotal());
        progIssues.setText("Issues: high " + t.high() + ", medium " + t.medium()
                + ", low " + t.low() + ", info " + t.info());

        eventLog.setText(String.join("\n", t.logLines()));
        eventLog.setCaretPosition(eventLog.getDocument().getLength());
    }

    private void onIssuesChanged() {
        List<Issue> all = ctx == null ? new ArrayList<>() : ctx.issues();
        all.sort(java.util.Comparator.comparingInt(Issue::rank));
        summaryModel.setIssues(all);
        issuesModel.setIssues(all);
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

        if (issueRequest != null) {
            if (is.request() != null) issueRequest.setMessage(is.service(), is.request());
            else issueRequest.setMessage(is.service(), null);
        }
        if (issueResponse != null) {
            if (is.response() != null) issueResponse.setMessage(is.service(), is.response(), is.request());
            else issueResponse.setMessage(is.service(), null, is.request());
        }
    }


    // ------------------------------------------------------------- actions

    private void newScan() {
        // The Burp-style wizard: scan type, URLs, configuration, login, resource pool.
        NewScanWizard w = new NewScanWizard(ctx, ctx == null ? null : ctx.frame());
        w.setVisible(true);
        if (!w.started()) return;
        startSpiderFor(w.targetUrl(), w.crawlAndAudit(), w.crawlDepth(), w.crawlDuration(), w.threads());
    }

    private void startSpider() {
        String def = "https://";
        // Default to the first in-scope host if scope is set.
        if (!ctx.scope().include().isEmpty()) {
            String host = ctx.scope().include().get(0).host.replaceAll("[\\^$]", "");
            if (!host.isBlank()) def = "https://" + host + "/";
        }
        String url = (String) JOptionPane.showInputDialog(ctx.frame(),
                "URL to spider (the crawl stays within the target scope):", "Spider",
                JOptionPane.PLAIN_MESSAGE, null, null, def);
        if (url == null || url.trim().isEmpty()) return;
        startSpiderFor(url.trim());
    }

    /** Crawl the given URL. The spider button calls this directly (crawl only). */
    private static final int DEFAULT_CRAWL_DEPTH = 5;

    private void startSpiderFor(String url) { startSpiderFor(url, false, DEFAULT_CRAWL_DEPTH, 0, 10); }

    private void startSpiderFor(String url, boolean thenAudit) {
        startSpiderFor(url, thenAudit, DEFAULT_CRAWL_DEPTH, 0, 10);
    }

    private void startSpiderFor(String url, boolean thenAudit, int maxDepth, int maxDurationMin) {
        startSpiderFor(url, thenAudit, maxDepth, maxDurationMin, 10);
    }

    /**
     * Crawl the URL with the ZAP engine, and when {@code thenAudit} is set, audit
     * the endpoints the crawl discovered. The engine is required: detection is
     * ZAP's job, so there is no home-grown fall-back scanner. If the engine is not
     * installed, scanning is unavailable until it is fetched.
     */
    private void startSpiderFor(String url, boolean thenAudit, int maxDepth, int maxDurationMin, int threads) {
        HttpService svc = HttpService.parse(url.trim());
        if (svc == null) {
            JOptionPane.showMessageDialog(ctx.frame(),
                    "Could not parse that URL. Use the form https://host/path.",
                    "Scan", JOptionPane.WARNING_MESSAGE);
            return;
        }
        opentrap.engine.ZapEngine ze = zapEngine();
        if (ze == null || !ze.available()) {
            JOptionPane.showMessageDialog(ctx.frame(),
                    "The scan engine (OWASP ZAP) is not installed.\n"
                    + "Run ./fetch-zap.sh to add it, then try again.",
                    "Scan engine required", JOptionPane.WARNING_MESSAGE);
            return;
        }
        String pathq = "/";
        try {
            java.net.URI u = java.net.URI.create(url.trim());
            if (u.getRawPath() != null && !u.getRawPath().isEmpty()) pathq = u.getRawPath();
        } catch (Exception ignored) {}
        final HttpService seed = svc;
        final String seedUrl = seed.url() + (pathq.startsWith("/") ? pathq : "/" + pathq);
        // Keep the crawl within scope: include the seed host so a scheme fall-back
        // (https to http or the reverse) stays in scope.
        if (!ctx.scope().isInScope(seed, pathq)) {
            ctx.scope().includeHost(seed);
            ctx.scopeChanged();
        }
        opentrap.core.ScanTask task = new opentrap.core.ScanTask(
                opentrap.core.ScanTask.Type.LIVE_ACTIVE,
                "Crawl of " + seed.host(),
                thenAudit ? "Crawl then audit, within scope" : "Crawl within scope");
        task.setScope(seed.url());
        ctx.addTask(task);
        task.log("Starting scan engine (first run can take ~30s)...");
        ctx.taskChanged(task);
        Thread zth = new Thread(() -> {
            try {
                ze.start();
                try { ze.api().setSpiderThreads(threads); ze.api().setActiveThreadPerHost(threads); }
                catch (Exception ignored) {}
                task.log("Engine ready. Crawling " + seedUrl);
                ctx.taskChanged(task);
                // The audit, if requested, runs only after the crawl finishes, and
                // its own task card is created at that point by the scanner.
                new opentrap.engine.ZapScanner(ctx, ze.api())
                        .crawlAndAudit(seed, seedUrl, thenAudit, maxDepth, maxDurationMin, task);
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
        private final boolean full;   // the Issues tab: the full Burp column set
        private List<Issue> rows = new ArrayList<>();
        private final java.text.DateFormat fmt = java.text.DateFormat.getTimeInstance(java.text.DateFormat.MEDIUM);

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
                case 2 -> i.severity().label();
                case 3 -> i.confidence().label();
                default -> "";
            };
        }
    }
}
