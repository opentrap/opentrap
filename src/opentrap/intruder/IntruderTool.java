package opentrap.intruder;

import opentrap.core.HttpService;
import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.ui.Ui;

import javax.swing.*;
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

    @Override public boolean alwaysTabbed() { return false; }

    @Override public void init(SuiteContext ctx) {
        ctx.registerSink("intruder", (svc, req, resp, cap) ->
                SwingUtilities.invokeLater(() -> {
                    target.set(svc);
                    positions.setRequest(svc, req);
                    payloads.setAttack(positions.attackType(), positions.positionCount());
                }));
    }

    @Override public Tab[] tabs() {
        // Burp's single-view layout: positions/request on the left, the Payloads
        // configuration (and Settings/Target) as panels on the right.
        JTabbedPane right = new JTabbedPane();
        right.setFont(Ui.PLAIN);
        right.addTab("Payloads", payloads);
        right.addTab("Settings", options);
        right.addTab("Target", target);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, positions, right);
        split.setDividerSize(5);
        Ui.balance(split, 0.62);
        return new Tab[]{ new Tab("intruder", split) };
    }

    @Override public JMenu[] menus() {
        JMenu menu = new JMenu("intruder");
        JMenuItem start = new JMenuItem("start attack");
        start.addActionListener(e -> startAttack());
        JMenuItem clear = new JMenuItem("clear positions");
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
        AttackEngine engine = new AttackEngine(ctx, svc, positions.markedText(),
                positions.attackType(), positionCount, materialised, grep,
                options.threads(), options.followRedirects(), options.throttleMs(), options.mode(), window);
        window.setOnClose(engine::stop);
        window.setPauseControl(engine::setPaused);
        window.setVisible(true);
        engine.start();
    }

    /** The original text between each marker pair, for transforming payloads. */
    private static List<String> originalValues(String markedText) {
        List<String> out = new ArrayList<>();
        String[] parts = markedText.split(String.valueOf(PositionsPanel.MARK), -1);
        for (int i = 1; i < parts.length; i += 2) out.add(parts[i]);
        return out;
    }
}
