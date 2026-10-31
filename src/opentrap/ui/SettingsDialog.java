package opentrap.ui;

import opentrap.core.SuiteContext;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * The suite Settings dialog: a category list on the left and the chosen
 * category's settings on the right, opened from the gear in the top bar, from
 * the OpenTRAP menu, or from a tool's own settings tab.
 *
 * <p>Tools own their settings panes and register them here, so the dialog
 * always shows the one live instance of each pane (a Swing component can only
 * be in one place, and a tool's pane holds the state the tool reads). The panes
 * are hosted while the dialog is open and released when it closes. A category
 * no tool has claimed shows its not-yet-implemented placeholder.
 */
public final class SettingsDialog {

    private SettingsDialog() {}

    /** The categories, in the order they are listed. */
    public static final String[] CATEGORIES = {
        "General", "Proxy", "Intruder", "Repeater", "Sequencer", "Comparer", "Browser",
        "Project", "Sessions", "Suite", "Extensions", "Configuration library",
    };

    private static final Map<String, Supplier<JComponent>> PANES = new LinkedHashMap<>();

    /** Categories nobody has implemented yet, and the placeholder each shows. */
    private static Unbuilt placeholder(String category) {
        return switch (category) {
            case "Comparer"   -> Unbuilt.SETTINGS_COMPARER;
            case "Suite"      -> Unbuilt.SETTINGS_SUITE;
            default           -> null;
        };
    }

    /**
     * Claim a category. The supplier returns the pane to host; it is called each
     * time the dialog opens, so it may return one long-lived instance.
     */
    public static void register(String category, Supplier<JComponent> pane) {
        PANES.put(category, pane);
    }

    public static void open(Window owner, SuiteContext ctx) { open(owner, ctx, null); }

    /** Open the dialog, showing {@code category} if given. */
    public static void open(Window owner, SuiteContext ctx, String category) {
        final JDialog d = new JDialog(owner, "Settings", Dialog.ModalityType.APPLICATION_MODAL);

        CardLayout cards = new CardLayout();
        final JPanel right = new JPanel(cards);
        right.setOpaque(false);
        for (String c : CATEGORIES) {
            JComponent pane;
            Supplier<JComponent> s = PANES.get(c);
            if (s != null) pane = s.get();
            else if ("General".equals(c)) pane = new SuiteOptionsPanel(ctx);
            else {
                Unbuilt u = placeholder(c);
                pane = u != null ? u.panel() : new JPanel();
            }
            right.add(pane, c);
        }

        JList<String> nav = new JList<>(CATEGORIES);
        nav.setFont(Ui.PLAIN);
        nav.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        nav.setFixedCellHeight(26);
        nav.setBorder(Ui.pad(4, 4, 4, 4));
        nav.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && nav.getSelectedValue() != null) {
                cards.show(right, nav.getSelectedValue());
            }
        });
        nav.setSelectedValue(category != null && java.util.Arrays.asList(CATEGORIES).contains(category)
                ? category : CATEGORIES[0], true);
        JScrollPane navScroll = new JScrollPane(nav);
        navScroll.setPreferredSize(new Dimension(190, 10));
        navScroll.setBorder(BorderFactory.createLineBorder(Ui.GRID));

        JPanel body = new JPanel(new BorderLayout(10, 0));
        body.setBorder(Ui.pad(12, 12, 8, 12));
        body.add(navScroll, BorderLayout.WEST);
        body.add(right, BorderLayout.CENTER);

        JButton close = Ui.button("Close");
        close.addActionListener(e -> d.dispose());
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        bar.add(close);

        JPanel root = new JPanel(new BorderLayout());
        root.add(body, BorderLayout.CENTER);
        root.add(bar, BorderLayout.SOUTH);

        // Release the hosted panes when the dialog goes, so a tool's pane can be
        // hosted again next time (and shown by the tool meanwhile if it wants).
        d.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosed(java.awt.event.WindowEvent e) { right.removeAll(); }
        });
        d.setContentPane(root);
        d.setSize(1040, 680);
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    /**
     * A small page for a tool's own settings tab: it says where the settings
     * live and opens the dialog there, so each setting has exactly one home.
     */
    public static JComponent launcher(SuiteContext ctx, String category, String heading, String note) {
        JPanel column = new JPanel();
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);
        JLabel h = Ui.heading(heading);
        h.setAlignmentX(Component.LEFT_ALIGNMENT);
        JTextArea n = Ui.note(note);
        n.setAlignmentX(Component.LEFT_ALIGNMENT);
        JButton open = Ui.primaryButton("Open settings", "open Settings at " + category);
        open.setAlignmentX(Component.LEFT_ALIGNMENT);
        open.addActionListener(e -> {
            Window w = ctx.frame() instanceof Window win ? win : null;
            open(w, ctx, category);
        });
        column.add(h);
        column.add(Box.createVerticalStrut(6));
        column.add(n);
        column.add(Box.createVerticalStrut(12));
        column.add(open);
        JPanel page = new JPanel(new BorderLayout());
        page.setBorder(Ui.pad(16, 16, 16, 16));
        page.add(column, BorderLayout.NORTH);
        return page;
    }
}
