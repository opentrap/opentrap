package opentrap.dashboard;

import opentrap.core.SuiteContext;
import opentrap.ui.CustomScopePanel;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

/**
 * The New scan wizard: a left step rail and a content area, with Cancel /
 * Previous / Next / Scan along the bottom. Only "Scan type" is shown until a
 * type is chosen; the rail then shows that type's steps. Crawl and audit and
 * Crawl share Scan details, Scan configuration, Application login and Resource
 * pool; API-only scan has API definition and API details instead of details
 * and login. The dialog only gathers configuration; the caller reads the
 * getters and starts the scan.
 */
public class NewScanWizard extends JDialog {

    private static final String[] WEB_STEPS = {
            "Scan type", "Scan details", "Scan configuration", "Application login", "Resource pool"};
    private static final String[] WEB_ICONS = {"type", "details", "config", "login", "pool"};
    private static final String[] API_STEPS = {
            "Scan type", "API definition", "API details", "Scan configuration", "Resource pool"};
    private static final String[] API_ICONS = {"type", "api", "details", "config", "pool"};

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JPanel railPanel = new JPanel();
    private JToggleButton[] rail = new JToggleButton[0];
    private String[] steps = WEB_STEPS;
    private String[] icons = WEB_ICONS;
    private int step;
    private boolean typeChosen;

    private final JRadioButton typeCrawlAudit = new JRadioButton();
    private final JRadioButton typeCrawl = new JRadioButton();
    private final JRadioButton typeApiOnly = new JRadioButton();
    private final JTextArea urls = new JTextArea();
    private final JRadioButton pHttpHttps = Ui.radio("Scan using HTTP & HTTPS", true);
    private final JRadioButton pSpecified = Ui.radio("Scan using my specified protocols", false);
    private final JCheckBox isolated = Ui.check("Run isolated scan", false);
    private CustomScopePanel scopePanel;
    private final ScanConfigPanel webConfig = new ScanConfigPanel(ScanConfigPanel.Mode.CRAWL_AND_AUDIT);
    private final ScanConfigPanel apiConfig = new ScanConfigPanel(ScanConfigPanel.Mode.AUDIT);
    private final ApplicationLoginPanel login = new ApplicationLoginPanel();
    private final ApiDefinitionPanel apiDefinition = new ApiDefinitionPanel();
    private final ApiDetailsPanel apiDetails = new ApiDetailsPanel(() -> selectStepNamed("API definition"));
    private final opentrap.ui.ResourcePoolPanel resourcePool = new opentrap.ui.ResourcePoolPanel();

    private JButton prevButton, nextButton, scanButton;
    private boolean started;

    public NewScanWizard(SuiteContext ctx, Frame owner) {
        super(owner, "New scan", true);
        // Cancel and Scan both dispose; make the window-manager close do the
        // same rather than only hiding this heavy five-step dialog.
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);

        content.add(buildScanType(), "Scan type");
        content.add(buildScanDetails(ctx), "Scan details");
        content.add(stepPanel("Scan configuration",
                "Select the type of scan configuration you want to run. You can adjust it as needed.", webConfig), "Scan configuration");
        content.add(stepPanel("Application login", "Configure how the scan authenticates.", login), "Application login");
        content.add(stepPanel("Resource pool", "Specify the resources the scan may use.", resourcePool), "Resource pool");
        content.add(stepPanel("API definition", "Upload the definition of the API to scan.", apiDefinition), "API definition");
        content.add(stepPanel("API details", "The endpoints the scan will exercise.", apiDetails), "API details");
        content.add(stepPanel("Scan configuration ",
                "Select the audit configuration for the API scan.", apiConfig), "API configuration");
        apiDefinition.onChange(() -> apiDetails.show(apiDefinition.endpoints(), apiDefinition.source(), apiDefinition.baseUrl()));

        railPanel.setLayout(new BoxLayout(railPanel, BoxLayout.Y_AXIS));
        railPanel.setBackground(Theme.SURFACE);
        railPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.GRID));
        JPanel railWrap = new JPanel(new BorderLayout());
        railWrap.add(railPanel, BorderLayout.NORTH);
        railWrap.setBackground(Theme.SURFACE);
        railWrap.setPreferredSize(new Dimension(152, 10));
        buildRail();

        setLayout(new BorderLayout());
        add(railWrap, BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        add(buildButtons(), BorderLayout.SOUTH);
        selectStep(0);
        revealSteps(false);

        setSize(1000, 680);
        setLocationRelativeTo(owner);
    }

    // ---- getters the caller reads after the dialog closes ----

    public boolean started()       { return started; }
    public boolean crawlAndAudit() { return typeCrawlAudit.isSelected() || typeApiOnly.isSelected(); }
    public boolean apiOnly()       { return typeApiOnly.isSelected(); }
    public boolean isolated()      { return isolated.isSelected(); }
    public int threads()           { return resourcePool.concurrency(); }
    public opentrap.core.ResourcePool resourcePool() { return resourcePool.selected(); }
    public ApplicationLoginPanel login() { return login; }
    public ApiDefinitionPanel apiDefinition() { return apiDefinition; }

    public String targetUrl() {
        java.util.List<String> all = targetUrls();
        return all.isEmpty() ? "" : all.get(0);
    }

    /** Every URL to scan: the list typed in, or the API's endpoints. */
    public java.util.List<String> targetUrls() {
        if (typeApiOnly.isSelected()) return apiDefinition.seedUrls();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : urls.getText().split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** The engine settings implied by the chosen configuration. */
    public opentrap.engine.ScanConfig config() {
        return typeApiOnly.isSelected() ? apiConfig.config() : webConfig.config();
    }

    /** The scope the user drew up under "Detailed scope configuration", or null for the default. */
    public opentrap.core.Scope customScope() {
        return scopePanel == null || scopePanel.isEmpty() ? null : scopePanel.scope();
    }

    // ---- left rail ----

    private void buildRail() {
        railPanel.removeAll();
        rail = new JToggleButton[steps.length];
        ButtonGroup g = new ButtonGroup();
        for (int i = 0; i < steps.length; i++) {
            final int idx = i;
            JToggleButton b = new JToggleButton(
                    "<html><div style='text-align:center'>" + steps[i].replace(" ", "<br>") + "</div></html>");
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
            railPanel.add(b);
        }
        railPanel.revalidate();
        railPanel.repaint();
    }

    private void revealSteps(boolean show) {
        for (int k = 1; k < rail.length; k++) rail[k].setVisible(show);
        railPanel.revalidate();
        railPanel.repaint();
    }

    private String cardFor(int i) {
        String s = steps[i];
        return "Scan configuration".equals(s) && typeApiOnly.isSelected() ? "API configuration" : s;
    }

    private void selectStepNamed(String name) {
        for (int i = 0; i < steps.length; i++) if (steps[i].equals(name)) { selectStep(i); return; }
    }

    private void selectStep(int i) {
        if (!typeChosen) i = 0;
        step = Math.max(0, Math.min(steps.length - 1, i));
        for (int k = 0; k < rail.length; k++) {
            boolean on = k == step;
            rail[k].setSelected(on);
            rail[k].setBackground(on ? Ui.ACCENT : Theme.SURFACE);
            rail[k].setForeground(on ? Color.WHITE : Theme.TEXT);
            rail[k].setIcon(railIcon(icons[k], on ? Color.WHITE : Theme.MUTED));
        }
        cards.show(content, cardFor(step));
        updateButtons();
    }

    // ---- step panels ----

    private JComponent buildScanType() {
        ButtonGroup g = new ButtonGroup();
        g.add(typeCrawlAudit); g.add(typeCrawl); g.add(typeApiOnly);
        JPanel cardsRow = new JPanel(new GridLayout(1, 3, 16, 0));
        cardsRow.setOpaque(false);
        cardsRow.add(typeCard(typeCrawlAudit, "Crawl and audit",
                "Maps the attack surface and identifies vulnerabilities. Scans the web application and any detected APIs."));
        cardsRow.add(typeCard(typeCrawl, "Crawl",
                "Maps the attack surface of the web application and any detected APIs."));
        cardsRow.add(typeCard(typeApiOnly, "API-only scan",
                "Offers additional configuration options. Scans the endpoints of an uploaded API definition."));
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(cardsRow, BorderLayout.NORTH);
        return stepPanel("Scan type", "Select the type of scan you'd like to run.", wrap);
    }

    private JComponent typeCard(JRadioButton radio, String title, String desc) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(Theme.BG);
        card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Ui.GRID), Ui.pad(14, 14, 14, 14)));
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        head.setOpaque(false);
        radio.setOpaque(false);
        head.add(radio);
        head.add(Ui.bold(title));
        card.add(head, BorderLayout.NORTH);
        card.add(Ui.note(desc), BorderLayout.CENTER);
        card.setPreferredSize(new Dimension(230, 110));
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter pick = new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { radio.setSelected(true); onTypeChosen(); }
        };
        addClickToTree(card, pick);
        radio.addActionListener(e -> onTypeChosen());
        return card;
    }

    private static void addClickToTree(Component c, java.awt.event.MouseListener l) {
        c.addMouseListener(l);
        if (c instanceof Container cont) for (Component child : cont.getComponents()) addClickToTree(child, l);
    }

    /** A scan type was chosen: show its steps and enable Scan. */
    private void onTypeChosen() {
        String[] want = typeApiOnly.isSelected() ? API_STEPS : WEB_STEPS;
        if (!typeChosen || want != steps) {
            steps = want;
            icons = typeApiOnly.isSelected() ? API_ICONS : WEB_ICONS;
            buildRail();
        }
        typeChosen = true;
        revealSteps(true);
        selectStep(step);
        updateButtons();
    }

    private JComponent buildScanDetails(SuiteContext ctx) {
        urls.setFont(Ui.MONO);
        StringBuilder sb = new StringBuilder();
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
        sp.setPreferredSize(new Dimension(500, 160));
        JTextArea urlsNote = Ui.note("OpenTRAP will begin crawling from these URLs, and by default includes everything "
                + "beneath the specified URLs' folders. One URL per line.");

        ButtonGroup pg = new ButtonGroup();
        pg.add(pHttpHttps); pg.add(pSpecified);
        JPanel protoRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        protoRow.setOpaque(false);
        protoRow.add(pHttpHttps); protoRow.add(Box.createHorizontalStrut(18)); protoRow.add(pSpecified);

        // Detailed scope configuration: an expander revealing the full scope control.
        scopePanel = new CustomScopePanel(sb.toString());
        scopePanel.setVisible(false);
        JButton scopeToggle = new JButton("› Detailed scope configuration");
        scopeToggle.setFont(Ui.PLAIN);
        scopeToggle.setBorder(Ui.pad(2, 0, 2, 0));
        scopeToggle.setContentAreaFilled(false);
        scopeToggle.setFocusPainted(false);
        scopeToggle.setHorizontalAlignment(SwingConstants.LEFT);
        scopeToggle.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        scopeToggle.addActionListener(e -> {
            boolean now = !scopePanel.isVisible();
            scopePanel.setVisible(now);
            scopeToggle.setText((now ? "⌄ " : "› ") + "Detailed scope configuration");
        });

        isolated.setToolTipText("the scan's findings are kept with the task rather than merged into the site map and issue log");

        JPanel body = new JPanel(new GridBagLayout());
        body.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0; c.anchor = GridBagConstraints.WEST; c.fill = GridBagConstraints.HORIZONTAL; c.weightx = 1;
        int row = 0;
        c.gridy = row++; c.fill = GridBagConstraints.BOTH; c.ipady = 120; body.add(sp, c);
        c.fill = GridBagConstraints.HORIZONTAL; c.ipady = 0;
        c.gridy = row++; c.insets = new Insets(6, 0, 14, 0); body.add(urlsNote, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0); body.add(sectionLabel("Protocol settings"), c);
        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0); body.add(protoRow, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0); body.add(scopeToggle, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 14, 0); body.add(scopePanel, c);
        c.gridy = row++; c.insets = new Insets(0, 0, 6, 0); body.add(sectionLabel("Advanced scan parameters"), c);
        c.gridy = row++; c.insets = new Insets(0, 0, 4, 0);
        body.add(Ui.note("Results from isolated scans do not appear in the Target site map or Issue activity log."), c);
        c.gridy = row++; c.insets = new Insets(0, 0, 0, 0); body.add(isolated, c);
        c.gridy = row; c.weighty = 1; c.fill = GridBagConstraints.BOTH; body.add(Box.createGlue(), c);

        return stepPanel("URLs to scan", "Define the URLs to scan. OpenTRAP will begin crawling from these URLs.", Ui.scroll(body));
    }

    private JComponent stepPanel(String title, String subtitle, JComponent body) {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG);
        p.setBorder(Ui.pad(16, 20, 16, 20));
        JPanel titles = new JPanel(new BorderLayout());
        titles.setOpaque(false);
        titles.add(Ui.heading(title), BorderLayout.NORTH);
        titles.add(Ui.note(subtitle), BorderLayout.CENTER);
        p.add(titles, BorderLayout.NORTH);
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
        JButton help = opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.help(), "About the New scan wizard", () ->
                JOptionPane.showMessageDialog(this, "Choose a scan type, then work through the steps on the left.\n"
                        + "Scan starts the task on the dashboard.", "New scan", JOptionPane.INFORMATION_MESSAGE));
        JButton cancel = Ui.button("Cancel");
        prevButton = Ui.button("← Previous");
        nextButton = Ui.button("Next →");
        scanButton = Ui.button("Scan");
        scanButton.setEnabled(false);
        cancel.addActionListener(e -> { started = false; dispose(); });
        prevButton.addActionListener(e -> selectStep(step - 1));
        nextButton.addActionListener(e -> selectStep(step + 1));
        scanButton.addActionListener(e -> doScan());

        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID));
        JPanel l = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        l.add(help);
        JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        r.add(cancel); r.add(prevButton); r.add(nextButton); r.add(scanButton);
        bar.add(l, BorderLayout.WEST);
        bar.add(r, BorderLayout.EAST);
        return bar;
    }

    private void updateButtons() {
        prevButton.setEnabled(step > 0);
        nextButton.setEnabled(typeChosen && step < steps.length - 1);
        scanButton.setEnabled(typeChosen);
        scanButton.putClientProperty(Theme.PRIMARY, typeChosen ? Boolean.TRUE : Boolean.FALSE);
        scanButton.repaint();
    }

    private void doScan() {
        if (typeApiOnly.isSelected()) {
            if (apiDefinition.endpoints().isEmpty()) {
                selectStepNamed("API definition");
                JOptionPane.showMessageDialog(this, "Upload an API definition first.", "New scan", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (targetUrls().isEmpty()) {
                selectStepNamed("API definition");
                JOptionPane.showMessageDialog(this, "The definition has no base URL. Enter the definition's URL so the server can be found.",
                        "New scan", JOptionPane.WARNING_MESSAGE);
                return;
            }
        } else {
            if (targetUrl().isEmpty()) {
                selectStep(1);
                JOptionPane.showMessageDialog(this, "Enter at least one URL to scan.", "New scan", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (opentrap.core.HttpService.parse(targetUrl()) == null) {
                selectStep(1);
                JOptionPane.showMessageDialog(this, "Could not parse that URL. Use the form https://host/path.", "New scan", JOptionPane.WARNING_MESSAGE);
                return;
            }
        }
        started = true;
        dispose();
    }

    // ---- icons ----

    static Icon railIcon(String kind, Color col) {
        int s = 22;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(col);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        switch (kind) {
            case "type" -> {
                java.awt.geom.Path2D.Float horn = new java.awt.geom.Path2D.Float();
                horn.moveTo(3, 8.5); horn.lineTo(7, 8.5); horn.lineTo(12, 4.5); horn.lineTo(12, 17.5);
                horn.lineTo(7, 13.5); horn.lineTo(3, 13.5); horn.closePath();
                g.draw(horn);
                g.draw(new java.awt.geom.Arc2D.Float(10f, 6.5f, 8f, 9f, -50, 100, java.awt.geom.Arc2D.OPEN));
                g.draw(new java.awt.geom.Arc2D.Float(10f, 3.5f, 13f, 15f, -50, 100, java.awt.geom.Arc2D.OPEN));
            }
            case "details" -> {
                java.awt.geom.Path2D.Float kite = new java.awt.geom.Path2D.Float();
                kite.moveTo(11, 2.5); kite.lineTo(19, 9); kite.lineTo(11, 19.5); kite.lineTo(3, 9); kite.closePath();
                g.draw(kite);
                g.drawLine(8, 10, 10, 12);
                g.drawLine(10, 12, 14, 8);
            }
            case "config" -> {
                java.awt.geom.Path2D.Float gear = new java.awt.geom.Path2D.Float();
                double cx = 11, cy = 11, ro = 8.2, ri = 6.2;
                for (int i = 0; i < 16; i++) {
                    double a = Math.PI * i / 8 - Math.PI / 16;
                    double r = (i % 2 == 0) ? ro : ri;
                    double x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
                    if (i == 0) gear.moveTo(x, y); else gear.lineTo(x, y);
                }
                gear.closePath();
                g.draw(gear);
                g.drawOval(8, 8, 6, 6);
            }
            case "login" -> {
                g.drawLine(3, 11, 13, 11);
                g.drawLine(9, 7, 13, 11);
                g.drawLine(9, 15, 13, 11);
                g.drawLine(13, 3, 18, 3); g.drawLine(18, 3, 18, 19); g.drawLine(18, 19, 13, 19);
            }
            case "pool" -> {
                g.draw(new java.awt.geom.Arc2D.Float(3f, 4f, 15f, 15f, 90, 270, java.awt.geom.Arc2D.PIE));
                g.draw(new java.awt.geom.Arc2D.Float(6f, 1f, 15f, 15f, 0, 90, java.awt.geom.Arc2D.PIE));
            }
            case "api" -> {
                // Curly braces: a definition document.
                g.drawArc(3, 3, 6, 8, 90, 180);
                g.drawArc(3, 11, 6, 8, 90, 180);
                g.drawArc(13, 3, 6, 8, -90, 180);
                g.drawArc(13, 11, 6, 8, -90, 180);
                g.drawLine(9, 11, 13, 11);
            }
        }
        g.dispose();
        return new ImageIcon(img);
    }
}
