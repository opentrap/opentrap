package opentrap.session;

import opentrap.core.SuiteContext;

import javax.swing.JFrame;
import java.awt.BorderLayout;

/**
 * Session handling lives in Settings in Burp, not as a main tab, so here it is a
 * window reached from the menu. A single instance is reused and refreshed each
 * time it is shown.
 */
public final class SessionWindow extends JFrame {

    private static SessionWindow instance;

    private final SessionPanel panel;

    private SessionWindow(SuiteContext ctx) {
        super("session handling");
        panel = new SessionPanel(ctx);
        setLayout(new BorderLayout());
        add(panel, BorderLayout.CENTER);
        setSize(960, 620);
        setLocationRelativeTo(ctx.frame());
    }

    /** Show (creating once) the session-handling window. */
    public static void open(SuiteContext ctx) {
        if (instance == null) instance = new SessionWindow(ctx);
        instance.panel.refreshAll();
        instance.setVisible(true);
        instance.toFront();
        instance.requestFocus();
    }
}
