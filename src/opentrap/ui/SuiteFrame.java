package opentrap.ui;

import opentrap.core.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The suite shell. It knows nothing about any individual tool: it asks each
 * registered module for a name, its tabs and its menus, and renders those.
 */
public class SuiteFrame extends JFrame {

    private final SuiteContext ctx;
    private final JTabbedPane tabs = new JTabbedPane();
    private final JMenuBar menuBar = new JMenuBar();
    private final List<ToolModule> modules = new ArrayList<>();
    private final JLabel statusLabel = new JLabel(" ");
    private final JLabel memoryLabel = new JLabel();
    private final JButton eventLogLabel = new JButton("Event log (0)");
    private final JButton issuesLabel = new JButton("All issues (0)");
    private AlertsPanel alertsPanel;

    public SuiteFrame(SuiteContext ctx, String title) {
        super(title);
        this.ctx = ctx;
        ctx.setFrame(this);
        setIconImages(AppIcon.images());
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        tabs.setFont(Ui.PLAIN);
        setLayout(new BorderLayout());
        add(tabs, BorderLayout.CENTER);
        add(buildStatusBar(), BorderLayout.SOUTH);
        setJMenuBar(menuBar);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { confirmExit(); }
        });

        Timer t = new Timer(2000, e -> updateMemory());
        t.start();
        updateMemory();
    }

    private JPanel buildStatusBar() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBackground(Theme.SURFACE);
        p.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID), Ui.pad(2, 10, 2, 10)));
        statusLabel.setFont(Ui.PLAIN);
        memoryLabel.setFont(Ui.PLAIN);
        memoryLabel.setForeground(Theme.MUTED);

        linkify(eventLogLabel, () -> selectTool("alerts"));
        linkify(issuesLabel, () -> selectTool("dashboard"));

        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        west.setOpaque(false);
        west.add(eventLogLabel);
        west.add(issuesLabel);
        west.add(statusLabel);
        p.add(west, BorderLayout.WEST);
        p.add(memoryLabel, BorderLayout.EAST);

        // Live counters for the status bar.
        ctx.addAlertListener(a -> SwingUtilities.invokeLater(() ->
                eventLogLabel.setText("Event log (" + ctx.alerts().size() + ")")));
        ctx.addIssueListener(i -> SwingUtilities.invokeLater(() ->
                issuesLabel.setText("All issues (" + ctx.issues().size() + ")")));
        return p;
    }

    /** Make a button read as a flat clickable link in the status bar. */
    private void linkify(JButton b, Runnable action) {
        b.setFont(Ui.PLAIN);
        b.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        b.setContentAreaFilled(false);
        b.setFocusable(false);
        b.setForeground(Theme.MUTED);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.addActionListener(e -> action.run());
    }

    private void updateMemory() {
        Runtime r = Runtime.getRuntime();
        long used = (r.totalMemory() - r.freeMemory()) / (1024 * 1024);
        long max = r.maxMemory() / (1024 * 1024);
        memoryLabel.setText("Memory: " + used + "MB of " + max + "MB");
    }

    public void setStatus(String s) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(s == null ? " " : s));
    }

    /** Register a tool. Call before {@link #buildUi()}. */
    public void register(ToolModule m) {
        modules.add(m);
        ctx.registerTool(m);
    }

    /** Build the tab strip and menu bar from everything registered. */
    public void buildUi() {
        for (ToolModule m : modules) m.init(ctx);

        for (ToolModule m : modules) {
            ToolModule.Tab[] mt = m.tabs();
            JComponent comp;
            if (mt.length == 1 && !m.alwaysTabbed()) {
                comp = mt[0].component();
            } else {
                JTabbedPane inner = new JTabbedPane();
                inner.setFont(Ui.PLAIN);
                inner.setFocusable(false);
                for (ToolModule.Tab t : mt) inner.addTab(t.title(), t.component());
                comp = inner;
            }
            tabs.addTab(m.name(), comp);
        }

        // Two suite-level tabs after the tools, matching the original's shape.
        SuiteOptionsPanel opts = new SuiteOptionsPanel(ctx);
        tabs.addTab("comms", opts);
        alertsPanel = new AlertsPanel(ctx);
        tabs.addTab("alerts", alertsPanel);

        buildMenus();
    }

    private void buildMenus() {
        menuBar.removeAll();

        JMenu suite = new JMenu("OpenTRAP");
        suite.setFont(Ui.PLAIN);
        // Search is built into the places it belongs: the quick URL box and the
        // filter popup on the proxy history and the target site map. It is no
        // longer a separate window reached from this menu.
        addItem(suite, "save state", e -> StateIO.saveDialog(ctx, this));
        addItem(suite, "restore state", e -> StateIO.restoreDialog(ctx, this));
        suite.addSeparator();
        addItem(suite, "session handling...", e -> opentrap.session.SessionWindow.open(ctx));
        suite.addSeparator();
        addItem(suite, "exit", e -> confirmExit());
        menuBar.add(suite);

        for (ToolModule m : modules) {
            JMenu[] ms = m.menus();
            if (ms == null) continue;
            for (JMenu x : ms) { x.setFont(Ui.PLAIN); x.setText(capitalise(x.getText())); menuBar.add(x); }
        }

        JMenu view = new JMenu("View");
        view.setFont(Ui.PLAIN);
        JMenuItem bigger = new JMenuItem("increase text size");
        bigger.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_EQUALS,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        bigger.addActionListener(e -> UiScale.changeTo(currentScale() + 0.25, Main.launchArgs));
        JMenuItem smaller = new JMenuItem("decrease text size");
        smaller.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_MINUS,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        smaller.addActionListener(e -> UiScale.changeTo(currentScale() - 0.25, Main.launchArgs));
        JMenuItem reset = new JMenuItem("reset text size");
        reset.setAccelerator(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_0,
                java.awt.Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        reset.addActionListener(e -> UiScale.changeTo(1.0, Main.launchArgs));
        bigger.setFont(Ui.PLAIN); smaller.setFont(Ui.PLAIN); reset.setFont(Ui.PLAIN);
        view.add(bigger); view.add(smaller); view.add(reset);
        view.addSeparator();
        for (double f : new double[]{1.0, 1.25, 1.5, 2.0}) {
            final double ff = f;
            JMenuItem mi = new JMenuItem((int) (f * 100) + "%");
            mi.setFont(Ui.PLAIN);
            mi.addActionListener(e -> UiScale.changeTo(ff, Main.launchArgs));
            view.add(mi);
        }
        menuBar.add(view);

        JMenu window = new JMenu("Window");
        window.setFont(Ui.PLAIN);
        for (int i = 0; i < modules.size(); i++) {
            final int idx = i;
            addItem(window, "go to " + modules.get(i).name(), e -> tabs.setSelectedIndex(idx));
        }
        menuBar.add(window);

        JMenu help = new JMenu("Help");
        help.setFont(Ui.PLAIN);
        addItem(help, "proxy setup instructions", e -> showProxyHelp());
        addItem(help, "about", e -> AboutDialog.show(this));
        menuBar.add(help);
    }

    private void showProxyHelp() {
        JOptionPane.showMessageDialog(this,
            "1. Set your browser's HTTP and HTTPS proxy to 127.0.0.1 port " + opentrap.proxy.ProxyListener.DEFAULT_PORT + ".\n" +
            "2. Browse to http://opentrap/ and install the CA certificate.\n" +
            "3. Trust it for identifying websites, then reload your target.\n\n" +
            "Traffic appears on the proxy history tab and builds the target site map.",
            "proxy setup", JOptionPane.INFORMATION_MESSAGE);
    }

    /** Menu titles read as headings, like the tab strip: a leading capital. */
    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void addItem(JMenu menu, String label, java.awt.event.ActionListener l) {
        JMenuItem mi = new JMenuItem(label);
        mi.setFont(Ui.PLAIN);
        mi.addActionListener(l);
        menu.add(mi);
    }

    public void selectTool(String name) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getTitleAt(i).equals(name)) { tabs.setSelectedIndex(i); return; }
        }
    }

    public void flashTool(String name) {
        for (int i = 0; i < tabs.getTabCount(); i++) {
            if (tabs.getTitleAt(i).equals(name)) {
                final int idx = i;
                tabs.setBackgroundAt(idx, Ui.ACCENT);
                Timer t = new Timer(900, e -> tabs.setBackgroundAt(idx, null));
                t.setRepeats(false);
                t.start();
                return;
            }
        }
    }

    private double currentScale() {
        String s = System.getProperty("opentrap.scaled");
        if (s != null) try { return Double.parseDouble(s); } catch (Exception ignored) {}
        return UiScale.desired();
    }

    /** Kept for call-site compatibility; the JVM now handles scaling. */
    public void refitAfterScale() {
        if (getExtendedState() == Frame.MAXIMIZED_BOTH) { validate(); repaint(); return; }
        Rectangle screen = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .getMaximumWindowBounds();
        int w = Math.min(getWidth(), screen.width);
        int h = Math.min(getHeight(), screen.height);
        setSize(w, h);
        validate();
        repaint();
    }

    private void confirmExit() {
        int r = JOptionPane.showConfirmDialog(this,
                "This will close all opentrap windows - are you sure you want to exit?",
                "confirm", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE, null);
        if (r == JOptionPane.YES_OPTION) {
            for (ToolModule m : modules) {
                try { m.shutdown(); } catch (Exception ignored) {}
            }
            dispose();
            System.exit(0);
        }
    }
}
