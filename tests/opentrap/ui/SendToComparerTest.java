package opentrap.ui;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.SuiteContext;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.Component;
import java.awt.event.ActionListener;
import java.util.ArrayList;
import java.util.List;

/**
 * "Send to comparer" must load exactly the view the user right-clicked.
 *
 * <p>The comparer compares two items the user chose. Sending it both halves of
 * one exchange loaded two rows per click and left the tool offering to compare a
 * request against its own response, which is never the comparison wanted. Tools
 * that take a pair, such as the repeater, must keep getting both.
 */
public final class SendToComparerTest {

    private static final String REQUEST_TEXT  = "GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    private static final String RESPONSE_TEXT = "HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nhi";

    /** Records what a sink was handed, so a send can be inspected after the fact. */
    private static final class Recorder implements SuiteContext.MessageSink {
        final List<HttpMessage> requests = new ArrayList<>();
        final List<HttpMessage> responses = new ArrayList<>();
        final boolean single;
        int calls;

        Recorder(boolean single) { this.single = single; }

        @Override public void receive(HttpService svc, HttpMessage req, HttpMessage resp, String cap) {
            calls++;
            requests.add(req);
            responses.add(resp);
        }

        @Override public boolean singleItem() { return single; }
    }

    public void run() {
        Harness.suite("Send to comparer");

        Harness.test("a sink is a pair sink unless it says otherwise", () -> {
            SuiteContext ctx = new SuiteContext();
            ctx.registerSink("repeater", new Recorder(false));
            ctx.registerSink("comparer", new Recorder(true));
            Expect.isFalse(ctx.sinkTakesSingleItem("repeater"), "repeater takes one item");
            Expect.isTrue(ctx.sinkTakesSingleItem("comparer"), "comparer takes one item");
            Expect.isFalse(ctx.sinkTakesSingleItem("no-such-tool"), "unknown tool takes one item");
        });

        Harness.test("sending from a response view loads the response alone", () -> {
            Recorder comparer = new Recorder(true);
            SuiteContext ctx = context(comparer);
            MessageEditor editor = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
            // the related request is what the old code smuggled in alongside it
            editor.setMessage(service(), message(RESPONSE_TEXT), message(REQUEST_TEXT));

            fire(editor, "Send to comparer");

            Expect.equal(comparer.calls, 1, "sends from one click");
            Expect.isNull(comparer.requests.get(0), "request handed to the comparer");
            Expect.notNull(comparer.responses.get(0), "response handed to the comparer");
            Expect.equal(text(comparer.responses.get(0)), RESPONSE_TEXT, "the response sent");
        });

        Harness.test("sending from a request view loads the request alone", () -> {
            Recorder comparer = new Recorder(true);
            SuiteContext ctx = context(comparer);
            MessageEditor editor = new MessageEditor(ctx, MessageEditor.REQUEST, true);
            editor.setMessage(service(), message(REQUEST_TEXT));

            fire(editor, "Send to comparer");

            Expect.equal(comparer.calls, 1, "sends from one click");
            Expect.isNull(comparer.responses.get(0), "response handed to the comparer");
            Expect.equal(text(comparer.requests.get(0)), REQUEST_TEXT, "the request sent");
        });

        Harness.test("the intercept view sends what it holds, not the same bytes twice", () -> {
            Recorder comparer = new Recorder(true);
            Recorder repeater = new Recorder(false);
            SuiteContext ctx = new SuiteContext();
            ctx.registerSink("comparer", comparer);
            ctx.registerSink("repeater", repeater);

            // the intercept editor carries both flags, so only its content says
            // which half it is showing
            MessageEditor editor =
                    new MessageEditor(ctx, MessageEditor.REQUEST | MessageEditor.RESPONSE, true);
            editor.setMessage(service(), message(RESPONSE_TEXT), message(REQUEST_TEXT));

            fire(editor, "Send to comparer");
            Expect.equal(comparer.calls, 1, "sends to the comparer");
            Expect.isNull(comparer.requests.get(0), "request handed to the comparer");
            Expect.equal(text(comparer.responses.get(0)), RESPONSE_TEXT, "the response sent");

            fire(editor, "Send to repeater");
            Expect.equal(text(repeater.requests.get(0)), REQUEST_TEXT,
                    "the request the repeater receives");
            Expect.equal(text(repeater.responses.get(0)), RESPONSE_TEXT,
                    "the response the repeater receives");
        });

        Harness.test("a pair sink still receives both halves from a response view", () -> {
            Recorder repeater = new Recorder(false);
            SuiteContext ctx = new SuiteContext();
            ctx.registerSink("repeater", repeater);
            MessageEditor editor = new MessageEditor(ctx, MessageEditor.RESPONSE, false);
            editor.setMessage(service(), message(RESPONSE_TEXT), message(REQUEST_TEXT));

            fire(editor, "Send to repeater");

            Expect.equal(text(repeater.requests.get(0)), REQUEST_TEXT, "the request sent");
            Expect.equal(text(repeater.responses.get(0)), RESPONSE_TEXT, "the response sent");
        });

        Harness.test("an empty view offers no send to the comparer", () -> {
            Recorder comparer = new Recorder(true);
            SuiteContext ctx = context(comparer);
            MessageEditor editor = new MessageEditor(ctx, MessageEditor.RESPONSE, false);

            Expect.isNull(find(editor.buildMenu(), "Send to comparer"),
                    "a send entry on an empty view");
        });
    }

    // ---- helpers ----

    private static SuiteContext context(SuiteContext.MessageSink comparer) {
        SuiteContext ctx = new SuiteContext();
        ctx.registerSink("comparer", comparer);
        return ctx;
    }

    private static HttpService service() { return new HttpService("example.com", 80, false); }

    private static HttpMessage message(String text) {
        return new HttpMessage(text.getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
    }

    private static String text(HttpMessage m) {
        return m == null ? null : new String(m.raw(), java.nio.charset.StandardCharsets.ISO_8859_1);
    }

    /** Click a named entry of the editor's action menu without needing a screen. */
    private static void fire(MessageEditor editor, String label) {
        JMenuItem item = find(editor.buildMenu(), label);
        Expect.notNull(item, "a \"" + label + "\" entry in the menu");
        for (ActionListener l : item.getActionListeners()) l.actionPerformed(null);
    }

    private static JMenuItem find(JPopupMenu menu, String label) {
        for (Component c : menu.getComponents()) {
            if (c instanceof JMenuItem mi && label.equals(mi.getText())) return mi;
        }
        return null;
    }
}
