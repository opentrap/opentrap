package opentrap.ui;

import opentrap.core.SuiteContext;
import javax.swing.SwingUtilities;
import java.nio.file.Path;

/** Registers every tool with the suite shell, in tab order. */
public final class Bootstrap {
    private Bootstrap() {}

    public static void registerTools(SuiteContext ctx, SuiteFrame frame, Path home) throws Exception {
        // Top-level tabs correspond to Burp's ToolType tools. WebSockets lives
        // under Proxy; session handling is a window from the menu; CSRF PoC and
        // content discovery are right-click engagement tools (registered below).
        frame.register(new opentrap.dashboard.DashboardTool());
        frame.register(new opentrap.target.TargetTool());
        frame.register(new opentrap.proxy.ProxyTool());
        frame.register(new opentrap.scanner.ScannerTool());
        frame.register(new opentrap.intruder.IntruderTool(ctx));
        frame.register(new opentrap.repeater.RepeaterTool());
        frame.register(new opentrap.collaborator.CollaboratorTool());
        frame.register(new opentrap.logger.LoggerTool());
        frame.register(new opentrap.organizer.OrganizerTool());
        frame.register(new opentrap.sequencer.SequencerTool());
        frame.register(new opentrap.decoder.DecoderTool());
        frame.register(new opentrap.comparer.ComparerTool());

        registerEngagementActions(ctx);
    }

    /**
     * Right-click actions that open their own window (Burp's engagement tools),
     * plus the session-macro recorder, all surfaced through the "send to ..."
     * context menu rather than as main tabs.
     */
    private static void registerEngagementActions(SuiteContext ctx) {
        ctx.registerSink("CSRF PoC", (svc, req, resp, cap) ->
                SwingUtilities.invokeLater(() -> new opentrap.csrf.CsrfWindow(ctx, svc, req).setVisible(true)));
        ctx.registerSink("content discovery", (svc, req, resp, cap) ->
                SwingUtilities.invokeLater(() -> new opentrap.discovery.DiscoveryWindow(ctx, svc).setVisible(true)));
        ctx.registerSink("session macro", (svc, req, resp, cap) ->
                ctx.sessions().recordToMacro(svc, req));
    }
}
