package opentrap.dashboard;

import opentrap.core.SuiteContext;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/**
 * The New scan wizard, laid out like Burp's: a left step rail (Scan type, Scan
 * details, Scan configuration, Application login, Resource pool) and a content
 * area, with Cancel / Previous / Next / Scan along the bottom. The dialog only
 * gathers configuration; the caller reads the getters and starts the scan.
 */
public class NewScanWizard extends JDialog {

    private static final String[] STEPS = {
            "Scan type", "Scan details", "Scan configuration", "Application login", "Resource pool"
    };

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JToggleButton[] rail = new JToggleButton[STEPS.length];
    private int step;

    // Collected configuration.
    private final JRadioButton typeCrawlAudit = new JRadioButton("", true);
    private final JRadioButton typeCrawl = new JRadioButton("");
    private final JTextArea urls = new JTextArea();
    private final ButtonGroup configGroup = new ButtonGroup();
    private String configPreset = "Balanced";
    private final JSpinner threads = new JSpinner(new SpinnerNumberModel(10, 1, 50, 1));

    private boolean started;

    public NewScanWizard(SuiteContext ctx, Frame owner) {
        super(owner, "New scan", true);

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

        setSize(900, 560);
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

    public int crawlDepth() {
        return switch (configPreset) { case "Lightweight" -> 3; case "Deep" -> 0; default -> 5; };
    }
    public int crawlDuration() {
        return switch (configPreset) { case "Lightweight" -> 15; case "Deep" -> 0; default -> 60; };
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
            JToggleButton b = new JToggleButton("<html><div style='text-align:center'>" + STEPS[i] + "</div></html>");
            b.setFont(Ui.PLAIN);
            b.setFocusable(false);
            b.setContentAreaFilled(false);
            b.setOpaque(true);
            b.setBackground(Theme.SURFACE);
            b.setBorder(Ui.pad(14, 18, 14, 18));
            b.setAlignmentX(Component.CENTER_ALIGNMENT);
            b.setMaximumSize(new Dimension(150, 70));
            b.addActionListener(e -> selectStep(idx));
            g.add(b);
            rail[i] = b;
            p.add(b);
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.NORTH);
        wrap.setBackground(Theme.SURFACE);
        return wrap;
    }

    private void selectStep(int i) {
        step = Math.max(0, Math.min(STEPS.length - 1, i));
        for (int k = 0; k < rail.length; k++) {
            boolean on = k == step;
            rail[k].setSelected(on);
            rail[k].setBackground(on ? Ui.ACCENT : Theme.SURFACE);
            rail[k].setForeground(on ? Color.WHITE : Theme.TEXT);
        }
        cards.show(content, STEPS[step]);
    }

    // ---- step panels ----

    private JComponent buildScanType() {
        ButtonGroup g = new ButtonGroup();
        g.add(typeCrawlAudit);
        g.add(typeCrawl);
        JPanel cardsRow = new JPanel(new GridLayout(1, 2, 16, 0));
        cardsRow.setOpaque(false);
        cardsRow.add(typeCard(typeCrawlAudit, "Crawl and audit",
                "Maps the attack surface and identifies vulnerabilities. Scans the web application."));
        cardsRow.add(typeCard(typeCrawl, "Crawl",
                "Maps the attack surface of the web application without auditing for vulnerabilities."));
        return stepPanel("Scan type", "Select the type of scan you'd like to run.", cardsRow);
    }

    private JComponent typeCard(JRadioButton radio, String title, String desc) {
        JPanel card = new JPanel(new BorderLayout(0, 6));
        card.setBackground(Theme.BG);
        card.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.GRID), Ui.pad(14, 14, 14, 14)));
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        head.setOpaque(false);
        radio.setOpaque(false);
        head.add(radio);
        JLabel titleLabel = Ui.bold(title);
        head.add(titleLabel);
        JTextArea d = Ui.note(desc);
        card.add(head, BorderLayout.NORTH);
        card.add(d, BorderLayout.CENTER);
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter pick = new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) { radio.setSelected(true); }
        };
        // Clicking anywhere on the card, including its title and description text,
        // selects it, not just the radio button.
        for (Component c : new Component[]{card, head, titleLabel, d}) {
            c.addMouseListener(pick);
        }
        return card;
    }

    private JComponent buildScanDetails(SuiteContext ctx) {
        urls.setFont(Ui.MONO);
        // Prefill from scope if it is set; otherwise leave empty.
        StringBuilder sb = new StringBuilder();
        try {
            for (var r : ctx.scope().include()) {
                String host = r.host == null ? "" : r.host.replaceAll("[\\^$]", "");
                if (!host.isBlank()) sb.append("https://").append(host).append("/\n");
            }
        } catch (Exception ignored) {}
        urls.setText(sb.toString());

        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        JScrollPane sp = Ui.scroll(urls);
        sp.setPreferredSize(new Dimension(500, 260));
        body.add(sp, BorderLayout.CENTER);
        body.add(Ui.note("Burp will begin crawling from these URLs, and by default includes "
                + "everything beneath the specified URLs' folders. One URL per line."), BorderLayout.SOUTH);
        return stepPanel("URLs to scan", "Define the URLs to scan.", body);
    }

    private JComponent buildScanConfiguration() {
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setOpaque(false);
        list.add(configRow("Lightweight", "Gain fast feedback on a site's security. Shallow crawl, quick audit.", false));
        list.add(configRow("Balanced", "A balance between coverage and speed. The default.", true));
        list.add(configRow("Deep", "Comprehensive coverage. Unlimited crawl depth; takes longer.", false));
        return stepPanel("Scan configuration",
                "Select the scan configuration to run. Lightweight is fastest; Deep is most thorough.", list);
    }

    private JComponent configRow(String name, String desc, boolean selected) {
        JRadioButton r = new JRadioButton("", selected);
        r.setOpaque(false);
        configGroup.add(r);
        r.addActionListener(e -> { if (r.isSelected()) configPreset = name; });
        JPanel row = new JPanel(new BorderLayout(8, 0));
        row.setOpaque(false);
        row.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.GRID), Ui.pad(8, 4, 8, 4)));
        row.add(r, BorderLayout.WEST);
        JPanel text = new JPanel(new BorderLayout());
        text.setOpaque(false);
        JLabel nameLabel = Ui.bold(name);
        JTextArea descArea = Ui.note(desc);
        text.add(nameLabel, BorderLayout.NORTH);
        text.add(descArea, BorderLayout.CENTER);
        row.add(text, BorderLayout.CENTER);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
        // Click anywhere on the row (not just the radio) to select it, like Burp.
        row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        java.awt.event.MouseAdapter pick = new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                r.setSelected(true);
                configPreset = name;
            }
        };
        for (Component c : new Component[]{row, text, nameLabel, descArea}) {
            c.addMouseListener(pick);
        }
        return row;
    }

    private JComponent buildApplicationLogin() {
        JPanel box = new JPanel(new GridBagLayout());
        box.setOpaque(false);
        JLabel msg = new JLabel("<html><div style='text-align:center'><b>Application login</b><br><br>"
                + "Recorded login sequences and credential-based login are <b>coming soon</b>.<br>"
                + "This section is under construction.</div></html>");
        msg.setFont(Ui.PLAIN);
        msg.setForeground(Theme.MUTED);
        box.add(msg);
        return stepPanel("Application login", "Configure how the scan authenticates (not yet available).", box);
    }

    private JComponent buildResourcePool() {
        threads.setFont(Ui.PLAIN);
        threads.setPreferredSize(new Dimension(70, threads.getPreferredSize().height));
        threads.setMaximumSize(threads.getPreferredSize());
        // Keep the spinner at its natural width rather than stretching across the
        // form, which looks wrong for a small number field.
        JPanel spinnerWrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        spinnerWrap.setOpaque(false);
        spinnerWrap.add(threads);
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setOpaque(false);
        body.add(Ui.form("Concurrent requests:", spinnerWrap), BorderLayout.NORTH);
        body.add(Ui.note("The number of requests the scan engine sends at once. Lower this to "
                + "be gentler on the target; raise it to scan faster."), BorderLayout.CENTER);
        return stepPanel("Resource pool",
                "Specify the resources the scan may use.", body);
    }

    private JComponent stepPanel(String title, String subtitle, JComponent body) {
        JPanel p = new JPanel(new BorderLayout(0, 10));
        p.setBackground(Theme.BG);
        p.setBorder(Ui.pad(16, 20, 16, 20));
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(Ui.heading(title), BorderLayout.NORTH);
        head.add(Ui.note(subtitle), BorderLayout.CENTER);
        p.add(head, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    // ---- bottom buttons ----

    private JComponent buildButtons() {
        JButton cancel = Ui.button("Cancel");
        JButton prev = Ui.button("Previous");
        JButton next = Ui.button("Next");
        JButton scan = Ui.primaryButton("Scan", "start the scan with this configuration");
        cancel.addActionListener(e -> { started = false; dispose(); });
        prev.addActionListener(e -> selectStep(step - 1));
        next.addActionListener(e -> selectStep(step + 1));
        scan.addActionListener(e -> doScan());

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID));
        bar.add(cancel);
        bar.add(prev);
        bar.add(next);
        bar.add(scan);
        return bar;
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
}
