package opentrap.sequencer;

import opentrap.core.Bytes;
import opentrap.core.HttpClient;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.BufferedReader;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sequencer: collects a sample of session tokens (by manual load or by live
 * capture of a repeated request) and performs real statistical randomness
 * analysis on them -- per-character Shannon entropy and the FIPS 140-2
 * bit-level test battery -- presenting results in a separate window.
 */
public class SequencerTool implements ToolModule {

    private SuiteContext ctx;

    private int analysisCounter = 0;
    private int captureCounter = 0;

    // ---- manual load state ----
    private final List<String> manualTokens = new ArrayList<>();
    private TokenTableModel manualModel;
    private JLabel manualLoaded, manualShortest, manualLongest;

    // ---- live capture state ----
    private volatile HttpService baseService;
    private volatile HttpMessage baseRequest;
    private volatile HttpMessage baseResponse;
    private JComboBox<String> cookieCombo;
    private JComboBox<String> formCombo;
    private JRadioButton rCookie, rForm, rManual;
    private JTextField startDelim, endDelim;
    private JTextField threadsField, throttleField;
    private JButton startCaptureButton;
    private JLabel captureStatus;
    private volatile boolean capturing;
    private final List<Thread> captureThreads = new ArrayList<>();
    private final List<String> liveTokens = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger liveRequests = new AtomicInteger();
    private final AtomicInteger liveErrors = new AtomicInteger();
    private AnalysisWindow liveWindow;

    // ---- options ----
    private JCheckBox optCount, optTransitions, optMonobit, optPoker, optRuns, optLongRun;
    private JCheckBox optChiSquare, optSerial, optCompression;

    // cached tab components
    private JComponent liveTab, manualTab, optionsTab;

    public SequencerTool() {
        optionsTab = buildOptions();
        manualTab = buildManualLoad();
        liveTab = buildLiveCapture();
    }

    @Override public String name() { return "sequencer"; }
    @Override public boolean alwaysTabbed() { return true; }

    @Override public Tab[] tabs() {
        return new Tab[] {
            new Tab("Live capture", liveTab),
            new Tab("Manual load", manualTab),
            new Tab("Sequencer settings", opentrap.ui.SettingsDialog.launcher(ctx, "Sequencer", "Sequencer settings",
                    "Which randomness tests run on a captured token set is chosen under "
                    + "Settings > Sequencer.")),
        };
    }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        opentrap.ui.SettingsDialog.register("Sequencer", () -> optionsTab);
        ctx.registerSink("sequencer", (svc, req, resp, cap) ->
            SwingUtilities.invokeLater(() -> receive(svc, req, resp)));
    }

    @Override public void shutdown() {
        capturing = false;
    }

    // ---- the requests sent here, listed for live capture ----

    private record Received(HttpService svc, HttpMessage req, HttpMessage resp) {}

    private JTable requestTable;
    private RequestListModel requestModel;

    private static final class RequestListModel extends javax.swing.table.AbstractTableModel {
        private final List<Received> rows = new ArrayList<>();
        private final String[] cols = {"#", "Host", "Request"};
        void add(Received r) { rows.add(r); fireTableRowsInserted(rows.size() - 1, rows.size() - 1); }
        void remove(int i) { if (i >= 0 && i < rows.size()) { rows.remove(i); fireTableDataChanged(); } }
        void clear() { rows.clear(); fireTableDataChanged(); }
        Received at(int i) { return (i >= 0 && i < rows.size()) ? rows.get(i) : null; }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : String.class; }
        @Override public Object getValueAt(int r, int c) {
            Received x = rows.get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> x.svc() == null ? "" : x.svc().url();
                default -> x.req() == null ? "" : x.req().startLine();
            };
        }
    }

    /** Make the chosen request the one a live capture will replay. */
    private void selectReceived(int viewRow) {
        Received r = viewRow < 0 ? null : requestModel.at(requestTable.convertRowIndexToModel(viewRow));
        baseService = r == null ? null : r.svc();
        baseRequest = r == null ? null : r.req();
        baseResponse = r == null ? null : r.resp();
        populateCombos(baseResponse);
        startCaptureButton.setEnabled(baseRequest != null && baseService != null);
    }

    // ================================================================
    //  options tab
    // ================================================================

    private JComponent buildOptions() {
        optCount       = Ui.check("character count", true);
        optTransitions = Ui.check("transitions", true);
        optMonobit     = Ui.check("FIPS monobit", true);
        optPoker       = Ui.check("FIPS poker", true);
        optRuns        = Ui.check("FIPS runs", true);
        optLongRun     = Ui.check("FIPS long runs", true);
        optChiSquare   = Ui.check("chi-square (bit distribution)", true);
        optSerial      = Ui.check("serial correlation (lag-1)", true);
        optCompression = Ui.check("compression", true);

        JPanel body = Ui.vbox(
            Ui.heading("Live capture"),
            Ui.form("Number of threads:", threadsField == null ? (threadsField = Ui.field("5", 6)) : threadsField,
                    "Throttle between requests (milliseconds):", throttleField == null ? (throttleField = Ui.field("0", 6)) : throttleField),
            Box.createVerticalStrut(15),
            Ui.heading("Character-level analysis"),
            optCount,
            optTransitions,
            Box.createVerticalStrut(15),
            Ui.heading("Bit-level analysis"),
            optMonobit,
            optPoker,
            optRuns,
            optLongRun,
            optChiSquare,
            optSerial,
            optCompression
        );
        body.setBorder(Ui.pad(12, 12, 12, 12));

        JPanel panel = new JPanel(new BorderLayout());
        panel.add(body, BorderLayout.NORTH);
        return Ui.scroll(panel);
    }

    // ================================================================
    //  manual load tab
    // ================================================================

    private JComponent buildManualLoad() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(Ui.pad(10, 10, 10, 10));

        // --- top: buttons + counters ---
        JButton load  = Ui.button("load");
        JButton paste = Ui.button("paste");
        JButton clear = Ui.button("clear");

        JPanel buttons = new JPanel(new GridLayout(1, 3, 5, 5));
        buttons.setOpaque(false);
        buttons.add(load);
        buttons.add(paste);
        buttons.add(clear);

        manualLoaded   = Ui.label("tokens loaded: 0");
        manualShortest = Ui.label("shortest: 0");
        manualLongest  = Ui.label("longest: 0");
        JPanel counters = Ui.vbox(manualLoaded, manualShortest, manualLongest);
        counters.setBorder(Ui.pad(10, 0, 10, 10));

        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        JPanel bwrap = new JPanel(new BorderLayout());
        bwrap.setOpaque(false);
        bwrap.add(buttons, BorderLayout.WEST);
        north.add(bwrap, BorderLayout.NORTH);
        north.add(counters, BorderLayout.CENTER);
        panel.add(north, BorderLayout.NORTH);

        // --- centre: token table ---
        manualModel = new TokenTableModel(manualTokens);
        JTable table = new JTable(manualModel);
        Ui.styleTable(table);
        table.setTableHeader(null);
        table.setFont(Ui.MONO);
        panel.add(Ui.scroll(table), BorderLayout.CENTER);

        // --- south: analyse now ---
        JButton analyse = Ui.button("analyse now");
        analyse.setFont(Ui.BOLD);
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.setBorder(Ui.pad(10, 0, 0, 0));
        south.add(analyse, BorderLayout.EAST);
        panel.add(south, BorderLayout.SOUTH);

        // --- behaviour ---
        load.addActionListener(e -> loadFromFile());
        paste.addActionListener(e -> pasteTokens());
        clear.addActionListener(e -> { manualTokens.clear(); refreshManual(); });
        analyse.addActionListener(e -> analyseManual());

        refreshManual();
        return panel;
    }

    private void loadFromFile() {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("load tokens");
        if (fc.showOpenDialog(ctx == null ? null : ctx.frame()) != JFileChooser.APPROVE_OPTION) return;
        List<String> loaded = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(fc.getSelectedFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (!line.isEmpty()) loaded.add(line);
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(panelAnchor(), "could not read file: " + ex.getMessage(),
                "error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        manualTokens.addAll(loaded);
        refreshManual();
    }

    private void pasteTokens() {
        try {
            Object data = Toolkit.getDefaultToolkit().getSystemClipboard()
                .getData(DataFlavor.stringFlavor);
            String text = String.valueOf(data);
            for (String line : Bytes.lines(text)) {
                if (!line.isEmpty()) manualTokens.add(line);
            }
            refreshManual();
        } catch (Exception ignored) {
        }
    }

    private void refreshManual() {
        manualModel.fireTableDataChanged();
        manualLoaded.setText("tokens loaded: " + manualTokens.size());
        manualShortest.setText("shortest: " + shortest(manualTokens));
        manualLongest.setText("longest: " + longest(manualTokens));
    }

    private void analyseManual() {
        if (manualTokens.size() < 100) {
            JOptionPane.showMessageDialog(panelAnchor(),
                "you must load at least 100 tokens", "error", JOptionPane.ERROR_MESSAGE);
            return;
        }
        analysisCounter++;
        String id = "[manual load analysis #" + analysisCounter + "]";
        AnalysisWindow w = new AnalysisWindow("sequencer " + id, false);
        w.setTokens(new ArrayList<>(manualTokens));
        w.setVisible(true);
    }

    private Component panelAnchor() {
        return ctx == null ? null : ctx.frame();
    }

    // ================================================================
    //  live capture tab
    // ================================================================

    private JComponent buildLiveCapture() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(Ui.pad(10, 10, 10, 10));

        // --- Select live capture request ---
        requestModel = new RequestListModel();
        requestTable = new JTable(requestModel);
        Ui.styleTable(requestTable);
        Ui.newestFirst(requestTable, 0);
        Ui.distributeWidths(requestTable, 40, 240, 700);
        requestTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        requestTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) selectReceived(requestTable.getSelectedRow());
        });
        requestTable.setToolTipText("find a request within the application which returns a token "
            + "whose randomness you want to analyse, and send it here");
        JScrollPane reqScroll = Ui.scroll(requestTable);
        reqScroll.setPreferredSize(new Dimension(10, 120));
        JButton remove = Ui.button("Remove", "remove the selected request");
        JButton clearReq = Ui.button("Clear", "remove every request");
        remove.addActionListener(e -> {
            int r = requestTable.getSelectedRow();
            if (r >= 0) requestModel.remove(requestTable.convertRowIndexToModel(r));
            selectReceived(requestTable.getSelectedRow());
        });
        clearReq.addActionListener(e -> { requestModel.clear(); selectReceived(-1); });
        JPanel reqRow = new JPanel(new BorderLayout(8, 0));
        reqRow.setOpaque(false);
        reqRow.add(Ui.buttonColumn(remove, clearReq), BorderLayout.WEST);
        reqRow.add(reqScroll, BorderLayout.CENTER);
        reqRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 130));

        startCaptureButton = Ui.primaryButton("Start live capture", "start sampling tokens from the selected request");
        startCaptureButton.setFont(Ui.BOLD);
        startCaptureButton.setEnabled(false);
        startCaptureButton.addActionListener(e -> toggleCapture());
        captureStatus = Ui.label(" ");
        JPanel startRow = Ui.row(startCaptureButton, captureStatus);

        JTextArea selectNote = Ui.note("Send requests here from other tools to configure a live "
            + "capture. Select the request to use, configure the other options below, then click "
            + "\"Start live capture\".");
        selectNote.setMaximumSize(new Dimension(Integer.MAX_VALUE, selectNote.getPreferredSize().height));
        JTextArea tokenNote = Ui.note("Select the location in the response where the token appears.");
        tokenNote.setMaximumSize(new Dimension(Integer.MAX_VALUE, tokenNote.getPreferredSize().height));

        buildCaptureOptions();   // the fields live on the Settings page; keep them created
        JPanel body = Ui.vbox(
            helpHeading("Select live capture request"),
            Box.createVerticalStrut(4),
            indent(selectNote),
            Box.createVerticalStrut(10),
            indent(reqRow),
            Box.createVerticalStrut(10),
            indent(startRow),
            Box.createVerticalStrut(22),
            rule(),
            Box.createVerticalStrut(14),
            helpHeading("Token location within response"),
            Box.createVerticalStrut(4),
            indent(tokenNote),
            Box.createVerticalStrut(10),
            indent(buildTokenLocation()));

        panel.add(body, BorderLayout.NORTH);
        return Ui.scroll(panel);
    }

    /** A bold section heading with the help mark before it, as the reference draws them. */
    private static JComponent helpHeading(String text) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        p.setOpaque(false);
        p.add(new JLabel(opentrap.ui.Glyphs.help(20, opentrap.ui.Theme.MUTED)));
        JLabel h = Ui.heading(text);
        h.setFont(new Font("Dialog", Font.BOLD, 15));
        p.add(h);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, p.getPreferredSize().height));
        return p;
    }

    private static JComponent indent(JComponent c) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(Ui.pad(0, 32, 0, 0));
        p.add(c, BorderLayout.CENTER);
        p.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height + 2));
        return p;
    }

    private static JComponent rule() {
        JSeparator s = new JSeparator();
        s.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        return s;
    }

    private JComponent buildTokenLocation() {
        rCookie = Ui.radio("Cookie:", false);
        rForm   = Ui.radio("Form field:", false);
        rManual = Ui.radio("Custom location:", true);
        ButtonGroup g = new ButtonGroup();
        g.add(rCookie); g.add(rForm); g.add(rManual);
        rCookie.setEnabled(false);
        rForm.setEnabled(false);

        cookieCombo = Ui.combo(new String[0]);
        formCombo   = Ui.combo(new String[0]);
        cookieCombo.setEnabled(false);
        formCombo.setEnabled(false);
        startDelim = Ui.field("", 14);
        endDelim   = Ui.field("", 14);
        startDelim.setToolTipText("the token starts after this expression");
        endDelim.setToolTipText("the token ends at this delimiter");
        JTextField customSummary = Ui.field("", 24);
        customSummary.setEditable(false);
        JButton configure = Ui.button("Configure", "set the expressions the token sits between");
        configure.addActionListener(e -> {
            JPanel f = Ui.form("Starts after:", startDelim, "Ends at:", endDelim);
            f.setBorder(Ui.pad(10, 10, 10, 10));
            if (JOptionPane.showConfirmDialog(this.liveTab, f, "Custom token location", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION)
                customSummary.setText(startDelim.getText().isEmpty() && endDelim.getText().isEmpty() ? "" : "after \"" + startDelim.getText() + "\" until \"" + endDelim.getText() + "\"");
        });
        JPanel custom = Ui.row(customSummary, configure);

        // The two choosers are as wide as the custom-location row, so the three
        // controls share one right edge as well as one left edge.
        int w = custom.getPreferredSize().width;
        cookieCombo.setPreferredSize(new Dimension(customSummary.getPreferredSize().width, cookieCombo.getPreferredSize().height));
        formCombo.setPreferredSize(new Dimension(customSummary.getPreferredSize().width, formCombo.getPreferredSize().height));
        JPanel form = Ui.form(rCookie, cookieCombo, rForm, formCombo, rManual, custom);
        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(form, BorderLayout.WEST);
        return panel;
    }

    private JComponent buildCaptureOptions() {
        if (threadsField == null) threadsField  = Ui.field("5", 6);
        if (throttleField == null) throttleField = Ui.field("0", 6);

        JPanel body = Ui.form(
            "Number of threads:", threadsField,
            "Throttle between requests (milliseconds):", throttleField);
        body.setBorder(Ui.pad(0, 16, 0, 0));

        JPanel panel = new JPanel(new BorderLayout());
        panel.setOpaque(false);
        panel.add(body, BorderLayout.WEST);
        return panel;
    }

    /** "send to sequencer" lands here: list the request and select it. */
    private void receive(HttpService svc, HttpMessage req, HttpMessage resp) {
        requestModel.add(new Received(svc, req, resp));
        int last = requestTable.convertRowIndexToView(requestModel.getRowCount() - 1);
        requestTable.setRowSelectionInterval(last, last);
    }

    private void populateCombos(HttpMessage resp) {
        List<String> cookies = new ArrayList<>();
        List<String> forms = new ArrayList<>();
        if (resp != null) {
            for (String line : resp.headerLines()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Set-Cookie")) {
                    String nv = line.substring(c + 1).trim();
                    int semi = nv.indexOf(';');
                    if (semi >= 0) nv = nv.substring(0, semi);
                    if (!nv.isEmpty()) cookies.add(nv.trim());
                }
            }
            Matcher m = INPUT_PATTERN.matcher(resp.bodyText());
            while (m.find()) {
                String nm = m.group(1);
                String val = m.group(2) == null ? "" : m.group(2);
                forms.add(nm + "=" + val);
            }
        }
        cookieCombo.setModel(new DefaultComboBoxModel<>(cookies.toArray(new String[0])));
        formCombo.setModel(new DefaultComboBoxModel<>(forms.toArray(new String[0])));

        boolean hasCookies = !cookies.isEmpty();
        boolean hasForms = !forms.isEmpty();
        rCookie.setEnabled(hasCookies);
        cookieCombo.setEnabled(hasCookies);
        rForm.setEnabled(hasForms);
        formCombo.setEnabled(hasForms);
    }

    private static final Pattern INPUT_PATTERN = Pattern.compile(
        "<input[^>]*\\bname\\s*=\\s*[\"']?([^\"'>\\s]+)[\"']?[^>]*?(?:\\bvalue\\s*=\\s*[\"']?([^\"'>]*)[\"']?)?",
        Pattern.CASE_INSENSITIVE);

    private void toggleCapture() {
        if (capturing) {
            stopCapture();
        } else {
            startCapture();
        }
    }

    private void startCapture() {
        if (baseRequest == null || baseService == null) return;
        liveTokens.clear();
        liveRequests.set(0);
        liveErrors.set(0);
        capturing = true;
        startCaptureButton.setText("Stop live capture");

        captureCounter++;
        String id = "[live capture #" + captureCounter + ": "
            + (baseService == null ? "?" : baseService.host()) + "]";
        liveWindow = new AnalysisWindow("sequencer " + id, true);
        liveWindow.setVisible(true);

        int threads = parseInt(threadsField.getText(), 5);
        final long throttle = parseInt(throttleField.getText(), 0);

        final HttpService svc = baseService;
        final HttpMessage req = baseRequest.copy();
        final boolean useCookie = rCookie.isSelected();
        final String cookieName = useCookie ? nameOf(stringOf(cookieCombo.getSelectedItem())) : null;
        final String start = startDelim.getText();
        final String end = endDelim.getText();

        captureThreads.clear();
        for (int i = 0; i < Math.max(1, threads); i++) {
            Thread t = new Thread(() -> captureLoop(svc, req, useCookie, cookieName, start, end, throttle),
                "sequencer-capture-" + i);
            t.setDaemon(true);
            captureThreads.add(t);
            t.start();
        }
        updateCaptureStatus();
    }

    private void captureLoop(HttpService svc, HttpMessage req, boolean useCookie,
                             String cookieName, String start, String end, long throttle) {
        HttpClient client = new HttpClient();
        HttpClient.Options opt = new HttpClient.Options();
        opt.updateContentLength = false;
        opt.session = ctx.sessions();
        opt.sessionTool = "sequencer";
        while (capturing) {
            HttpClient.Result r = client.send(svc, req, opt);
            liveRequests.incrementAndGet();
            if (r.ok()) {
                String token = extractToken(r.response, useCookie, cookieName, start, end);
                if (token != null && !token.isEmpty()) {
                    liveTokens.add(token);
                    onTokenCaptured();
                }
            } else {
                liveErrors.incrementAndGet();
            }
            if (throttle > 0) {
                try { Thread.sleep(throttle); } catch (InterruptedException e) { break; }
            }
        }
    }

    private String extractToken(HttpMessage resp, boolean useCookie,
                                String cookieName, String start, String end) {
        if (resp == null) return null;
        if (useCookie && cookieName != null && !cookieName.isEmpty()) {
            for (String line : resp.headerLines()) {
                int c = line.indexOf(':');
                if (c > 0 && line.substring(0, c).trim().equalsIgnoreCase("Set-Cookie")) {
                    String nv = line.substring(c + 1).trim();
                    int semi = nv.indexOf(';');
                    if (semi >= 0) nv = nv.substring(0, semi);
                    int eq = nv.indexOf('=');
                    if (eq > 0 && nv.substring(0, eq).trim().equals(cookieName)) {
                        return nv.substring(eq + 1).trim();
                    }
                }
            }
            return null;
        }
        // delimiter-based extraction
        String text = Bytes.str(resp.raw());
        int from = 0;
        if (start != null && !start.isEmpty()) {
            int s = text.indexOf(start);
            if (s < 0) return null;
            from = s + start.length();
        }
        int to;
        if (end != null && !end.isEmpty()) {
            to = text.indexOf(end, from);
            if (to < 0) return null;
        } else {
            to = text.length();
        }
        if (to <= from) return null;
        return text.substring(from, to);
    }

    private void onTokenCaptured() {
        final int count = liveTokens.size();
        SwingUtilities.invokeLater(() -> {
            updateCaptureStatus();
            if (liveWindow != null && (count % 20 == 0 || count < 20)) {
                liveWindow.setTokens(new ArrayList<>(liveTokens));
            }
        });
    }

    private void stopCapture() {
        capturing = false;
        startCaptureButton.setText("Start live capture");
        for (Thread t : captureThreads) t.interrupt();
        captureThreads.clear();
        if (liveWindow != null) liveWindow.setTokens(new ArrayList<>(liveTokens));
        updateCaptureStatus();
    }

    private void updateCaptureStatus() {
        captureStatus.setText("live capture (" + liveTokens.size() + " tokens)   requests: "
            + liveRequests.get() + "   errors: " + liveErrors.get());
    }

    // ================================================================
    //  analysis window
    // ================================================================

    private class AnalysisWindow extends JFrame {
        private final boolean live;
        private final JTextArea summaryArea = resultArea();
        private final JTextArea charArea = resultArea();
        private final JTextArea bitArea = resultArea();
        private List<String> tokens = new ArrayList<>();

        AnalysisWindow(String title, boolean live) {
            super(title);
            this.live = live;
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setSize(1000, 600);
            setLocationByPlatform(true);

            JTabbedPane tabs = new JTabbedPane();
            tabs.addTab("summary", Ui.scroll(summaryArea));
            tabs.addTab("character-level analysis", Ui.scroll(charArea));
            tabs.addTab("bit-level analysis", Ui.scroll(bitArea));
            setContentPane(tabs);
        }

        void setTokens(List<String> t) {
            this.tokens = t;
            recompute();
        }

        private void recompute() {
            final List<String> snapshot = new ArrayList<>(tokens);
            new Thread(() -> {
                String summary = buildSummary(snapshot);
                String chars = buildCharReport(snapshot);
                String bits = buildBitReport(snapshot);
                SwingUtilities.invokeLater(() -> {
                    summaryArea.setText(summary);
                    summaryArea.setCaretPosition(0);
                    charArea.setText(chars);
                    charArea.setCaretPosition(0);
                    bitArea.setText(bits);
                    bitArea.setCaretPosition(0);
                });
            }, "sequencer-analysis").start();
        }

        private String buildSummary(List<String> t) {
            StringBuilder sb = new StringBuilder();
            sb.append(live ? "live capture analysis\n" : "manual load analysis\n");
            sb.append("====================\n\n");
            sb.append("Sample\n");
            sb.append("  tokens analysed : ").append(t.size()).append('\n');
            sb.append("  shortest token  : ").append(shortest(t)).append('\n');
            sb.append("  longest token   : ").append(longest(t)).append("\n\n");

            double effective = effectiveEntropy(t);
            sb.append("Effective entropy\n");
            sb.append("  estimated bits of entropy : ").append(fmt(effective)).append("\n\n");

            sb.append("Overall result\n");
            sb.append("  quality grade : ").append(grade(effective)).append('\n');
            sb.append("  reliability   : ").append(reliability(t.size())).append("\n\n");

            sb.append("This grade is derived from the summed per-character-position Shannon\n");
            sb.append("entropy of the sample. See the character-level and bit-level tabs for\n");
            sb.append("the detailed statistics.\n");
            return sb.toString();
        }

        private String buildCharReport(List<String> t) {
            StringBuilder sb = new StringBuilder();
            sb.append("CHARACTER-LEVEL ANALYSIS\n========================\n\n");

            int maxLen = 0;
            for (String s : t) maxLen = Math.max(maxLen, s.length());
            double[] perPos = positionEntropies(t, maxLen);
            double total = 0;
            for (double d : perPos) total += d;

            sb.append("Effective entropy\n");
            sb.append("  summed effective entropy : ").append(fmt(total)).append(" bits\n");
            sb.append("  character positions       : ").append(maxLen).append("\n\n");

            if (optCount.isSelected()) {
                sb.append("Character count analysis - significance levels\n");
                sb.append("  per-position Shannon entropy (bits):\n");
                for (int p = 0; p < maxLen; p++) {
                    sb.append(String.format("    position %4d : %s%n", p, fmt(perPos[p])));
                }
                sb.append('\n');
            }

            if (optTransitions.isSelected()) {
                sb.append("Character transition analysis - significance levels\n");
                double[] trans = transitionEntropies(t, maxLen);
                for (int p = 0; p < trans.length; p++) {
                    sb.append(String.format("    pos %4d -> %-4d : %s bits%n", p, p + 1, fmt(trans[p])));
                }
                sb.append('\n');
            }

            sb.append("Character set\n");
            java.util.TreeSet<Character> set = new java.util.TreeSet<>();
            for (String s : t) for (int i = 0; i < s.length(); i++) set.add(s.charAt(i));
            sb.append("  distinct characters observed : ").append(set.size()).append('\n');
            double maxBits = set.size() <= 1 ? 0 : (Math.log(set.size()) / Math.log(2));
            sb.append("  maximum entropy per character : ").append(fmt(maxBits)).append(" bits\n");
            return sb.toString();
        }

        private String buildBitReport(List<String> t) {
            StringBuilder sb = new StringBuilder();
            sb.append("BIT-LEVEL ANALYSIS\n==================\n\n");

            boolean[] bits = toBits(t);
            sb.append("Bit conversion\n");
            sb.append("  usable bits of entropy : ").append(bits.length).append("\n\n");

            if (optMonobit.isSelected()) {
                sb.append(monobit(bits)).append('\n');
            }
            if (optPoker.isSelected()) {
                sb.append(poker(bits)).append('\n');
            }
            if (optRuns.isSelected()) {
                sb.append(runs(bits)).append('\n');
            }
            if (optLongRun.isSelected()) {
                sb.append(longRun(bits)).append('\n');
            }
            if (optChiSquare.isSelected()) {
                sb.append(chiSquare(bits)).append('\n');
            }
            if (optSerial.isSelected()) {
                sb.append(serialCorrelation(bits)).append('\n');
            }
            if (optCompression.isSelected()) {
                sb.append(compressionTest(t)).append('\n');
            }
            return sb.toString();
        }
    }

    // ---- additional randomness tests (chi-square, serial correlation, compression) ----

    /** Chi-square test of the 0/1 bit distribution (1 degree of freedom) with a p-value. */
    static String chiSquare(boolean[] bits) {
        int n = bits.length;
        if (n == 0) return "Chi-square (bit uniformity)\n  (no bits to test)\n";
        int ones = 0;
        for (boolean b : bits) if (b) ones++;
        double e = n / 2.0;
        double chi = Math.pow(ones - e, 2) / e + Math.pow((n - ones) - e, 2) / e;
        double p = erfc(Math.sqrt(chi / 2.0));
        return "Chi-square (bit uniformity)\n"
                + "  chi-square : " + fmt(chi) + "\n"
                + "  p-value    : " + fmt(p) + "\n"
                + "  result     : " + (p >= 0.01 ? "PASS" : "FAIL") + "\n";
    }

    /** Lag-1 serial correlation of the bit stream; near zero indicates no linear dependence. */
    static String serialCorrelation(boolean[] bits) {
        int n = bits.length;
        if (n < 2) return "Serial correlation (lag-1)\n  (insufficient data)\n";
        double sum = 0, sumSq = 0, prod = 0;
        for (int i = 0; i < n; i++) {
            double x = bits[i] ? 1 : 0;
            sum += x;
            sumSq += x * x;
            prod += x * (bits[(i + 1) % n] ? 1 : 0);
        }
        double den = n * sumSq - sum * sum;
        double scc = den == 0 ? 0 : (n * prod - sum * sum) / den;
        return "Serial correlation (lag-1)\n"
                + "  coefficient : " + fmt(scc) + "\n"
                + "  result      : " + (Math.abs(scc) < 0.10 ? "PASS" : "review") + " (near 0 is ideal)\n";
    }

    /** Compression ratio of the concatenated tokens; a ratio near or above 1.0 means high entropy. */
    static String compressionTest(List<String> tokens) {
        java.io.ByteArrayOutputStream raw = new java.io.ByteArrayOutputStream();
        for (String s : tokens) raw.writeBytes(s.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
        byte[] data = raw.toByteArray();
        if (data.length == 0) return "Compression\n  (no data)\n";
        int clen;
        try {
            java.io.ByteArrayOutputStream gz = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(gz)) { g.write(data); }
            clen = gz.size();
        } catch (Exception e) { return "Compression\n  (failed)\n"; }
        double ratio = (double) clen / data.length;
        return "Compression\n"
                + "  original : " + data.length + " bytes\n"
                + "  gzipped  : " + clen + " bytes\n"
                + "  ratio    : " + fmt(ratio) + "\n"
                + "  result   : " + (ratio >= 0.95 ? "PASS" : "review") + " (ratio near/above 1.0 = high entropy)\n";
    }

    /** Complementary error function (Abramowitz &amp; Stegun 7.1.26), for the chi-square p-value. */
    static double erfc(double x) {
        double z = Math.abs(x);
        double t = 1.0 / (1.0 + 0.5 * z);
        double r = t * Math.exp(-z * z - 1.26551223 + t * (1.00002368 + t * (0.37409196 + t * (0.09678418
                + t * (-0.18628806 + t * (0.27886807 + t * (-1.13520398 + t * (1.48851587
                + t * (-0.82215223 + t * 0.17087277)))))))));
        return x >= 0 ? r : 2.0 - r;
    }

    private static JTextArea resultArea() {
        JTextArea a = new JTextArea();
        a.setFont(Ui.MONO);
        a.setEditable(false);
        a.setText("collecting tokens...");
        return a;
    }

    // ================================================================
    //  statistics
    // ================================================================

    /** Summed per-position Shannon entropy of the character distribution. */
    private static double effectiveEntropy(List<String> tokens) {
        int maxLen = 0;
        for (String s : tokens) maxLen = Math.max(maxLen, s.length());
        double total = 0;
        for (double d : positionEntropies(tokens, maxLen)) total += d;
        return total;
    }

    private static double[] positionEntropies(List<String> tokens, int maxLen) {
        double[] out = new double[maxLen];
        for (int p = 0; p < maxLen; p++) {
            Map<Character, Integer> counts = new LinkedHashMap<>();
            int total = 0;
            for (String s : tokens) {
                if (p < s.length()) {
                    char c = s.charAt(p);
                    counts.merge(c, 1, Integer::sum);
                    total++;
                }
            }
            out[p] = shannon(counts.values(), total);
        }
        return out;
    }

    /** Entropy of the (char at p, char at p+1) joint distribution, minus H(p). */
    private static double[] transitionEntropies(List<String> tokens, int maxLen) {
        int n = Math.max(0, maxLen - 1);
        double[] out = new double[n];
        for (int p = 0; p < n; p++) {
            Map<String, Integer> counts = new LinkedHashMap<>();
            int total = 0;
            for (String s : tokens) {
                if (p + 1 < s.length()) {
                    counts.merge("" + s.charAt(p) + s.charAt(p + 1), 1, Integer::sum);
                    total++;
                }
            }
            out[p] = shannon(counts.values(), total);
        }
        return out;
    }

    private static double shannon(Iterable<Integer> counts, int total) {
        if (total <= 0) return 0;
        double h = 0;
        for (int c : counts) {
            if (c <= 0) continue;
            double pr = (double) c / total;
            h -= pr * (Math.log(pr) / Math.log(2));
        }
        return h;
    }

    private static boolean[] toBits(List<String> tokens) {
        // cap the stream at 20000 bits for FIPS-style tests, but build from all tokens
        int cap = 20000;
        List<Boolean> acc = new ArrayList<>();
        outer:
        for (String s : tokens) {
            byte[] b = Bytes.bytes(s);
            for (byte x : b) {
                for (int bit = 7; bit >= 0; bit--) {
                    acc.add(((x >> bit) & 1) == 1);
                    if (acc.size() >= cap) break outer;
                }
            }
        }
        boolean[] out = new boolean[acc.size()];
        for (int i = 0; i < out.length; i++) out[i] = acc.get(i);
        return out;
    }

    private static String monobit(boolean[] bits) {
        int n = bits.length;
        int ones = 0;
        for (boolean b : bits) if (b) ones++;
        double mean = n / 2.0;
        double sd = Math.sqrt(n) / 2.0;
        double z = sd == 0 ? 0 : (ones - mean) / sd;
        boolean pass = n > 0 && Math.abs(z) < 3.9;
        StringBuilder sb = new StringBuilder();
        sb.append("FIPS monobit test - significance levels\n");
        sb.append("  Test description\n");
        sb.append("    counts the number of 1 bits in the stream (expected ~ n/2)\n");
        sb.append("  Anomalies\n");
        sb.append("    ones = ").append(ones).append(" of ").append(n)
          .append("   z = ").append(fmt(z)).append('\n');
        sb.append("  FIPS result : ").append(pass ? "PASS" : "FAIL").append('\n');
        return sb.toString();
    }

    private static String poker(boolean[] bits) {
        int m = bits.length / 4;
        int[] freq = new int[16];
        for (int i = 0; i < m; i++) {
            int v = 0;
            for (int j = 0; j < 4; j++) v = (v << 1) | (bits[i * 4 + j] ? 1 : 0);
            freq[v]++;
        }
        double sum = 0;
        for (int f : freq) sum += (double) f * f;
        double x = m == 0 ? 0 : (16.0 / m) * sum - m;
        boolean pass = m > 0 && x > 2.16 && x < 46.17;
        StringBuilder sb = new StringBuilder();
        sb.append("FIPS poker test - significance levels\n");
        sb.append("  Test description\n");
        sb.append("    chi-square over the sixteen 4-bit patterns across ").append(m).append(" groups\n");
        sb.append("  Anomalies\n");
        sb.append("    X = ").append(fmt(x)).append("   (FIPS interval 2.16 .. 46.17)\n");
        sb.append("  FIPS result : ").append(pass ? "PASS" : "FAIL").append('\n');
        return sb.toString();
    }

    private static final int[][] RUN_INTERVALS = {
        {2315, 2685}, {1114, 1386}, {527, 723}, {240, 384}, {103, 209}, {103, 209}
    };

    private static String runs(boolean[] bits) {
        int[] runs0 = new int[7];
        int[] runs1 = new int[7];
        int i = 0;
        int n = bits.length;
        while (i < n) {
            boolean v = bits[i];
            int len = 1;
            while (i + len < n && bits[i + len] == v) len++;
            int bucket = Math.min(len, 6);
            if (v) runs1[bucket]++; else runs0[bucket]++;
            i += len;
        }
        // compare against FIPS intervals only when the stream is close to 20000 bits
        boolean checkIntervals = n >= 18000 && n <= 22000;
        boolean pass = true;
        StringBuilder sb = new StringBuilder();
        sb.append("FIPS runs test - significance levels\n");
        sb.append("  Test description\n");
        sb.append("    counts runs of consecutive identical bits, by length\n");
        sb.append("  Anomalies\n");
        sb.append(String.format("    %-8s %8s %8s%n", "length", "runs-0", "runs-1"));
        for (int len = 1; len <= 6; len++) {
            int c0 = runs0[len];
            int c1 = runs1[len];
            String flag = "";
            if (checkIntervals) {
                int[] iv = RUN_INTERVALS[len - 1];
                boolean ok = c0 >= iv[0] && c0 <= iv[1] && c1 >= iv[0] && c1 <= iv[1];
                if (!ok) { pass = false; flag = "  <-- outside " + iv[0] + ".." + iv[1]; }
            }
            String label = len == 6 ? "6+" : String.valueOf(len);
            sb.append(String.format("    %-8s %8d %8d%s%n", label, c0, c1, flag));
        }
        if (!checkIntervals) {
            sb.append("    (FIPS pass/fail intervals apply to a 20000-bit stream; "
                + "sample size ").append(n).append(")\n");
        }
        sb.append("  FIPS result : ").append(checkIntervals ? (pass ? "PASS" : "FAIL") : "n/a").append('\n');
        return sb.toString();
    }

    private static String longRun(boolean[] bits) {
        int maxRun = 0;
        int i = 0;
        int n = bits.length;
        while (i < n) {
            boolean v = bits[i];
            int len = 1;
            while (i + len < n && bits[i + len] == v) len++;
            maxRun = Math.max(maxRun, len);
            i += len;
        }
        boolean pass = maxRun < 26;
        StringBuilder sb = new StringBuilder();
        sb.append("FIPS long run test - significance levels\n");
        sb.append("  Test description\n");
        sb.append("    a long run is any run of length 26 or greater; none are permitted\n");
        sb.append("  Anomalies\n");
        sb.append("    longest run observed : ").append(maxRun).append('\n');
        sb.append("  FIPS result : ").append(pass ? "PASS" : "FAIL").append('\n');
        return sb.toString();
    }

    // ================================================================
    //  grading
    // ================================================================

    private static String grade(double bits) {
        if (bits < 1)   return "extremely poor";
        if (bits < 8)   return "very poor";
        if (bits < 20)  return "poor";
        if (bits < 40)  return "reasonable";
        if (bits < 64)  return "good";
        if (bits < 96)  return "very good";
        return "excellent";
    }

    private static String reliability(int sampleSize) {
        if (sampleSize < 100)   return "very poor";
        if (sampleSize < 300)   return "poor";
        if (sampleSize < 1000)  return "reasonable";
        if (sampleSize < 5000)  return "good";
        if (sampleSize < 20000) return "very good";
        return "FIPS-compliant";
    }

    // ================================================================
    //  helpers
    // ================================================================

    private static int shortest(List<String> t) {
        if (t.isEmpty()) return 0;
        int m = Integer.MAX_VALUE;
        for (String s : t) m = Math.min(m, s.length());
        return m;
    }

    private static int longest(List<String> t) {
        int m = 0;
        for (String s : t) m = Math.max(m, s.length());
        return m;
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return def; }
    }

    private static String fmt(double d) {
        return String.format("%.4f", d);
    }

    private static String stringOf(Object o) {
        return o == null ? "" : o.toString();
    }

    private static String nameOf(String nameValue) {
        int eq = nameValue.indexOf('=');
        return eq >= 0 ? nameValue.substring(0, eq).trim() : nameValue.trim();
    }

    // ================================================================
    //  token table model
    // ================================================================

    private static class TokenTableModel extends AbstractTableModel {
        private final List<String> data;
        TokenTableModel(List<String> data) { this.data = data; }
        @Override public int getRowCount() { return data.size(); }
        @Override public int getColumnCount() { return 1; }
        @Override public String getColumnName(int c) { return ""; }
        @Override public Object getValueAt(int r, int c) { return data.get(r); }
        @Override public boolean isCellEditable(int r, int c) { return false; }
    }
}
