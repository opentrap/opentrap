package opentrap.dashboard;

import opentrap.engine.ScanConfig;
import opentrap.engine.ScanConfigStore;
import opentrap.ui.Glyphs;
import opentrap.ui.OptionsPage;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The scan configuration step shared by the New scan wizard and the live task
 * dialog: a preset chooser (Lightweight / Fast / Balanced / Deep / Custom) with
 * Save and Load, and, for Custom, a tree of configuration pages: crawl
 * optimization, limits, login functions, error handling and project overrides
 * under "Crawl configuration"; audit behaviour, scan checks, JavaScript
 * analysis and insertion points under "Audit configuration". The controls that
 * drive the engine map onto {@link ScanConfig}; the rest are kept with the
 * configuration so a saved one reloads exactly as it was.
 */
public final class ScanConfigPanel extends JPanel {

    /** Which trees to show. */
    public enum Mode { CRAWL_AND_AUDIT, CRAWL, AUDIT, PASSIVE_CRAWL }

    public record Preset(String name, String description) {}
    public static final List<Preset> PRESETS = List.of(
            new Preset("Lightweight", "Gain fast feedback on a site's security. Will complete within 15 minutes."),
            new Preset("Fast", "Greater coverage while keeping scan times low. Generally completes within one hour."),
            new Preset("Balanced", "Provides a balance between coverage and speed. Generally completes within a few hours."),
            new Preset("Deep", "Achieve comprehensive coverage. Scanning time depends on the target site's size and complexity."),
            new Preset("Custom", "Configure the default scan settings to meet your needs."));

    private final Mode mode;
    private final JComboBox<Preset> preset = new JComboBox<>(PRESETS.toArray(new Preset[0]));
    private final JPanel custom = new JPanel(new BorderLayout(8, 0));
    private final CardLayout pageCards = new CardLayout();
    private final JPanel pages = new JPanel(pageCards);
    private final Map<String, JComponent> pageByName = new LinkedHashMap<>();

    // ---- Crawl optimization
    private final JComboBox<String> crawlStrategy = Ui.combo(new String[]{"Fastest", "Faster", "Normal", "More complete", "Most complete"});
    private final JComboBox<String> crawlSpeed = Ui.combo(new String[]{"Fast", "Normal", "Slow"});
    private final JCheckBox submitForms = Ui.check("Submit forms while crawling", true);
    private final JCheckBox crawlDynamic = Ui.check("Use dynamic analysis (drive a browser to crawl JavaScript applications)", true);
    // ---- Crawl limits
    private final JSpinner maxDepth = spin(10, 0, 100, 1);
    private final JSpinner maxCrawlMin = spin(0, 0, 1440, 5);
    private final JSpinner maxLocations = spin(0, 0, 100000, 100);
    private final JSpinner maxRequests = spin(0, 0, 1000000, 1000);
    private final JSpinner ajaxDepth = spin(10, 0, 100, 1);
    private final JSpinner ajaxStates = spin(0, 0, 10000, 10);
    // ---- Login functions
    private final JCheckBox selfRegister = Ui.check("Attempt to self-register a user", true);
    private final JCheckBox triggerFailures = Ui.check("Trigger login failures", true);
    private final JCheckBox useRecorded = Ui.check("Use recorded login sequences", false);
    // ---- Handling application errors during crawl
    private final JSpinner crawlTimeouts = spin(2, 1, 100, 1);
    private final JSpinner crawlErrors = spin(5, 1, 100, 1);
    private final JSpinner crawlErrorPct = spin(50, 0, 100, 5);
    private final JSpinner crawlRetries = spin(1, 0, 10, 1);
    // ---- Crawl project option overrides
    private final JCheckBox overrideProject = Ui.check("Override project settings for this crawl", false);
    private final JCheckBox ovFollowRedirects = Ui.check("Follow redirections", true);
    private final JCheckBox ovKeepAlive = Ui.check("Use HTTP/1 keep-alive", true);
    private final JCheckBox ovHttp2 = Ui.check("Default to HTTP/2 if the server supports it", true);
    // ---- Audit behaviour
    private final JComboBox<String> auditSpeed = Ui.combo(new String[]{"Fast", "Normal", "Thorough"});
    private final JComboBox<String> auditAccuracy = Ui.combo(new String[]{"Minimize false negatives", "Normal", "Minimize false positives"});
    private final JCheckBox skipIneffective = Ui.check("Skip checks unlikely to be effective", true);
    private final JCheckBox maintainSession = Ui.check("Automatically maintain session (doesn't apply to audit-only scans)", true);
    private final JCheckBox followRedirects = Ui.check("Follow redirections when necessary", true);
    private final JCheckBox parallelPhases = Ui.check("Allow crawl and audit phases to run in parallel", true);
    private final JSpinner maxScanMin = spin(0, 0, 10080, 10);
    private final JCheckBox consolidatePassive = Ui.check("Consolidate frequently occurring passive issues", true);
    private final JSpinner connectTimeout = spin(10, 1, 600, 1);
    private final JSpinner requestTimeout = spin(120, 1, 3600, 10);
    private final JSpinner failChecks = spin(2, 1, 100, 1);
    private final JSpinner failPoints = spin(2, 1, 100, 1);
    private final JSpinner failItems = spin(60, 1, 10000, 10);
    private final JCheckBox failPctOn = Ui.check("", false);
    private final JSpinner failPct = spin(10, 0, 100, 5);
    private final JSpinner followUpPasses = spin(1, 0, 10, 1);
    // ---- JavaScript analysis
    private final JCheckBox jsDynamic = Ui.check("Use dynamic analysis techniques", true);
    private final JCheckBox jsStatic = Ui.check("Use static analysis techniques", false);
    private final JCheckBox jsMissing = Ui.check("Make requests for missing site resources", true);
    private final JCheckBox jsOutOfScope = Ui.check("Fetch previously undiscovered resources and data from out-of-scope hosts", true);
    private final JSpinner jsDynamicSecs = spin(30, 1, 3600, 5);
    private final JSpinner jsStaticSecs = spin(30, 1, 3600, 5);
    // ---- Insertion points
    private final JCheckBox ipQuery = Ui.check("URL parameter values", true);
    private final JCheckBox ipBody = Ui.check("Body parameter values", true);
    private final JCheckBox ipCookie = Ui.check("Cookie parameter values", true);
    private final JCheckBox ipName = Ui.check("Parameter name", true);
    private final JCheckBox ipHeaders = Ui.check("HTTP headers", true);
    private final JCheckBox ipEntireBody = Ui.check("Entire body (for relevant content types)", true);
    private final JCheckBox ipFilename = Ui.check("URL path filename", true);
    private final JCheckBox ipFolders = Ui.check("URL path folders", true);
    private final JCheckBox rpcMultipart = Ui.check("Multipart", true);
    private final JCheckBox rpcXml = Ui.check("XML", true);
    private final JCheckBox rpcJson = Ui.check("JSON", true);
    private final JCheckBox rpcGwt = Ui.check("GWT", true);
    private final JCheckBox rpcOdata = Ui.check("OData", true);
    private final JCheckBox nested = Ui.check("Use nested insertion points", true);
    private final JSpinner maxPoints = spin(30, 1, 1000, 5);
    private final JCheckBox[] relocate = {
            Ui.check("URL to body", false), Ui.check("URL to cookie", false), Ui.check("Body to URL", false),
            Ui.check("Body to cookie", false), Ui.check("Cookie to URL", false), Ui.check("Cookie to body", false)};
    private final SkipTable skipInjection = new SkipTable(defaultSkipInjection());
    private final SkipTable skipAll = new SkipTable(defaultSkipAll());
    // ---- Scan checks
    private final ChecksTable checks = new ChecksTable();
    // ---- Live passive crawl
    private final JCheckBox pcLinks = Ui.check("Links", true);
    private final JCheckBox pcForms = Ui.check("Form submissions", false);
    private final JCheckBox pcEverything = Ui.check("Everything", false);
    private final JCheckBox pcItself = Ui.check("The item itself", true);
    private final JCheckBox pcSameDomain = Ui.check("Items on the same domain", true);
    private final JCheckBox pcInScope = Ui.check("URLs in scope with", true);
    private final JRadioButton pcSuiteScope = Ui.radio("Suite scope", true);
    private final JRadioButton pcCustomScope = Ui.radio("Custom scope", false);

    public ScanConfigPanel(Mode mode) {
        super(new BorderLayout(0, 10));
        this.mode = mode;
        setOpaque(false);

        // Header row: Save / Load on the right, preset combo beneath the note.
        JButton save = Ui.button("Save", "save this configuration to the library");
        JButton load = Ui.button("Load", "load a configuration from the library");
        save.addActionListener(e -> save());
        load.addActionListener(e -> load());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(save); actions.add(load);

        preset.setFont(Ui.PLAIN);
        preset.setRenderer(new PresetRenderer());
        preset.setMaximumRowCount(6);
        preset.setPreferredSize(new Dimension(520, 44));
        preset.addActionListener(e -> onPreset());

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(Ui.note(mode == Mode.PASSIVE_CRAWL
                ? "Select how the live passive crawl adds items to the site map."
                : "Select the scan configuration you want to run. Choose Custom to adjust every setting."), BorderLayout.CENTER);
        head.add(actions, BorderLayout.EAST);
        top.add(head, BorderLayout.NORTH);
        JPanel presetRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        presetRow.setOpaque(false);
        presetRow.add(preset);
        if (mode != Mode.PASSIVE_CRAWL) top.add(presetRow, BorderLayout.CENTER);
        add(top, BorderLayout.NORTH);

        // The tree + pages.
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("config");
        if (mode == Mode.PASSIVE_CRAWL) {
            DefaultMutableTreeNode pc = node(root, "Live passive crawl configuration");
            leaf(pc, "Live passive crawl", passiveCrawlPage());
        } else {
            if (mode != Mode.AUDIT) {
                DefaultMutableTreeNode crawl = node(root, "Crawl configuration");
                leaf(crawl, "Crawl optimization", crawlOptimizationPage());
                leaf(crawl, "Crawl limits", crawlLimitsPage());
                leaf(crawl, "Login functions", loginFunctionsPage());
                leaf(crawl, "Handling application errors during crawl", crawlErrorsPage());
                leaf(crawl, "Crawl project option overrides", crawlOverridesPage());
            }
            if (mode != Mode.CRAWL) {
                DefaultMutableTreeNode audit = node(root, "Audit configuration");
                leaf(audit, "Audit behaviour", auditBehaviourPage());
                leaf(audit, "Scan checks", checks);
                leaf(audit, "JavaScript analysis", javascriptPage());
                leaf(audit, "Insertion points strategy", insertionPointsPage());
            }
        }
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Ui.PLAIN);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);
        tree.addTreeSelectionListener(e -> {
            Object o = tree.getLastSelectedPathComponent();
            if (o instanceof DefaultMutableTreeNode n && pageByName.containsKey(String.valueOf(n.getUserObject())))
                pageCards.show(pages, String.valueOf(n.getUserObject()));
        });
        JScrollPane ts = Ui.scroll(tree);
        ts.setPreferredSize(new Dimension(250, 10));
        pages.setOpaque(false);
        custom.setOpaque(false);
        custom.add(ts, BorderLayout.WEST);
        custom.add(pages, BorderLayout.CENTER);
        add(custom, BorderLayout.CENTER);
        // Select the first leaf.
        for (int i = 0; i < tree.getRowCount(); i++) {
            Object o = tree.getPathForRow(i).getLastPathComponent();
            if (o instanceof DefaultMutableTreeNode n && n.isLeaf()) { tree.setSelectionRow(i); break; }
        }

        preset.setSelectedIndex(mode == Mode.PASSIVE_CRAWL ? 4 : 2);   // Balanced, or Custom for passive crawl
        onPreset();
    }

    private DefaultMutableTreeNode node(DefaultMutableTreeNode parent, String name) {
        DefaultMutableTreeNode n = new DefaultMutableTreeNode(name);
        parent.add(n);
        return n;
    }

    private void leaf(DefaultMutableTreeNode parent, String name, JComponent page) {
        parent.add(new DefaultMutableTreeNode(name));
        pageByName.put(name, page);
        pages.add(page, name);
    }

    private static JSpinner spin(int v, int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(v, min, max, step));
        s.setFont(Ui.PLAIN);
        s.setPreferredSize(new Dimension(80, s.getPreferredSize().height));
        return s;
    }

    private static JPanel row(Component... c) { return Ui.row(c); }

    // ---- pages ----

    private JComponent crawlOptimizationPage() {
        OptionsPage p = new OptionsPage();
        p.section("Crawl optimization",
                Ui.note("How the crawler explores the site: how hard it tries to find every path, and how fast it moves."),
                OptionsPage.labelRow("Crawl strategy:", crawlStrategy),
                OptionsPage.labelRow("Crawl speed:", crawlSpeed),
                submitForms, crawlDynamic);
        crawlStrategy.setSelectedItem("Normal");
        crawlSpeed.setSelectedItem("Normal");
        return p;
    }

    private JComponent crawlLimitsPage() {
        OptionsPage p = new OptionsPage();
        p.section("Crawl limits",
                Ui.note("Stop the crawl when any of these limits is reached. 0 means no limit."),
                OptionsPage.fieldRow("Maximum link depth:", asField(maxDepth), ""),
                OptionsPage.fieldRow("Maximum crawl time:", asField(maxCrawlMin), "minutes"),
                OptionsPage.fieldRow("Maximum unique locations discovered:", asField(maxLocations), ""),
                OptionsPage.fieldRow("Maximum request count:", asField(maxRequests), ""));
        p.section("Dynamic analysis limits",
                OptionsPage.fieldRow("Maximum click depth:", asField(ajaxDepth), ""),
                OptionsPage.fieldRow("Maximum UI states explored:", asField(ajaxStates), ""));
        return p;
    }

    private JComponent loginFunctionsPage() {
        OptionsPage p = new OptionsPage();
        p.section("Login functions",
                Ui.note("What the crawler does when it meets a login or registration form."),
                selfRegister, triggerFailures, useRecorded);
        return p;
    }

    private JComponent crawlErrorsPage() {
        OptionsPage p = new OptionsPage();
        p.section("Handling application errors during crawl",
                Ui.note("When the application stops answering properly, pause rather than flood it."),
                row(Ui.label("If"), crawlTimeouts, Ui.label("consecutive requests time out, pause the crawl")),
                row(Ui.label("If"), crawlErrors, Ui.label("consecutive requests fail, pause the crawl")),
                row(Ui.label("Pause the crawl if"), crawlErrorPct, Ui.label("% of requests fail")),
                row(Ui.label("On completion of each crawl phase do"), crawlRetries, Ui.label("follow-up passes to retry failed operations")));
        return p;
    }

    private JComponent crawlOverridesPage() {
        OptionsPage p = new OptionsPage();
        JPanel inner = Ui.vbox(ovFollowRedirects, ovKeepAlive, ovHttp2);
        overrideProject.addItemListener(e -> { for (Component c : inner.getComponents()) c.setEnabled(overrideProject.isSelected()); });
        for (Component c : inner.getComponents()) c.setEnabled(false);
        p.section("Crawl project option overrides",
                Ui.note("The crawl normally uses the project's connection and HTTP settings. Override them here for this crawl only."),
                overrideProject, OptionsPage.indent(inner, 24));
        return p;
    }

    private JComponent auditBehaviourPage() {
        OptionsPage p = new OptionsPage();
        auditSpeed.setSelectedItem("Normal");
        auditAccuracy.setSelectedItem("Normal");
        p.section("Audit strategy",
                OptionsPage.labelRow("Audit speed:", auditSpeed),
                OptionsPage.labelRow("Audit accuracy:", auditAccuracy),
                skipIneffective, maintainSession, followRedirects, parallelPhases);
        p.section("Total scan time",
                OptionsPage.fieldRow("Maximum scan time:", asField(maxScanMin), "minutes (0 = no limit)"));
        p.section("Issue noise reduction", consolidatePassive);
        p.section("Audit network timeouts",
                OptionsPage.fieldRow("Maximum time to establish a connection:", asField(connectTimeout), "s"),
                OptionsPage.fieldRow("Maximum time to complete the request after connecting:", asField(requestTimeout), "s"));
        failPctOn.setText("");
        p.section("Handling repeated timeouts during audit",
                row(Ui.label("If"), failChecks, Ui.label("consecutive audit checks fail, skip remaining checks in the current insertion point")),
                row(Ui.label("If"), failPoints, Ui.label("consecutive insertion points fail, skip remaining insertion points and flag")),
                row(Ui.label("Pause the audit if"), failItems, Ui.label("consecutive audit items fail")),
                row(failPctOn, Ui.label("or if"), failPct, Ui.label("% of audit items fail")),
                row(Ui.label("On completion of each audit phase do"), followUpPasses, Ui.label("follow-up passes to retry failed operations")));
        return p;
    }

    private JComponent javascriptPage() {
        OptionsPage p = new OptionsPage();
        p.section("JavaScript analysis",
                Ui.note("How the audit analyses the site's JavaScript to find DOM-based issues and hidden resources."),
                jsDynamic, jsStatic, jsMissing, jsOutOfScope,
                OptionsPage.fieldRow("Maximum dynamic analysis time per item:", asField(jsDynamicSecs), "s"),
                OptionsPage.fieldRow("Maximum static analysis time per item:", asField(jsStaticSecs), "s"));
        return p;
    }

    private JComponent insertionPointsPage() {
        OptionsPage p = new OptionsPage();
        p.section("Set insertion point types",
                Ui.note("Where the audit places its payloads."),
                ipQuery, ipBody, ipCookie, ipName, ipHeaders, ipEntireBody,
                OptionsPage.indent(row(rpcMultipart, rpcXml, rpcJson, rpcGwt, rpcOdata), 24),
                ipFilename, ipFolders);
        p.section("Ignore specific insertion points",
                Ui.bold("Skip server-side injection tests for these parameters"), skipInjection,
                Box.createVerticalStrut(8),
                Ui.bold("Skip all tests for these parameters"), skipAll);
        p.section("Nested insertion points", nested);
        p.section("Insertion point limits",
                OptionsPage.fieldRow("Maximum insertion points per base request:", asField(maxPoints), ""));
        p.section("Payload relocation rules",
                Ui.note("Also try each payload in these other locations."),
                row(relocate[0], relocate[1], relocate[2]), row(relocate[3], relocate[4], relocate[5]));
        return p;
    }

    private JComponent passiveCrawlPage() {
        OptionsPage p = new OptionsPage();
        ButtonGroup g = new ButtonGroup();
        g.add(pcSuiteScope); g.add(pcCustomScope);
        p.section("Types of item to add", pcLinks, pcForms);
        p.section("URLs to add", pcEverything, pcItself, pcSameDomain,
                row(pcInScope, pcSuiteScope, pcCustomScope));
        return p;
    }

    private static JTextField asField(JSpinner s) {
        // OptionsPage.fieldRow takes a text field; wrap the spinner's editor so it lines up.
        JTextField f = ((JSpinner.DefaultEditor) s.getEditor()).getTextField();
        f.setColumns(5);
        f.setFont(Ui.PLAIN);
        return f;
    }

    // ---- presets ----

    private void onPreset() {
        Preset p = (Preset) preset.getSelectedItem();
        boolean isCustom = p == null || "Custom".equals(p.name());
        custom.setVisible(isCustom || mode == Mode.PASSIVE_CRAWL);
        if (!isCustom && p != null) apply(ScanConfig.preset(p.name()));
        revalidate();
        repaint();
    }

    public String presetName() {
        Preset p = (Preset) preset.getSelectedItem();
        return p == null ? "Custom" : p.name();
    }

    /** Put a configuration's values into the controls. */
    public void apply(ScanConfig c) {
        maxDepth.setValue(c.crawlDepth());
        maxCrawlMin.setValue(c.crawlDurationMin());
        submitForms.setSelected(c.submitForms());
        crawlDynamic.setSelected(c.ajaxSpider());
        jsDynamic.setSelected(c.ajaxSpider());
        jsDynamicSecs.setValue(Math.max(1, c.ajaxMaxDurationMin() == 0 ? 30 : c.ajaxMaxDurationMin() * 60));
        ajaxDepth.setValue(c.ajaxMaxDepth());
        ajaxStates.setValue(c.ajaxMaxStates());
        auditSpeed.setSelectedItem(switch (c.attackStrength()) { case "LOW" -> "Fast"; case "HIGH", "INSANE" -> "Thorough"; default -> "Normal"; });
        auditAccuracy.setSelectedItem(switch (c.alertThreshold()) { case "LOW" -> "Minimize false negatives"; case "HIGH" -> "Minimize false positives"; default -> "Normal"; });
        int inj = c.injectableMask();
        ipQuery.setSelected((inj & ScanConfig.INJECT_QUERY) != 0);
        ipBody.setSelected((inj & ScanConfig.INJECT_BODY) != 0);
        ipFilename.setSelected((inj & ScanConfig.INJECT_PATH) != 0);
        ipFolders.setSelected((inj & ScanConfig.INJECT_PATH) != 0);
        ipHeaders.setSelected((inj & ScanConfig.INJECT_HEADERS) != 0);
        ipCookie.setSelected((inj & ScanConfig.INJECT_COOKIE) != 0);
        int rpc = c.rpcMask();
        rpcMultipart.setSelected((rpc & ScanConfig.RPC_MULTIPART) != 0);
        rpcXml.setSelected((rpc & ScanConfig.RPC_XML) != 0);
        rpcJson.setSelected((rpc & ScanConfig.RPC_JSON) != 0);
        rpcGwt.setSelected((rpc & ScanConfig.RPC_GWT) != 0);
        rpcOdata.setSelected((rpc & ScanConfig.RPC_ODATA) != 0);
        maxScanMin.setValue(c.scanDurationMin());
    }

    /** The engine configuration the controls describe. */
    public ScanConfig config() {
        String name = presetName();
        int inj = 0;
        if (ipQuery.isSelected()) inj |= ScanConfig.INJECT_QUERY;
        if (ipBody.isSelected()) inj |= ScanConfig.INJECT_BODY;
        if (ipFilename.isSelected() || ipFolders.isSelected()) inj |= ScanConfig.INJECT_PATH;
        if (ipHeaders.isSelected()) inj |= ScanConfig.INJECT_HEADERS;
        if (ipCookie.isSelected()) inj |= ScanConfig.INJECT_COOKIE;
        int rpc = 0;
        if (ipEntireBody.isSelected()) {
            if (rpcMultipart.isSelected()) rpc |= ScanConfig.RPC_MULTIPART;
            if (rpcXml.isSelected()) rpc |= ScanConfig.RPC_XML;
            if (rpcJson.isSelected()) rpc |= ScanConfig.RPC_JSON;
            if (rpcGwt.isSelected()) rpc |= ScanConfig.RPC_GWT;
            if (rpcOdata.isSelected()) rpc |= ScanConfig.RPC_ODATA;
        }
        String strength = switch (String.valueOf(auditSpeed.getSelectedItem())) { case "Fast" -> "LOW"; case "Thorough" -> "HIGH"; default -> "MEDIUM"; };
        String threshold = switch (String.valueOf(auditAccuracy.getSelectedItem())) { case "Minimize false negatives" -> "LOW"; case "Minimize false positives" -> "HIGH"; default -> "MEDIUM"; };
        boolean dyn = crawlDynamic.isSelected() && jsDynamic.isSelected();
        return new ScanConfig(name,
                (Integer) maxDepth.getValue(), (Integer) maxCrawlMin.getValue(), submitForms.isSelected(),
                dyn, Math.max(0, (Integer) jsDynamicSecs.getValue() / 60), (Integer) ajaxDepth.getValue(), (Integer) ajaxStates.getValue(),
                strength, threshold, inj, rpc, 0, (Integer) maxScanMin.getValue());
    }

    /** Live passive crawl choices, for the task description. */
    public String passiveCrawlSummary() {
        List<String> add = new ArrayList<>();
        if (pcLinks.isSelected()) add.add("links");
        if (pcForms.isSelected()) add.add("form submissions");
        List<String> urls = new ArrayList<>();
        if (pcEverything.isSelected()) urls.add("everything");
        if (pcItself.isSelected()) urls.add("the item itself");
        if (pcSameDomain.isSelected()) urls.add("same domain");
        if (pcInScope.isSelected()) urls.add(pcSuiteScope.isSelected() ? "URLs in suite scope" : "URLs in custom scope");
        return "Add " + String.join(", ", add) + ". " + String.join(", ", urls) + ".";
    }

    private void save() {
        String suggested = "Custom".equals(presetName()) ? "" : presetName() + " (copy)";
        String name = JOptionPane.showInputDialog(this, "Name for this configuration:", suggested);
        if (name == null || name.isBlank()) return;
        name = name.strip();
        if (ScanConfigStore.exists(name) && JOptionPane.showConfirmDialog(this, "\"" + name + "\" already exists. Replace it?",
                "Save configuration", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try { ScanConfigStore.save(name, config()); }
        catch (java.io.IOException e) { JOptionPane.showMessageDialog(this, "Could not save the configuration: " + e.getMessage(), "Save configuration", JOptionPane.ERROR_MESSAGE); }
    }

    private void load() {
        List<String> names = ScanConfigStore.names();
        if (names.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No saved configurations yet. Set one up and click Save to add it to the library.",
                    "Load configuration", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object pick = JOptionPane.showInputDialog(this, "Saved configuration:", "Load configuration",
                JOptionPane.PLAIN_MESSAGE, null, names.toArray(), names.get(0));
        if (pick == null) return;
        try {
            preset.setSelectedIndex(4);
            apply(ScanConfigStore.load(pick.toString()));
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Could not load the configuration: " + e.getMessage(), "Load configuration", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Icon + title + grey description, both in the list and in the closed combo. */
    private static final class PresetRenderer extends JPanel implements ListCellRenderer<Preset> {
        private final JLabel title = Ui.bold("");
        private final JLabel desc = Ui.label("");
        private final JLabel icon = new JLabel();
        PresetRenderer() {
            super(new BorderLayout(8, 0));
            setBorder(Ui.pad(4, 6, 4, 6));
            JPanel text = new JPanel(new GridLayout(2, 1));
            text.setOpaque(false);
            desc.setForeground(Theme.MUTED);
            text.add(title); text.add(desc);
            add(icon, BorderLayout.WEST);
            add(text, BorderLayout.CENTER);
        }
        @Override public Component getListCellRendererComponent(JList<? extends Preset> list, Preset v, int i, boolean sel, boolean focus) {
            title.setText(v == null ? "" : v.name());
            desc.setText(v == null ? "" : v.description());
            icon.setIcon(Glyphs.bolt(16, sel ? Color.WHITE : Ui.ACCENT));
            setBackground(sel ? Ui.SELECTION : Theme.BG);
            setOpaque(true);
            title.setForeground(Theme.TEXT);
            desc.setForeground(Theme.MUTED);
            return this;
        }
    }

    // ---- the "skip these parameters" tables ----

    record SkipRule(boolean enabled, String parameter, String item, String matchType, String expression) {}

    private static List<SkipRule> defaultSkipInjection() {
        List<SkipRule> l = new ArrayList<>();
        l.add(new SkipRule(true, "Cookie", "Name", "Matches regex", "^aspsessionid.*$"));
        l.add(new SkipRule(true, "Cookie", "Name", "Matches regex", "^\\.aspnet.*$"));
        l.add(new SkipRule(true, "Body parameter", "Name", "Matches regex", "^__event.*$"));
        l.add(new SkipRule(true, "Body parameter", "Name", "Matches regex", "^__viewstate.*$"));
        for (String n : new String[]{"jsessionid", "cfid", "cftoken", "phpsessid", "sessionid"})
            l.add(new SkipRule(true, "Any parameter", "Name", "Matches", n));
        return l;
    }

    private static List<SkipRule> defaultSkipAll() {
        List<SkipRule> l = new ArrayList<>();
        for (String n : new String[]{"version", "encoding", "standalone", "xmlns", "xml:lang", "lang"})
            l.add(new SkipRule(true, "XML attribute", "Name", "Matches", n));
        for (String n : new String[]{"_ga", "_gid", "_gat", "^_ga_.*$", "^_gac_.*$", "^aws.*$"})
            l.add(new SkipRule(true, "Cookie", "Name", n.startsWith("^") ? "Matches regex" : "Matches", n));
        return l;
    }

    private static final class SkipTable extends JPanel {
        final List<SkipRule> rules;
        private final AbstractTableModel model = new AbstractTableModel() {
            private final String[] cols = {"Enabled", "Parameter", "Item", "Match type", "Expression"};
            @Override public int getRowCount() { return rules.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
            @Override public Object getValueAt(int r, int c) {
                SkipRule x = rules.get(r);
                return switch (c) { case 0 -> x.enabled(); case 1 -> x.parameter(); case 2 -> x.item(); case 3 -> x.matchType(); default -> x.expression(); };
            }
            @Override public void setValueAt(Object v, int r, int c) {
                if (c == 0) { SkipRule x = rules.get(r); rules.set(r, new SkipRule(Boolean.TRUE.equals(v), x.parameter(), x.item(), x.matchType(), x.expression())); }
            }
        };
        SkipTable(List<SkipRule> initial) {
            super(new BorderLayout(8, 0));
            setOpaque(false);
            rules = new ArrayList<>(initial);
            JTable t = new JTable(model);
            Ui.styleTable(t);
            t.getColumnModel().getColumn(0).setMaxWidth(70);
            JButton add = Ui.button("Add"), edit = Ui.button("Edit"), remove = Ui.button("Remove");
            add.addActionListener(e -> { SkipRule r = dialog(null); if (r != null) { rules.add(r); model.fireTableDataChanged(); } });
            edit.addActionListener(e -> { int i = t.getSelectedRow(); if (i >= 0) { SkipRule r = dialog(rules.get(i)); if (r != null) { rules.set(i, r); model.fireTableDataChanged(); } } });
            remove.addActionListener(e -> { int i = t.getSelectedRow(); if (i >= 0) { rules.remove(i); model.fireTableDataChanged(); } });
            add(Ui.buttonColumn(add, edit, remove), BorderLayout.WEST);
            JScrollPane sp = Ui.scroll(t);
            sp.setPreferredSize(new Dimension(560, 130));
            add(sp, BorderLayout.CENTER);
        }
        private SkipRule dialog(SkipRule editing) {
            JComboBox<String> param = Ui.combo(new String[]{"Any parameter", "URL parameter", "Body parameter", "Cookie", "HTTP header", "XML attribute", "JSON parameter"});
            JComboBox<String> item = Ui.combo(new String[]{"Name", "Value"});
            JComboBox<String> match = Ui.combo(new String[]{"Matches", "Matches regex"});
            JTextField expr = Ui.field(editing == null ? "" : editing.expression(), 28);
            if (editing != null) { param.setSelectedItem(editing.parameter()); item.setSelectedItem(editing.item()); match.setSelectedItem(editing.matchType()); }
            JPanel form = Ui.form("Parameter:", param, "Item:", item, "Match type:", match, "Expression:", expr);
            form.setBorder(Ui.pad(10, 10, 10, 10));
            int ok = JOptionPane.showConfirmDialog(this, form, (editing == null ? "Add" : "Edit") + " parameter rule", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION || expr.getText().isBlank()) return null;
            return new SkipRule(editing == null || editing.enabled(), String.valueOf(param.getSelectedItem()), String.valueOf(item.getSelectedItem()),
                    String.valueOf(match.getSelectedItem()), expr.getText().strip());
        }
    }

    // ---- Scan checks: Built-in / Custom / Extension ----

    record Check(String id, String name, String type, boolean javascript, String severity, String methods, boolean enabled) {}

    private static final class ChecksTable extends JPanel {
        private final List<Check> all = new ArrayList<>();
        private final List<Check> shown = new ArrayList<>();
        private final java.util.Set<String> typeFilter = new java.util.HashSet<>(List.of("Passive", "Light", "Medium", "Intrusive", "JavaScript"));
        private String query = "";
        private boolean loaded;
        private final JLabel status = Ui.label("");
        private final AbstractTableModel model = new AbstractTableModel() {
            private final String[] cols = {"", "Name", "Type", "JavaScript", "Typical severity", "Type index", "Detection methods"};
            @Override public int getRowCount() { return shown.size(); }
            @Override public int getColumnCount() { return cols.length; }
            @Override public String getColumnName(int c) { return cols[c]; }
            @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int r, int c) { return c == 0; }
            @Override public Object getValueAt(int r, int c) {
                Check x = shown.get(r);
                return switch (c) { case 0 -> x.enabled(); case 1 -> x.name(); case 2 -> x.type(); case 3 -> x.javascript() ? "Yes" : "";
                    case 4 -> x.severity(); case 5 -> x.id(); default -> x.methods(); };
            }
            @Override public void setValueAt(Object v, int r, int c) {
                if (c != 0) return;
                Check x = shown.get(r);
                Check y = new Check(x.id(), x.name(), x.type(), x.javascript(), x.severity(), x.methods(), Boolean.TRUE.equals(v));
                shown.set(r, y);
                int i = all.indexOf(x); if (i >= 0) all.set(i, y);
            }
        };
        private final JTable table = new JTable(model);

        ChecksTable() {
            super(new BorderLayout());
            setOpaque(false);
            JTabbedPane tabs = new JTabbedPane();
            tabs.setFont(Ui.PLAIN);
            tabs.addTab("Built-in", builtIn());
            tabs.addTab("Custom", customTab());
            tabs.addTab("Extension", extensionTab());
            add(tabs, BorderLayout.CENTER);
            addHierarchyListener(e -> { if (isShowing() && !loaded) { loaded = true; load(); } });
        }

        private JComponent builtIn() {
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.setOpaque(false);
            p.setBorder(Ui.pad(8, 4, 4, 4));
            JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            chips.setOpaque(false);
            chips.add(new JLabel(Glyphs.funnel(14, Theme.MUTED)));
            chips.add(Ui.label("Filter"));
            for (String t : new String[]{"Passive", "Light", "Medium", "Intrusive", "JavaScript"}) {
                JToggleButton b = new JToggleButton(t, true);
                b.setFont(Ui.PLAIN); b.setFocusable(false);
                b.addActionListener(e -> { if (b.isSelected()) typeFilter.add(t); else typeFilter.remove(t); refilter(); });
                chips.add(b);
            }
            JTextField search = Ui.field("", 16);
            search.putClientProperty("JTextField.placeholderText", "Search");
            search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { query = search.getText(); refilter(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { query = search.getText(); refilter(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { query = search.getText(); refilter(); }
            });
            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            right.setOpaque(false);
            right.add(search);
            right.add(Glyphs.button(Glyphs.help(), "The scanner's built-in checks, from the engine's policy", null));
            right.add(Glyphs.menuButton("More", () -> {
                JPopupMenu m = new JPopupMenu();
                JMenuItem on = new JMenuItem("Enable all shown"), off = new JMenuItem("Disable all shown");
                on.setFont(Ui.PLAIN); off.setFont(Ui.PLAIN);
                on.addActionListener(e -> setAll(true)); off.addActionListener(e -> setAll(false));
                m.add(on); m.add(off);
                return m;
            }));
            JPanel bar = new JPanel(new BorderLayout());
            bar.setOpaque(false);
            bar.add(chips, BorderLayout.WEST);
            bar.add(right, BorderLayout.EAST);
            p.add(bar, BorderLayout.NORTH);
            Ui.styleTable(table);
            table.getColumnModel().getColumn(0).setMaxWidth(32);
            Ui.distributeWidths(table, 32, 260, 80, 80, 110, 80, 160);
            p.add(Ui.scroll(table), BorderLayout.CENTER);
            status.setForeground(Theme.MUTED);
            p.add(status, BorderLayout.SOUTH);
            return p;
        }

        private void setAll(boolean on) {
            for (int i = 0; i < shown.size(); i++) model.setValueAt(on, i, 0);
            model.fireTableDataChanged();
        }

        private JComponent customTab() {
            JPanel col = new JPanel();
            col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
            col.setOpaque(false);
            JLabel icon = new JLabel(Glyphs.bolt(48, Ui.ACCENT));
            JLabel h = Ui.heading("Extend the scanner with custom scan checks");
            JTextArea t = Ui.note("Write a check once and the scanner runs it against every audit item. Custom checks live in the Script library under Extensions.");
            Ui.sizeNote(t, 420);
            JButton learn = Ui.button("Learn more");
            learn.addActionListener(e -> opentrap.ui.Unbuilt.openProject(this));
            JButton go = Ui.primaryButton("Go to custom scan checks", "open Extensions > Custom scan checks");
            go.addActionListener(e -> {
                java.awt.Window w = SwingUtilities.getWindowAncestor(this);
                for (java.awt.Frame f : java.awt.Frame.getFrames()) if (f instanceof opentrap.ui.SuiteFrame sf) { sf.selectTool("extensions"); break; }
                if (w instanceof JDialog d) d.toBack();
            });
            for (JComponent c : new JComponent[]{icon, h, t, Ui.row(learn, go)}) { c.setAlignmentX(Component.CENTER_ALIGNMENT); col.add(c); col.add(Box.createVerticalStrut(10)); }
            JPanel wrap = new JPanel(new GridBagLayout());
            wrap.setOpaque(false);
            wrap.add(col, new GridBagConstraints());
            return wrap;
        }

        private JComponent extensionTab() {
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.setOpaque(false);
            p.setBorder(Ui.pad(8, 4, 4, 4));
            JCheckBox enabled = Ui.check("Enabled", true);
            p.add(Ui.row(enabled, Ui.note("Run the checks that loaded extensions contribute.")), BorderLayout.NORTH);
            javax.swing.table.DefaultTableModel m = new javax.swing.table.DefaultTableModel(new Object[]{"Check", "Extension", "Type", "Check runs"}, 0) {
                @Override public boolean isCellEditable(int r, int c) { return false; }
            };
            JTable t = new JTable(m);
            Ui.styleTable(t);
            p.add(Ui.scroll(t), BorderLayout.CENTER);
            return p;
        }

        private void refilter() {
            shown.clear();
            String q = query.strip().toLowerCase();
            for (Check c : all) {
                if (!typeFilter.contains(c.type()) && !(c.javascript() && typeFilter.contains("JavaScript"))) continue;
                if (!q.isEmpty() && !c.name().toLowerCase().contains(q)) continue;
                shown.add(c);
            }
            model.fireTableDataChanged();
        }

        /** Read the engine's policy off the EDT; the engine starts on first use. */
        private void load() {
            status.setText("Loading the scanner's checks from the engine...");
            new Thread(() -> {
                List<Check> out = new ArrayList<>();
                String note;
                try {
                    opentrap.engine.ZapEngine ze = opentrap.engine.ZapEngine.shared();
                    if (ze == null || !ze.available()) {
                        note = "Scan engine not installed: run ./fetch-zap.sh to list its checks.";
                    } else {
                        ze.start();
                        for (var s : ze.api().passiveScanners())
                            out.add(new Check(opentrap.engine.ZapApi.str(s, "id"), opentrap.engine.ZapApi.str(s, "name"), "Passive", false,
                                    "", "Passive", "true".equalsIgnoreCase(opentrap.engine.ZapApi.str(s, "enabled"))));
                        for (var s : ze.api().activeScanners()) {
                            String strength = opentrap.engine.ZapApi.str(s, "attackStrength");
                            String type = switch (strength == null ? "" : strength.toUpperCase()) { case "LOW" -> "Light"; case "HIGH", "INSANE" -> "Intrusive"; default -> "Medium"; };
                            out.add(new Check(opentrap.engine.ZapApi.str(s, "id"), opentrap.engine.ZapApi.str(s, "name"), type, false,
                                    "", "Active", "true".equalsIgnoreCase(opentrap.engine.ZapApi.str(s, "enabled"))));
                        }
                        note = out.size() + " checks from the engine's policy. Enable or disable them under Scanner.";
                    }
                } catch (Exception e) {
                    note = "Could not read the engine's checks: " + e.getMessage();
                }
                final String fnote = note;
                SwingUtilities.invokeLater(() -> { all.clear(); all.addAll(out); refilter(); status.setText(fnote); });
            }, "scan-checks-load").start();
        }
    }
}
