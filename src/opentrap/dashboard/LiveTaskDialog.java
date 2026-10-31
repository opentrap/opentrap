package opentrap.dashboard;

import opentrap.core.ScanTask;
import opentrap.core.SuiteContext;
import opentrap.ui.CustomScopePanel;
import opentrap.ui.Glyphs;
import opentrap.ui.ResourcePoolPanel;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/**
 * "New live task" and "Edit live audit task": a three-step dialog (Scan
 * details, Scan configuration, Resource pool) with a left icon rail. A live
 * task watches traffic from the chosen tools and either audits it (live
 * audit) or adds what it sees to the site map (live passive crawl), within
 * Everything, the suite scope or a custom scope.
 */
public final class LiveTaskDialog extends JDialog {

    /** What the dialog collected. */
    public record Result(boolean passiveCrawl, boolean proxy, boolean repeater, boolean intruder,
                         String scopeMode, opentrap.core.Scope customScope, boolean dedupe,
                         opentrap.engine.ScanConfig config, String configName, String passiveSummary,
                         opentrap.core.ResourcePool pool) {
        public String title() {
            String tools = (proxy ? "Proxy" : "") + (repeater ? (proxy ? ", " : "") + "Repeater" : "")
                    + (intruder ? ((proxy || repeater) ? ", " : "") + "Intruder" : "");
            if (tools.isEmpty()) tools = "no tools";
            String where = switch (scopeMode) { case "suite" -> "in-scope traffic"; case "custom" -> "custom scope"; default -> "all traffic"; };
            return (passiveCrawl ? "Live passive crawl from " : "Live audit from ") + tools + " (" + where + ")";
        }
        public String configuration() {
            return passiveCrawl ? "Live passive crawl" : "Audit checks - " + (configName.equals("Custom") ? "custom" : configName.toLowerCase());
        }
    }

    private static final String[] STEPS = {"Scan details", "Scan configuration", "Resource pool"};
    private static final String[] ICONS = {"details", "config", "pool"};

    private final CardLayout cards = new CardLayout();
    private final JPanel content = new JPanel(cards);
    private final JToggleButton[] rail = new JToggleButton[STEPS.length];
    private int step;
    private boolean saved;

    private final JRadioButton typeAudit = Ui.radio("Live audit", true);
    private final JRadioButton typeCrawl = Ui.radio("Live passive crawl", false);
    private final JComboBox<String> predefined = Ui.combo(new String[]{"Choose predefined task...",
            "Live audit from Proxy (all traffic)", "Live audit from Proxy (in-scope)", "Live passive crawl from Proxy (all traffic)",
            "Live passive crawl from Proxy (in-scope)"});
    private final JCheckBox toolProxy = Ui.check("Proxy", false);
    private final JCheckBox toolRepeater = Ui.check("Repeater", false);
    private final JCheckBox toolIntruder = Ui.check("Intruder", false);
    private final JRadioButton scopeEverything = Ui.radio("Everything", true);
    private final JRadioButton scopeSuite = Ui.radio("Suite scope", false);
    private final JRadioButton scopeCustom = Ui.radio("Custom scope", false);
    private final CustomScopePanel customScope = new CustomScopePanel("");
    private final JCheckBox dedupe = Ui.check("Ignore duplicate items based on URL and parameter names", false);
    private final ScanConfigPanel auditConfig = new ScanConfigPanel(ScanConfigPanel.Mode.AUDIT);
    private final ScanConfigPanel crawlConfig = new ScanConfigPanel(ScanConfigPanel.Mode.PASSIVE_CRAWL);
    private final CardLayout configCards = new CardLayout();
    private final JPanel configBody = new JPanel(configCards);
    private final ResourcePoolPanel pool = new ResourcePoolPanel();
    private JButton prevButton, nextButton;

    /** @param editing an existing task to edit, or null for a new one */
    public LiveTaskDialog(SuiteContext ctx, Frame owner, ScanTask editing) {
        super(owner, editing == null ? "New live task" : (editing.type() == ScanTask.Type.LIVE_PASSIVE ? "Edit live audit task" : "Edit live task"), true);

        content.add(buildDetails(editing), STEPS[0]);
        content.add(buildConfig(), STEPS[1]);
        content.add(stepPanel("Resource pool", "Specify the resources the task may use.", pool), STEPS[2]);

        setLayout(new BorderLayout());
        add(buildRail(), BorderLayout.WEST);
        add(content, BorderLayout.CENTER);
        add(buildButtons(editing), BorderLayout.SOUTH);
        selectStep(0);
        setSize(980, 700);
        setLocationRelativeTo(owner);

        if (editing != null) {
            // An existing task's type cannot change; its tool scope reads from its title.
            typeAudit.setEnabled(false); typeCrawl.setEnabled(false);
            toolProxy.setSelected(editing.title().contains("Proxy"));
            toolRepeater.setSelected(editing.title().contains("Repeater"));
            toolIntruder.setSelected(editing.title().contains("Intruder"));
            if (editing.title().contains("in-scope")) scopeSuite.setSelected(true);
            dedupe.setSelected(true);
        }
    }

    public boolean saved() { return saved; }

    public Result result() {
        return new Result(typeCrawl.isSelected(), toolProxy.isSelected(), toolRepeater.isSelected(), toolIntruder.isSelected(),
                scopeEverything.isSelected() ? "everything" : scopeSuite.isSelected() ? "suite" : "custom",
                scopeCustom.isSelected() ? customScope.scope() : null, dedupe.isSelected(),
                auditConfig.config(), auditConfig.presetName(), crawlConfig.passiveCrawlSummary(), pool.selected());
    }

    private JComponent buildDetails(ScanTask editing) {
        ButtonGroup types = new ButtonGroup();
        types.add(typeAudit); types.add(typeCrawl);
        ButtonGroup scopes = new ButtonGroup();
        scopes.add(scopeEverything); scopes.add(scopeSuite); scopes.add(scopeCustom);

        predefined.addActionListener(e -> {
            String p = String.valueOf(predefined.getSelectedItem());
            if (p.startsWith("Choose")) return;
            typeCrawl.setSelected(p.startsWith("Live passive crawl"));
            typeAudit.setSelected(!typeCrawl.isSelected());
            toolProxy.setSelected(true);
            (p.contains("in-scope") ? scopeSuite : scopeEverything).setSelected(true);
            syncType();
        });
        typeAudit.addItemListener(e -> syncType());

        JPanel typeRow = new JPanel(new BorderLayout());
        typeRow.setOpaque(false);
        typeRow.add(Ui.row(typeAudit, Box.createHorizontalStrut(18), typeCrawl), BorderLayout.WEST);
        typeRow.add(predefined, BorderLayout.EAST);

        customScope.setVisible(false);
        scopeCustom.addItemListener(e -> { customScope.setVisible(scopeCustom.isSelected()); content.revalidate(); });

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        left(body, Ui.bold("Task type"));
        left(body, typeRow);
        body.add(Box.createVerticalStrut(14));
        left(body, Ui.bold("Tools scope"));
        left(body, Ui.note("Monitor traffic from these tools."));
        left(body, Ui.row(toolProxy, toolRepeater, toolIntruder));
        body.add(Box.createVerticalStrut(14));
        left(body, Ui.bold("URL scope"));
        left(body, Ui.row(scopeEverything, scopeSuite, scopeCustom));
        left(body, customScope);
        body.add(Box.createVerticalStrut(14));
        left(body, Ui.bold("Deduplication"));
        left(body, dedupe);
        return stepPanel("Scan details", "Choose what the live task watches and where.", Ui.scroll(body));
    }

    private void syncType() {
        configCards.show(configBody, typeCrawl.isSelected() ? "crawl" : "audit");
    }

    private JComponent buildConfig() {
        configBody.setOpaque(false);
        configBody.add(auditConfig, "audit");
        configBody.add(crawlConfig, "crawl");
        return stepPanel("Scan configuration", "Select the configuration the live task runs with.", configBody);
    }

    private static void left(JPanel body, JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        if (c instanceof JTextArea ta) Ui.sizeNote(ta, 600);
        body.add(c);
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

    private JComponent buildRail() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBackground(Theme.SURFACE);
        p.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, Ui.GRID));
        ButtonGroup g = new ButtonGroup();
        for (int i = 0; i < STEPS.length; i++) {
            final int idx = i;
            JToggleButton b = new JToggleButton("<html><div style='text-align:center'>" + STEPS[i].replace(" ", "<br>") + "</div></html>");
            b.setFont(Ui.PLAIN); b.setFocusable(false); b.setContentAreaFilled(false); b.setOpaque(true);
            b.setBackground(Theme.SURFACE);
            b.setHorizontalTextPosition(SwingConstants.CENTER); b.setVerticalTextPosition(SwingConstants.BOTTOM);
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

    private void selectStep(int i) {
        step = Math.max(0, Math.min(STEPS.length - 1, i));
        for (int k = 0; k < rail.length; k++) {
            boolean on = k == step;
            rail[k].setSelected(on);
            rail[k].setBackground(on ? Ui.ACCENT : Theme.SURFACE);
            rail[k].setForeground(on ? Color.WHITE : Theme.TEXT);
            rail[k].setIcon(NewScanWizard.railIcon(ICONS[k], on ? Color.WHITE : Theme.MUTED));
        }
        cards.show(content, STEPS[step]);
        prevButton.setEnabled(step > 0);
        nextButton.setEnabled(step < STEPS.length - 1);
    }

    private JComponent buildButtons(ScanTask editing) {
        JButton help = Glyphs.button(Glyphs.help(), "About live tasks", () -> JOptionPane.showMessageDialog(this,
                "A live task watches traffic from the chosen tools as it happens.\nA live audit runs checks on it; "
              + "a live passive crawl adds what it sees to the site map.", "Live task", JOptionPane.INFORMATION_MESSAGE));
        JButton cancel = Ui.button("Cancel");
        prevButton = Ui.button("← Previous");
        nextButton = Ui.button("Next →");
        JButton save = Ui.primaryButton("Save", editing == null ? "create the live task" : "save the changes");
        cancel.addActionListener(e -> { saved = false; dispose(); });
        prevButton.addActionListener(e -> selectStep(step - 1));
        nextButton.addActionListener(e -> selectStep(step + 1));
        save.addActionListener(e -> {
            if (!toolProxy.isSelected() && !toolRepeater.isSelected() && !toolIntruder.isSelected()) {
                selectStep(0);
                JOptionPane.showMessageDialog(this, "Choose at least one tool to watch.", getTitle(), JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (scopeCustom.isSelected() && customScope.isEmpty()) {
                selectStep(0);
                JOptionPane.showMessageDialog(this, "Add at least one URL prefix to the custom scope.", getTitle(), JOptionPane.WARNING_MESSAGE);
                return;
            }
            saved = true;
            dispose();
        });
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID));
        JPanel l = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); l.add(help);
        JPanel r = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6)); r.add(cancel); r.add(prevButton); r.add(nextButton); r.add(save);
        bar.add(l, BorderLayout.WEST); bar.add(r, BorderLayout.EAST);
        return bar;
    }
}
