package opentrap.dashboard;

import opentrap.core.SuiteContext;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * The New scan wizard: a left step rail (Scan type, Scan details, Scan
 * configuration, Application login, Resource pool) and a content area, with
 * Cancel / Previous / Next / Scan along the bottom.
 *
 * <p>Behaviour mirrors the reference wizard: only "Scan type" is shown until a
 * scan type is chosen, at which point the remaining steps appear and the Scan
 * button becomes enabled (orange). The dialog only gathers configuration; the
 * caller reads the getters and starts the scan.
 */
public class NewScanWizard extends JDialog {

    private static final String[] STEPS = {
            "Scan type", "Scan details", "Scan configuration", "Application login", "Resource pool"
    };
    private static final String[] STEP_ICONS = { "type", "details", "config", "login", "pool" };

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JToggleButton[] rail = new JToggleButton[STEPS.length];
    private int step;
    private boolean typeChosen;

    // Collected configuration.
    private final JRadioButton typeCrawlAudit = new JRadioButton();
    private final JRadioButton typeCrawl = new JRadioButton();
    private final JRadioButton typeApiOnly = new JRadioButton();
    private final JTextArea urls = new JTextArea();
    private String configPreset = "Lightweight";
    private final java.util.Map<String, JPanel> configCards = new java.util.LinkedHashMap<>();
    private final JSpinner threads = new JSpinner(new SpinnerNumberModel(10, 1, 50, 1));

    // Custom tier controls, seeded from "Balanced" as a sensible starting point.
    // Read by config() to build a ScanConfig straight from the engine knobs the
    // user set in the Custom configuration dialog.
    private final JComboBox<String> cStrength =
            new JComboBox<>(new String[]{"LOW", "MEDIUM", "HIGH", "INSANE"});
    private final JComboBox<String> cThreshold =
            new JComboBox<>(new String[]{"OFF", "LOW", "MEDIUM", "HIGH"});
    private final JSpinner cCrawlDepth = new JSpinner(new SpinnerNumberModel(10, 0, 100, 1));
    private final JSpinner cCrawlDuration = new JSpinner(new SpinnerNumberModel(0, 0, 1440, 5));
    private final JCheckBox cSubmitForms = Ui.check("Submit forms while crawling", true);
    private final JCheckBox cAjax = Ui.check("AJAX spider (crawl JavaScript applications)", true);
    private final JSpinner cAjaxDuration = new JSpinner(new SpinnerNumberModel(30, 0, 600, 5));
    private final JSpinner cAjaxDepth = new JSpinner(new SpinnerNumberModel(10, 0, 100, 1));
    private final JSpinner cAjaxStates = new JSpinner(new SpinnerNumberModel(0, 0, 10000, 10));
    private final JCheckBox vQuery   = Ui.check("Query string", true);
    private final JCheckBox vBody    = Ui.check("POST body", true);
    private final JCheckBox vPath    = Ui.check("URL path", true);
    private final JCheckBox vHeaders = Ui.check("Headers", true);
    private final JCheckBox vCookie  = Ui.check("Cookies", true);
    private final JCheckBox rMultipart = Ui.check("Multipart", true);
    private final JCheckBox rXml       = Ui.check("XML", true);
    private final JCheckBox rJson      = Ui.check("JSON", true);
    private final JCheckBox rGwt       = Ui.check("GWT", true);
    private final JCheckBox rOdata     = Ui.check("OData", true);
    private final JSpinner cDelay = new JSpinner(new SpinnerNumberModel(0, 0, 5000, 50));
    private final JSpinner cScanDuration = new JSpinner(new SpinnerNumberModel(0, 0, 1440, 5));

    private JButton prevButton, nextButton, scanButton;
    private boolean started;

    // Scan configuration tiers. Every tier tests the same things (all input
    // vectors, all rules, the same reporting threshold); they differ only in how
    // much the crawl discovers and how hard each rule attacks it, so the
    // descriptions talk about depth and strength, not fixed time promises.
    private static final String[][] CONFIGS = {
        {"Lightweight", "Full coverage at low attack strength over a shallow crawl. The quickest way to surface obvious issues."},
        {"Fast",        "Full coverage at medium strength over a wider crawl. A good first pass on most targets."},
        {"Balanced",    "Full coverage at high strength with JavaScript crawling. Thorough without running unbounded."},
        {"Deep",        "Full coverage at maximum strength over an unlimited crawl. The most comprehensive scan available."},
        {"Custom",      "Set every engine knob yourself: crawl depth, AJAX budget, attack strength, threshold and injection points."},
    };

    public NewScanWizard(SuiteContext ctx, Frame owner) {
        super(owner, "New scan", true);
        // Cancel and Scan both dispose; make the window-manager close do the
        // same rather than only hiding this heavy five-step dialog.
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        // Seed the Custom controls from a thorough, Balanced-like starting point,
        // and keep the AJAX budget inputs in step with the AJAX checkbox.
        cStrength.setSelectedItem("HIGH");
        cThreshold.setSelectedItem("MEDIUM");
        cStrength.setFont(Ui.PLAIN);
        cThreshold.setFont(Ui.PLAIN);
        cAjax.addActionListener(e -> syncAjaxEnabled());

        content.add(buildScanType(), STEPS[0]);
        content.add(buildScanDetails(ctx), STEPS[1]);
        content.add(buildScanConfiguration(), STEPS[2]);
        content.add(buildApplicationLogin(), STEPS[3]);
        content.add(buildResourcePool(), STEPS[4]);

        setLayout(new BorderLayout());
        add(buildRail(), BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        selectStep(0);
        revealSteps(false);   // only "Scan type" until a type is chosen

        setSize(940, 620);
        setLocationRelativeTo(owner);
    }

    // ---- getters the caller reads after the dialog closes ----

    public boolean started()       { return started; }
    public boolean crawlAndAudit() { return typeCrawlAudit.isSelected(); }
    public int threads()           { return (Integer) threads.getValue(); }

    public String targetUrl() {
        for (String line : urls.getText().split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    /** Every non-empty URL in the list, each of which is scanned in turn. */
    public java.util.List<String> targetUrls() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : urls.getText().split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** The real engine settings implied by the chosen configuration tier. */
    public opentrap.engine.ScanConfig config() {
        if (!"Custom".equals(configPreset)) return opentrap.engine.ScanConfig.preset(configPreset);
        int inject = (vQuery.isSelected()   ? opentrap.engine.ScanConfig.INJECT_QUERY   : 0)
                   | (vBody.isSelected()    ? opentrap.engine.ScanConfig.INJECT_BODY    : 0)
                   | (vPath.isSelected()    ? opentrap.engine.ScanConfig.INJECT_PATH    : 0)
                   | (vHeaders.isSelected() ? opentrap.engine.ScanConfig.INJECT_HEADERS : 0)
                   | (vCookie.isSelected()  ? opentrap.engine.ScanConfig.INJECT_COOKIE  : 0);
        int rpc = (rMultipart.isSelected() ? opentrap.engine.ScanConfig.RPC_MULTIPART : 0)
                | (rXml.isSelected()       ? opentrap.engine.ScanConfig.RPC_XML       : 0)
                | (rJson.isSelected()      ? opentrap.engine.ScanConfig.RPC_JSON      : 0)
                | (rGwt.isSelected()       ? opentrap.engine.ScanConfig.RPC_GWT       : 0)
                | (rOdata.isSelected()     ? opentrap.engine.ScanConfig.RPC_ODATA     : 0);
        return new opentrap.engine.ScanConfig(
                "Custom",
                (Integer) cCrawlDepth.getValue(),
                (Integer) cCrawlDuration.getValue(),
                cSubmitForms.isSelected(),
                cAjax.isSelected(),
                (Integer) cAjaxDuration.getValue(),
                (Integer) cAjaxDepth.getValue(),
                (Integer) cAjaxStates.getValue(),
                (String) cStrength.getSelectedItem(),
                (String) cThreshold.getSelectedItem(),
                inject, rpc,
                (Integer) cDelay.getValue(),
                (Integer) cScanDuration.getValue());
    }

    // ---- left rail ----

    private JComponent buildRail() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.SURFACE);
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.GRID));
        ButtonGroup g = new ButtonGroup();
        for (int i = 0; i < STEPS.length; i++) {
            final int idx = i;
            JToggleButton b = new JToggleButton(
                    "<html><div style='text-align:center'>" + STEPS[i].replace(" ", "<br>") + "</div></html>");
            b.setFont(Ui.PLAIN);
            b.setFocusable(false);
            b.setContentAreaFilled(false);
            b.setOpaque(true);
            b.setBackground(Theme.SURFACE);
            b.setHorizontalTextPosition(SwingConstants.CENTER);
            b.setVerticalTextPosition(SwingConstants.BOTTOM);
            b.setBorder(Ui.pad(12, 14, 12, 14));
            b.setAlignmentX(Component.CENTER_ALIGNMENT);
            b.setMaximumSize(new Dimension(150, 80));
            b.addActionListener(e -> selectStep(idx));
            g.add(b);
            rail[i] = b;
            p.add(b);
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.NORTH);
        wrap.setBackground(Theme.SURFACE);
        wrap.setPreferredSize(new Dimension(152, 10));
        return wrap;
    }

    /** Show or hide every step after "Scan type". */
    private void revealSteps(boolean show) {
        for (int k = 1; k < rail.length; k++) rail[k].setVisible(show);
        rail[0].getParent().revalidate();
        rail[0].getParent().repaint();
    }

    private void selectStep(int i) {
        // Steps beyond the first are only reachable once a scan type is chosen.
        if (!typeChosen) i = 0;
        step = Math.max(0, Math.min(STEPS.length - 1, i));
        for (int k = 0; k < rail.length; k++) {
            boolean on = k == step;
            rail[k].setSelected(on);
            rail[k].setBackground(on ? Ui.ACCENT : Theme.SURFACE);
            rail[k].setForeground(on ? Color.WHITE : Theme.TEXT);
            rail[k].setIcon(railIcon(STEP_ICONS[k], on ? Color.WHITE : Theme.MUTED));
        }
        cards.show(content, STEPS[step]);
        updateButtons();
    }

    // ---- step panels ----

    private JComponent buildScanType() {
        ButtonGroup g = new ButtonGroup();
        g.add(typeCrawlAudit);
        g.add(typeCrawl);
        g.add(typeApiOnly);
        JPanel cardsRow = new JPanel(new GridLayout(1, 3, 16, 0));
        cardsRow.setOpaque(false);
        cardsRow.add(typeCard(typeCrawlAudit, "Crawl and audit",
                "Maps the attack surface and tests it for vulnerabilities, including any detected APIs.", true));
        cardsRow.add(typeCard(typeCrawl, "Crawl",
                "Maps the attack surface of the application and any detected APIs.", true));
        // API-only scanning is not implemented yet: the card is shown but not
        // selectable as a scan type; clicking it opens the "not yet implemented"
        // dialog to invite a contribution.
        Component apiCard = typeCard(typeApiOnly, "API-only scan",
                "Scans only detected APIs. Not yet implemented.", false);
        apiCard.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addClickToTree(apiCard, new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                opentrap.ui.Unbuilt.SCAN_TYPE_API.showDialog(apiCard);
            }
        });
        cardsRow.add(apiCard);

        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(cardsRow, BorderLayout.NORTH);
        return stepPanel("Scan type", "Select the type of scan you'd like to run.", wrap);
    }

    private JComponent typeCard(JRadioButton radio, String title, String desc, boolean enabled) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(enabled ? Theme.BG : Theme.SURFACE);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.GRID), Ui.pad(14, 14, 14, 14)));
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        head.setOpaque(false);
        radio.setOpaque(false);
        radio.setEnabled(enabled);
        head.add(radio);
        JLabel titleLabel = Ui.bold(title);
        if (!enabled) titleLabel.setForeground(Theme.MUTED);
        head.add(titleLabel);
        JTextArea d = Ui.note(desc);
        card.add(head, BorderLayout.NORTH);
        card.add(d, BorderLayout.CENTER);
        // A wrapping text area reports a single-line preferred height, which would
        // clip the description; fix a height that fits the wrapped text so the
        // three cards stay equal and nothing is cut off.
        card.setPreferredSize(new Dimension(230, 104));
        if (enabled) {
            card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            java.awt.event.MouseAdapter pick = new java.awt.event.MouseAdapter() {
                @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                    radio.setSelected(true);
                    onTypeChosen();
                }
            };
            addClickToTree(card, pick);
            radio.addActionListener(e -> onTypeChosen());
        }
        return card;
    }

    /** First scan-type selection: reveal the rest of the steps and enable Scan. */
    private void onTypeChosen() {
        if (typeChosen) { updateButtons(); return; }
        typeChosen = true;
        revealSteps(true);
        updateButtons();
    }

    private JComponent buildScanDetails(SuiteContext ctx) {
        urls.setFont(Ui.MONO);
        StringBuilder sb = new StringBuilder();
        // Start from what is in scope: one URL per enabled include rule. A
        // prefix rule is the URL itself; a host-only rule and an advanced rule
        // with a literal host are offered over https.
        try {
            for (var r : ctx.scope().include()) {
                if (!r.enabled) continue;
                if (r.isPrefix()) {
                    String p = r.prefix;
                    if (!p.contains("://")) p = "https://" + p;
                    if (!p.substring(p.indexOf("://") + 3).contains("/")) p = p + "/";
                    sb.append(p).append('\n');
                } else {
                    String host = opentrap.core.Scope.display(r.host);
                    if (!host.isBlank() && host.matches("[A-Za-z0-9.-]+")) {
                        sb.append("https".equalsIgnoreCase(r.protocol) || "any".equalsIgnoreCase(r.protocol) ? "https://" : "http://")
                          .append(host).append("/\n");
                    }
                }
            }
        } catch (Exception ignored) {}
        urls.setText(sb.toString());

        JScrollPane sp = Ui.scroll(urls);
        sp.setPreferredSize(new Dimension(500, 220));

        JTextArea urlsNote = Ui.note("OpenTRAP will begin crawling from these URLs, and by default "
                + "includes everything beneath the specified URLs' folders. One URL per line.");

        // Protocol settings.
        JRadioButton pHttpHttps = Ui.radio("Scan using HTTP & HTTPS", true);
        JRadioButton pSpecified = Ui.radio("Scan using my specified protocols", false);
        ButtonGroup pg = new ButtonGroup();
        pg.add(pHttpHttps); pg.add(pSpecified);
        JPanel protoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        protoRow.setOpaque(false);
        protoRow.add(pHttpHttps); protoRow.add(Box.createHorizontalStrut(18)); protoRow.add(pSpecified);

        // Detailed scope configuration: an expander that reveals the in-scope hosts.
        JTextArea scopeInfo = Ui.note(sb.length() == 0
                ? "Scope is taken from the URLs above (and everything beneath their folders)."
                : "In scope:\n" + sb.toString().trim());
        scopeInfo.setVisible(false);
        JButton scopeToggle = new JButton("› Detailed scope configuration");
        scopeToggle.setFont(Ui.PLAIN);
        scopeToggle.setBorder(Ui.pad(2, 0, 2, 0));
        scopeToggle.setContentAreaFilled(false);
        scopeToggle.setFocusPainted(false);
        scopeToggle.setHorizontalAlignment(SwingConstants.LEFT);
        scopeToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        scopeToggle.addActionListener(e -> {
            boolean now = !scopeInfo.isVisible();
            scopeInfo.setVisible(now);
            scopeToggle.setText((now ? "⌄ " : "› ") + "Detailed scope configuration");
        });

        // Advanced scan parameters: the isolated-scan toggle (not yet honoured, so
        // shown disabled rather than as a control that does nothing).
        JCheckBox isolated = Ui.check("Run isolated scan", false);
        isolated.setEnabled(false);
        isolated.setToolTipText("Isolated scans are not yet available.");

        // Lay the body out in a single full-width column with GridBag, which keeps
        // every row flush-left and lets the URLs box fill the width.
        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1; c.insets = new Insets(0, 0, 0, 0);
        int row = 0;
        c.gridy = row++; c.fill = GridBagConstraints.BOTH; c.weighty = 0; c.ipady = 200; body.add(sp, c);
        c.fill = GridBagConstraints.HORIZONTAL; c.ipady = 0;
        c.gridy = row++; c.insets = new Insets(6, 0, 16, 0); body.add(urlsNote, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0); body.add(sectionLabel("Protocol settings"), c);
        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0); body.add(protoRow, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 14, 0); body.add(scopeToggle, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 14, 0); body.add(scopeInfo, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0); body.add(sectionLabel("Advanced scan parameters"), c);
        c.gridy = row++; c.insets = new Insets(0, 0, 4, 0);
        body.add(Ui.note("Isolate the scan so its results do not appear in the main site map or issue log."), c);
        c.gridy = row++; c.insets = new Insets(0, 0, 0, 0); body.add(isolated, c);
        // Filler row pushes everything to the top.
        c.gridy = row; c.weighty = 1; c.fill = GridBagConstraints.BOTH; body.add(Box.createGlue(), c);

        return stepPanel("URLs to scan",
                "Define the URLs to scan. OpenTRAP will begin crawling from these URLs.", body);
    }

    private JComponent buildScanConfiguration() {
        // The configurations are shown directly as a selectable list (no dropdown).
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.setAlignmentX(Component.LEFT_ALIGNMENT);
        list.setBorder(BorderFactory.createLineBorder(Ui.GRID));
        for (String[] c : CONFIGS) {
            JPanel card = configCard(c[0], c[1], true);
            configCards.put(c[0], card);
            list.add(card);
        }

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        JPanel listWrap = new JPanel(new BorderLayout());
        listWrap.setOpaque(false);
        listWrap.add(list, BorderLayout.NORTH);
        body.add(listWrap, BorderLayout.NORTH);

        // Named configurations live in the configuration library (Settings >
        // Configuration library); Save adds the current one, Load adopts one as
        // the Custom tier.
        JButton save = Ui.button("Save", "save the current configuration under a name");
        JButton load = Ui.button("Load", "load a saved configuration");
        save.addActionListener(e -> saveConfig());
        load.addActionListener(e -> loadConfig());
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        actions.setOpaque(false);
        actions.add(save); actions.add(load);

        JComponent panel = stepPanel("Scan configuration",
                "Select the type of scan configuration you want to run. You can adjust it as needed.", body);
        if (panel instanceof JPanel jp && jp.getComponent(0) instanceof JPanel head) {
            head.add(actions, BorderLayout.EAST);
        }
        selectConfig(configPreset);
        return panel;
    }

    /** Add a mouse listener to a component and all of its descendants, so a
     *  click anywhere inside a card is handled rather than only on the gaps
     *  between its child components. */
    private static void addClickToTree(Component c, java.awt.event.MouseListener l) {
        c.addMouseListener(l);
        if (c instanceof Container cont) {
            for (Component child : cont.getComponents()) addClickToTree(child, l);
        }
    }

    private JPanel configCard(String name, String desc, boolean enabled) {
        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(true);
        row.setBackground(Theme.BG);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.GRID), Ui.pad(10, 12, 10, 12)));
        row.add(new JLabel(configIcon(name)), BorderLayout.WEST);
        JPanel text = new JPanel(new BorderLayout());
        text.setOpaque(false);
        JLabel title = Ui.bold(name);
        if (!enabled) title.setForeground(Theme.MUTED);
        text.add(title, BorderLayout.NORTH);
        text.add(Ui.note(desc), BorderLayout.CENTER);
        row.add(text, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        // A tier whose configuration UI is not built yet is shown but not
        // selectable, rather than offering a control that does nothing.
        if (!enabled) {
            row.setToolTipText(name + " configuration is coming soon.");
            return row;
        }
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter pick = new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { selectConfig(name); }
        };
        // Attach to every descendant so a click anywhere in the rectangle
        // selects the tier, including on the description text.
        addClickToTree(row, pick);
        return row;
    }

    private void selectConfig(String name) {
        if (name == null) return;
        // Custom opens its own configuration screen; only adopt it if the user
        // confirms there, otherwise leave the previously chosen tier selected.
        if ("Custom".equals(name)) {
            if (openCustomConfig()) configPreset = "Custom";
            highlightConfig(configPreset);
            return;
        }
        configPreset = name;
        highlightConfig(name);
    }

    private void saveConfig() {
        String suggested = "Custom".equals(configPreset) ? "" : configPreset + " (copy)";
        String name = JOptionPane.showInputDialog(this, "Name for this configuration:", suggested);
        if (name == null || name.isBlank()) return;
        name = name.trim();
        if (opentrap.engine.ScanConfigStore.exists(name)
                && JOptionPane.showConfirmDialog(this, "\"" + name + "\" already exists. Replace it?",
                "Save configuration", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            opentrap.engine.ScanConfigStore.save(name, config());
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Could not save the configuration: " + e.getMessage(),
                    "Save configuration", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadConfig() {
        java.util.List<String> names = opentrap.engine.ScanConfigStore.names();
        if (names.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No saved configurations yet. Set one up and click Save to add it to the library.",
                    "Load configuration", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        Object pick = JOptionPane.showInputDialog(this, "Saved configuration:", "Load configuration",
                JOptionPane.PLAIN_MESSAGE, null, names.toArray(), names.get(0));
        if (pick == null) return;
        try {
            applyCustom(opentrap.engine.ScanConfigStore.load(pick.toString()));
            configPreset = "Custom";
            highlightConfig(configPreset);
        } catch (java.io.IOException e) {
            JOptionPane.showMessageDialog(this, "Could not load the configuration: " + e.getMessage(),
                    "Load configuration", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Put a configuration's values into the Custom controls (the inverse of config()). */
    private void applyCustom(opentrap.engine.ScanConfig c) {
        cCrawlDepth.setValue(c.crawlDepth());
        cCrawlDuration.setValue(c.crawlDurationMin());
        cSubmitForms.setSelected(c.submitForms());
        cAjax.setSelected(c.ajaxSpider());
        cAjaxDuration.setValue(c.ajaxMaxDurationMin());
        cAjaxDepth.setValue(c.ajaxMaxDepth());
        cAjaxStates.setValue(c.ajaxMaxStates());
        cStrength.setSelectedItem(c.attackStrength());
        cThreshold.setSelectedItem(c.alertThreshold());
        int inject = c.injectableMask();
        vQuery.setSelected((inject & opentrap.engine.ScanConfig.INJECT_QUERY) != 0);
        vBody.setSelected((inject & opentrap.engine.ScanConfig.INJECT_BODY) != 0);
        vPath.setSelected((inject & opentrap.engine.ScanConfig.INJECT_PATH) != 0);
        vHeaders.setSelected((inject & opentrap.engine.ScanConfig.INJECT_HEADERS) != 0);
        vCookie.setSelected((inject & opentrap.engine.ScanConfig.INJECT_COOKIE) != 0);
        int rpc = c.rpcMask();
        rMultipart.setSelected((rpc & opentrap.engine.ScanConfig.RPC_MULTIPART) != 0);
        rXml.setSelected((rpc & opentrap.engine.ScanConfig.RPC_XML) != 0);
        rJson.setSelected((rpc & opentrap.engine.ScanConfig.RPC_JSON) != 0);
        rGwt.setSelected((rpc & opentrap.engine.ScanConfig.RPC_GWT) != 0);
        rOdata.setSelected((rpc & opentrap.engine.ScanConfig.RPC_ODATA) != 0);
        cDelay.setValue(c.delayMs());
        cScanDuration.setValue(c.scanDurationMin());
        syncAjaxEnabled();
    }

    private void highlightConfig(String name) {
        for (var e : configCards.entrySet()) {
            e.getValue().setBackground(e.getKey().equals(name) ? Ui.SELECTION : Theme.BG);
        }
    }

    private JComponent buildApplicationLogin() {
        return stepPanel("Application login",
                "Configure how the scan authenticates.",
                opentrap.ui.Unbuilt.SCAN_LOGIN.panel());
    }

    private JComponent buildResourcePool() {
        threads.setFont(Ui.PLAIN);
        threads.setPreferredSize(new Dimension(70, threads.getPreferredSize().height));
        threads.setMaximumSize(threads.getPreferredSize());
        JPanel spinnerWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        spinnerWrap.setOpaque(false);
        spinnerWrap.add(threads);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(Ui.form("Concurrent requests:", spinnerWrap), BorderLayout.NORTH);
        body.add(Ui.note("The number of requests the scan engine sends at once. Lower this to "
                + "be gentler on the target; raise it to scan faster."), BorderLayout.CENTER);
        return stepPanel("Resource pool", "Specify the resources the scan may use.", body);
    }

    /**
     * The Custom configuration screen: a modal dialog with a left category list
     * and a right settings pane holding every engine knob a scan can set, grouped
     * into crawl, audit and injection points. Returns true if the user confirmed
     * with OK; on Cancel the controls are restored to how they were on entry, so
     * the edit is discarded. Controls are seeded from "Balanced" the first time.
     */
    private boolean openCustomConfig() {
        Object[] snapshot = snapshotCustom();
        syncAjaxEnabled();

        JComponent split = customConfigView();
        final boolean[] confirmed = {false};
        final JDialog dlg = new JDialog(this, "Custom scan configuration", true);
        dlg.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JButton cancel = Ui.button("Cancel");
        JButton ok = Ui.button("OK");
        cancel.addActionListener(e -> dlg.dispose());
        ok.addActionListener(e -> { confirmed[0] = true; dlg.dispose(); });
        ok.putClientProperty(Theme.PRIMARY, Boolean.TRUE);
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID));
        bar.add(cancel);
        bar.add(ok);

        dlg.setLayout(new BorderLayout());
        dlg.add(split, BorderLayout.CENTER);
        dlg.add(bar, BorderLayout.SOUTH);
        dlg.setSize(720, 540);
        dlg.setLocationRelativeTo(this);
        dlg.setVisible(true);   // modal: blocks until OK or Cancel

        if (!confirmed[0]) restoreCustom(snapshot);
        return confirmed[0];
    }

    /** The Custom dialog's body: a left category list and a right settings pane. */
    JComponent customConfigView() {
        final String[] cats = {"Crawl", "Audit", "Injection points"};
        CardLayout rightCards = new CardLayout();
        JPanel right = new JPanel(rightCards);
        right.setBackground(Theme.BG);
        right.add(stepPanel("Crawl",
                "How much of the site the scan discovers before it audits.", crawlSection()), cats[0]);
        right.add(stepPanel("Audit",
                "How hard each rule probes what the crawl found.", auditSection()), cats[1]);
        right.add(stepPanel("Injection points",
                "Where the audit injects its payloads.", injectionSection()), cats[2]);

        JList<String> nav = new JList<>(cats);
        nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nav.setFont(Ui.PLAIN);
        nav.setFixedCellHeight(34);
        nav.setBorder(Ui.pad(6, 4, 6, 4));
        nav.setBackground(Theme.SURFACE);
        nav.setSelectionBackground(Ui.SELECTION);
        nav.setSelectionForeground(Theme.TEXT);
        nav.setSelectedIndex(0);
        nav.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && nav.getSelectedValue() != null) {
                rightCards.show(right, nav.getSelectedValue());
            }
        });
        JScrollPane navScroll = Ui.scroll(nav);
        navScroll.setPreferredSize(new Dimension(176, 10));
        navScroll.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.GRID));

        JPanel split = new JPanel(new BorderLayout());
        split.setBackground(Theme.BG);
        split.add(navScroll, BorderLayout.WEST);
        split.add(Ui.scroll(right), BorderLayout.CENTER);
        return split;
    }

    private JComponent crawlSection() {
        return new Grid()
                .field("Crawl depth:", fld(cCrawlDepth, 80))
                .field("Crawl time budget (min):", fld(cCrawlDuration, 80))
                .full(cSubmitForms)
                .full(cAjax)
                .field("AJAX time budget (min):", fld(cAjaxDuration, 80))
                .field("AJAX depth:", fld(cAjaxDepth, 80))
                .field("AJAX states:", fld(cAjaxStates, 80))
                .gap()
                .full(Ui.note("0 means unlimited depth and states, and no time limit."))
                .done();
    }

    private JComponent auditSection() {
        return new Grid()
                .field("Attack strength:", fld(cStrength, 110))
                .field("Alert threshold:", fld(cThreshold, 110))
                .field("Request delay (ms):", fld(cDelay, 80))
                .field("Active-scan cap (min):", fld(cScanDuration, 80))
                .gap()
                .full(Ui.note("Keep the active-scan cap at 0 so every test runs to completion."))
                .done();
    }

    private JComponent injectionSection() {
        return new Grid()
                .full(sectionLabel("Parameter locations"))
                .full(row(vQuery, vBody, vPath, vHeaders, vCookie))
                .gap()
                .full(sectionLabel("Request body types"))
                .full(row(rMultipart, rXml, rJson, rGwt, rOdata))
                .gap()
                .full(Ui.note("Body types only apply when a request actually carries that content."))
                .done();
    }

    /**
     * A tidy settings grid: labels left-aligned in one column, their fields in a
     * second column that lines up across every row, and full-width rows for
     * checkboxes, section labels and notes. Keeps a whole section on one grid so
     * nothing drifts out of alignment.
     */
    private static final class Grid {
        private final JPanel panel = new JPanel(new GridBagLayout());
        private int row;

        Grid() { panel.setOpaque(false); }

        Grid field(String label, JComponent field) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.LINE_START;
            c.insets = new Insets(5, 0, 5, 12);
            panel.add(Ui.label(label), c);
            c.gridx = 1; c.weightx = 1; c.anchor = GridBagConstraints.LINE_START;
            c.insets = new Insets(5, 0, 5, 0);
            panel.add(field, c);
            row++;
            return this;
        }

        Grid full(JComponent comp) {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = row; c.gridwidth = 2; c.weightx = 1;
            c.anchor = GridBagConstraints.LINE_START; c.fill = GridBagConstraints.HORIZONTAL;
            c.insets = new Insets(5, 0, 5, 0);
            panel.add(comp, c);
            row++;
            return this;
        }

        Grid gap() {
            GridBagConstraints c = new GridBagConstraints();
            c.gridx = 0; c.gridy = row; c.gridwidth = 2;
            panel.add(Box.createVerticalStrut(6), c);
            row++;
            return this;
        }

        JComponent done() {
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.add(panel, BorderLayout.NORTH);
            return wrap;
        }
    }

    /** Fix a field's width so the field column lines up down the grid. */
    private static JComponent fld(JComponent c, int width) {
        Dimension d = c.getPreferredSize();
        Dimension fixed = new Dimension(width, d.height);
        c.setPreferredSize(fixed);
        c.setMinimumSize(fixed);
        return c;
    }

    /** Enable the AJAX budget inputs only while the AJAX spider is ticked. */
    private void syncAjaxEnabled() {
        boolean on = cAjax.isSelected();
        cAjaxDuration.setEnabled(on);
        cAjaxDepth.setEnabled(on);
        cAjaxStates.setEnabled(on);
    }

    private Object[] snapshotCustom() {
        return new Object[]{
                cStrength.getSelectedItem(), cThreshold.getSelectedItem(),
                cCrawlDepth.getValue(), cCrawlDuration.getValue(),
                cSubmitForms.isSelected(), cAjax.isSelected(),
                cAjaxDuration.getValue(), cAjaxDepth.getValue(), cAjaxStates.getValue(),
                vQuery.isSelected(), vBody.isSelected(), vPath.isSelected(), vHeaders.isSelected(), vCookie.isSelected(),
                rMultipart.isSelected(), rXml.isSelected(), rJson.isSelected(), rGwt.isSelected(), rOdata.isSelected(),
                cDelay.getValue(), cScanDuration.getValue()
        };
    }

    private void restoreCustom(Object[] s) {
        int i = 0;
        cStrength.setSelectedItem(s[i++]); cThreshold.setSelectedItem(s[i++]);
        cCrawlDepth.setValue(s[i++]); cCrawlDuration.setValue(s[i++]);
        cSubmitForms.setSelected((Boolean) s[i++]); cAjax.setSelected((Boolean) s[i++]);
        cAjaxDuration.setValue(s[i++]); cAjaxDepth.setValue(s[i++]); cAjaxStates.setValue(s[i++]);
        vQuery.setSelected((Boolean) s[i++]); vBody.setSelected((Boolean) s[i++]); vPath.setSelected((Boolean) s[i++]);
        vHeaders.setSelected((Boolean) s[i++]); vCookie.setSelected((Boolean) s[i++]);
        rMultipart.setSelected((Boolean) s[i++]); rXml.setSelected((Boolean) s[i++]); rJson.setSelected((Boolean) s[i++]);
        rGwt.setSelected((Boolean) s[i++]); rOdata.setSelected((Boolean) s[i++]);
        cDelay.setValue(s[i++]); cScanDuration.setValue(s[i++]);
        syncAjaxEnabled();
    }


    /** A left-aligned row of check boxes. */
    private static JComponent row(JCheckBox... boxes) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));
        p.setOpaque(false);
        for (JCheckBox b : boxes) { b.setOpaque(false); p.add(b); }
        return p;
    }

    private JComponent stepPanel(String title, String subtitle, JComponent body) {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG);
        p.setBorder(Ui.pad(16, 20, 16, 20));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JPanel titles = new JPanel(new BorderLayout());
        titles.setOpaque(false);
        titles.add(Ui.heading(title), BorderLayout.NORTH);
        titles.add(Ui.note(subtitle), BorderLayout.CENTER);
        head.add(titles, BorderLayout.CENTER);
        p.add(head, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private static JLabel sectionLabel(String text) {
        JLabel l = Ui.bold(text);
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    // ---- bottom buttons ----

    private JComponent buildButtons() {
        JButton cancel = Ui.button("Cancel");
        prevButton = Ui.button("Previous");
        nextButton = Ui.button("Next");
        scanButton = Ui.button("Scan");
        scanButton.setEnabled(false);   // greyed until a scan type is chosen
        cancel.addActionListener(e -> { started = false; dispose(); });
        prevButton.addActionListener(e -> selectStep(step - 1));
        nextButton.addActionListener(e -> selectStep(step + 1));
        scanButton.addActionListener(e -> doScan());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID));
        bar.add(cancel);
        bar.add(prevButton);
        bar.add(nextButton);
        bar.add(scanButton);
        return bar;
    }

    private void updateButtons() {
        prevButton.setEnabled(step > 0);
        nextButton.setEnabled(typeChosen && step < STEPS.length - 1);
        boolean scannable = typeChosen;
        scanButton.setEnabled(scannable);
        // Turn the Scan button orange once it is usable, grey before that.
        scanButton.putClientProperty(Theme.PRIMARY, scannable ? Boolean.TRUE : Boolean.FALSE);
        scanButton.repaint();
    }

    private void doScan() {
        if (targetUrl().isEmpty()) {
            selectStep(1);
            JOptionPane.showMessageDialog(this, "Enter at least one URL to scan.",
                    "New scan", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (opentrap.core.HttpService.parse(targetUrl()) == null) {
            selectStep(1);
            JOptionPane.showMessageDialog(this, "Could not parse that URL. Use the form https://host/path.",
                    "New scan", JOptionPane.WARNING_MESSAGE);
            return;
        }
        started = true;
        dispose();
    }

    // ---- icons ----

    /**
     * The rail's step icons: clean 22-pixel outlines with rounded strokes,
     * drawn in the rail's text colour so they read the same selected or not.
     */
    private static Icon railIcon(String kind, Color col) {
        int s = 22;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(col);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case "type" -> {
                // A horn with two sound arcs: the kind of scan being announced.
                java.awt.geom.Path2D.Float horn = new java.awt.geom.Path2D.Float();
                horn.moveTo(3, 8.5); horn.lineTo(7, 8.5); horn.lineTo(12, 4.5); horn.lineTo(12, 17.5);
                horn.lineTo(7, 13.5); horn.lineTo(3, 13.5); horn.closePath();
                g.draw(horn);
                g.draw(new java.awt.geom.Arc2D.Float(10f, 6.5f, 8f, 9f, -50, 100, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Arc2D.Float(10f, 3.5f, 13f, 15f, -50, 100, java.awt.geom.Arc2D.OPEN));
            }
            case "details" -> {
                // A kite-shaped badge with a tick: the target, confirmed.
                java.awt.geom.Path2D.Float kite = new java.awt.geom.Path2D.Float();
                kite.moveTo(11, 2.5); kite.lineTo(19, 9); kite.lineTo(11, 19.5); kite.lineTo(3, 9); kite.closePath();
                g.draw(kite);
                g.drawLine(8, 10, 10, 12);
                g.drawLine(10, 12, 14, 8);
            }
            case "config" -> {
                // A gear: eight rounded teeth around a ring.
                java.awt.geom.Path2D.Float gear = new java.awt.geom.Path2D.Float();
                double cx = 11, cy = 11, ro = 8.2, ri = 6.2;
                int teeth = 8;
                for (int i = 0; i < teeth * 2; i++) {
                    double a = Math.PI * i / teeth - Math.PI / (teeth * 2);
                    double r = (i % 2 == 0) ? ro : ri;
                    double x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
                    if (i == 0) gear.moveTo(x, y); else gear.lineTo(x, y);
                }
                gear.closePath();
                g.draw(gear);
                g.drawOval(8, 8, 6, 6);
            }
            case "login" -> {
                // An arrow entering a bracket: signing in.
                g.drawLine(3, 11, 13, 11);
                g.drawLine(9, 7, 13, 11);
                g.drawLine(9, 15, 13, 11);
                g.drawLine(13, 3, 18, 3); g.drawLine(18, 3, 18, 19); g.drawLine(18, 19, 13, 19);
            }
            case "pool" -> {
                // A pie chart with one slice lifted out.
                g.draw(new java.awt.geom.Arc2D.Float(3f, 4f, 15f, 15f, 90, 270, java.awt.geom.Arc2D.PIE));
                g.draw(new java.awt.geom.Arc2D.Float(6f, 1f, 15f, 15f, 0, 90, java.awt.geom.Arc2D.PIE));
            }
        }
        g.dispose();
        return new ImageIcon(img);
    }

    private static Icon configIcon(String name) {
        int s = 18;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.MUTED);
        g.setStroke(new BasicStroke(1.4f));
        switch (name) {
            case "Lightweight" -> { g.drawOval(2, 2, 13, 13); g.drawLine(8, 8, 8, 4); g.drawLine(8, 8, 12, 9); }
            case "Fast" -> {
                g.fillPolygon(new int[]{2, 2, 8}, new int[]{3, 15, 9}, 3);
                g.fillPolygon(new int[]{8, 8, 14}, new int[]{3, 15, 9}, 3);
            }
            case "Balanced" -> { g.drawLine(9, 2, 9, 15); g.drawLine(3, 5, 15, 5); g.drawOval(2, 5, 3, 3); g.drawOval(13, 5, 3, 3); }
            case "Deep" -> { g.drawArc(2, 1, 14, 8, 0, -180); g.drawArc(4, 5, 10, 7, 0, -180); g.drawArc(6, 9, 6, 6, 0, -180); }
            case "Custom" -> {
                for (int y : new int[]{4, 9, 14}) { g.drawLine(2, y, 16, y); }
                g.fillRect(5, 2, 3, 4); g.fillRect(11, 7, 3, 4); g.fillRect(7, 12, 3, 4);
            }
        }
        g.dispose();
        return new ImageIcon(img);
    }
}
