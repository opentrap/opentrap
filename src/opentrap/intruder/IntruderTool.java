package opentrap.intruder;

import opentrap.core.HttpService;
import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.ui.Ui;
import opentrap.ui.ResourcePoolPanel;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The Intruder tool: automated customised attacks over a single shared attack
 * configuration. A request is loaded into the target and positions tabs, payload
 * sets are configured on the payloads tab, and starting an attack opens a
 * results window that fills in as responses arrive.
 */
public class IntruderTool implements ToolModule {

    private final SuiteContext ctx;
    private final TargetPanel target = new TargetPanel();
    private final PositionsPanel positions = new PositionsPanel();
    private final PayloadsPanel payloads = new PayloadsPanel();
    private final OptionsPanel options = new OptionsPanel();
    private final AtomicInteger attackCounter = new AtomicInteger(0);

    public IntruderTool(SuiteContext ctx) {
        this.ctx = ctx;
        positions.onChange(() ->
                payloads.setAttack(positions.attackType(), positions.positionCount()));
        positions.setStartAction(this::startAttack);
        payloads.setStartAction(this::startAttack);
        payloads.setAttack(positions.attackType(), positions.positionCount());
    }

    @Override public String name() { return "intruder"; }

    private final ResourcePoolPanel resourcePool = new ResourcePoolPanel();

    @Override public boolean alwaysTabbed() { return false; }

    @Override public void init(SuiteContext ctx) {
        // Settings > Intruder holds the defaults a new attack starts with; the
        // per-attack values stay on this tab's Settings pane.
        options.applyDefaults(ctx.settings());
        opentrap.ui.SettingsDialog.register("Intruder", () -> new IntruderSettingsPanel(ctx.settings()));
        ctx.registerSink("intruder", (svc, req, resp, cap) ->
                SwingUtilities.invokeLater(() -> {
                    target.set(svc);
                    positions.setRequest(svc, req);
                    payloads.setAttack(positions.attackType(), positions.positionCount());
                }));
    }

    @Override public Tab[] tabs() {
        // Single-view layout, as in the reference: the attack header, target row
        // and positions editor on the left; on the right the Payloads, Resource
        // pool and Settings panes, switched by a vertical tab strip on the edge.
        positions.setTargetRow(target);

        final CardLayout cards = new CardLayout();
        final JPanel panes = new JPanel(cards);
        panes.setOpaque(false);
        JPanel strip = new JPanel();
        strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
        strip.setOpaque(false);
        strip.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Ui.GRID));
        ButtonGroup group = new ButtonGroup();
        Object[][] items = {
            {"Payloads", payloads, opentrap.ui.Glyphs.section(14, opentrap.ui.Theme.ICON)},
            {"Resource pool", resourcePool, opentrap.ui.Glyphs.clock(14, opentrap.ui.Theme.ICON)},
            {"Settings", options, opentrap.ui.Glyphs.gear(14, opentrap.ui.Theme.ICON)},
        };
        for (Object[] it : items) {
            final String name = (String) it[0];
            panes.add((Component) it[1], name);
            opentrap.ui.VerticalTab t = new opentrap.ui.VerticalTab(name, (javax.swing.Icon) it[2]);
            t.addActionListener(e -> cards.show(panes, name));
            group.add(t);
            strip.add(t);
            strip.add(Box.createVerticalStrut(6));
        }
        ((AbstractButton) strip.getComponent(0)).setSelected(true);
        strip.add(Box.createVerticalGlue());

        JPanel right = new JPanel(new BorderLayout());
        right.add(panes, BorderLayout.CENTER);
        right.add(strip, BorderLayout.EAST);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, positions, right);
        split.setDividerSize(5);
        Ui.balance(split, 0.62);
        return new Tab[]{ new Tab("intruder", split) };
    }

    /** Concurrency from the chosen resource pool, capped by the Settings pane's thread count when it is lower. */
    private int poolThreads() {
        opentrap.core.ResourcePool p = resourcePool.selected();
        return Math.max(1, Math.min(options.threads(), p.effectiveConcurrent()));
    }

    /** Pacing: the pool's delay when it has one, else the Settings pane's throttle. */
    private long poolThrottle() {
        opentrap.core.ResourcePool p = resourcePool.selected();
        return p.delayOn() ? p.delayMs() : options.throttleMs();
    }

    @Override public java.io.Serializable saveState() {
        String marked = positions.markedText();
        return (marked == null || marked.isEmpty()) ? null : marked;
    }

    @Override public void restoreState(java.io.Serializable state) {
        if (state instanceof String s) {
            positions.setMarkedText(s);
            payloads.setAttack(positions.attackType(), positions.positionCount());
        }
    }

    @Override public JMenu[] menus() {
        JMenu menu = new JMenu("intruder");
        JMenuItem start = new JMenuItem("Start attack");
        start.addActionListener(e -> startAttack());
        JMenuItem clear = new JMenuItem("Clear positions");
        clear.addActionListener(e -> positions.clearPositions());
        start.setFont(Ui.PLAIN);
        clear.setFont(Ui.PLAIN);
        menu.add(start);
        menu.add(clear);
        return new JMenu[]{ menu };
    }

    private void startAttack() {
        int positionCount = positions.positionCount();
        if (positionCount == 0) {
            JOptionPane.showMessageDialog(ctx.frame(),
                    "No payload positions are defined. Use the positions tab to mark them.",
                    "intruder", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<PayloadSet> sets = payloads.activeSets();
        // Feed the marked positions' original values to transforming payload
        // types (character and case substitution) so they have base items.
        List<String> base = originalValues(positions.markedText());
        for (PayloadSet s : sets) s.setBase(base);
        List<List<String>> materialised = new ArrayList<>();
        boolean any = false;
        for (PayloadSet s : sets) {
            List<String> p = s.payloads();
            materialised.add(p);
            if (!p.isEmpty()) any = true;
        }
        if (!any) {
            JOptionPane.showMessageDialog(ctx.frame(),
                    "No payloads are configured. Add payloads on the payloads tab.",
                    "intruder", JOptionPane.WARNING_MESSAGE);
            return;
        }

        HttpService svc = target.service();
        if (svc.host().isEmpty()) {
            JOptionPane.showMessageDialog(ctx.frame(),
                    "No target host is set. Fill in the target tab.",
                    "intruder", JOptionPane.WARNING_MESSAGE);
            return;
        }

        List<String> grep = options.grepExpressions();
        ResultsWindow window = new ResultsWindow(ctx, attackCounter.incrementAndGet(), grep);
        String marked = positions.markedText();
        if (target.updateHostHeader()) marked = withHost(marked, svc);
        AttackEngine engine = new AttackEngine(ctx, svc, marked,
                positions.attackType(), positionCount, materialised, grep,
                poolThreads(), options.followRedirects(), poolThrottle(), options.mode(), window);
        window.setOnClose(engine::stop);
        window.setPauseControl(engine::setPaused);
        window.setVisible(true);
        engine.start();
    }

    /**
     * Rewrite the request's Host header so it names the target, which is what
     * "Update Host header to match target" asks for. A request with no Host
     * header gains one after the start line.
     */
    static String withHost(String marked, HttpService svc) {
        String host = svc.host() + (svc.defaultPort() ? "" : ":" + svc.port());
        String[] lines = marked.split("\r?\n", -1);
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) break;                     // end of the headers
            if (lines[i].regionMatches(true, 0, "Host:", 0, 5)) {
                lines[i] = "Host: " + host;
                return String.join("\r\n", lines);
            }
        }
        if (lines.length < 2) return marked;
        StringBuilder sb = new StringBuilder(lines[0]).append("\r\nHost: ").append(host);
        for (int i = 1; i < lines.length; i++) sb.append("\r\n").append(lines[i]);
        return sb.toString();
    }

    /** The original text between each marker pair, for transforming payloads. */
    private static List<String> originalValues(String markedText) {
        List<String> out = new ArrayList<>();
        String[] parts = markedText.split(String.valueOf(PositionsPanel.MARK), -1);
        for (int i = 1; i < parts.length; i += 2) out.add(parts[i]);
        return out;
    }
}
