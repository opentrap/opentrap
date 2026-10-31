import opentrap.testkit.Harness;

/**
 * Entry point for the test run.
 *
 * <p>Suites are listed explicitly so the run order is fixed and a missing suite
 * is a compile error rather than a silently skipped file.
 */
public final class RunTests {

    public static void main(String[] args) {
        System.out.println("OpenTRAP tests");
        System.out.println();

        new opentrap.core.ParameterParsingTest().run();
        new opentrap.core.HttpModeTest().run();
        new opentrap.core.UpstreamProxyTest().run();
        new opentrap.core.ScopeDisplayTest().run();
        new opentrap.core.ScopePrefixTest().run();
        new opentrap.core.ScopeUrlRegexTest().run();
        new opentrap.core.MessageStoreCapTest().run();
        new opentrap.session.SessionTest().run();
        new opentrap.intruder.PayloadProcessorTest().run();
        new opentrap.report.ExportTest().run();
        new opentrap.discovery.DiscoveryTest().run();
        new opentrap.decoder.DecoderTransformsTest().run();
        new opentrap.ui.MessageEditorJsonTest().run();
        new opentrap.core.HttpMessageTitleTest().run();
        new opentrap.csrf.CsrfPocTest().run();
        new opentrap.sequencer.SequencerStatsTest().run();
        new opentrap.organizer.OrganizerTest().run();
        new opentrap.engine.EngineMappingTest().run();
        new opentrap.proxy.ProxyDefaultsTest().run();
        new opentrap.proxy.WebSocketFrameTest().run();
        new opentrap.proxy.WsMatchReplaceTest().run();
        new opentrap.proxy.HistoryFilterTest().run();
        new opentrap.engine.ScanConfigStoreTest().run();
        new opentrap.ui.SendToComparerTest().run();
        new opentrap.ui.ThemePaletteTest().run();
        new opentrap.ui.ProjectTasksTest().run();
        new opentrap.ui.ProjectSlimTest().run();

        System.exit(Harness.report());
    }
}
