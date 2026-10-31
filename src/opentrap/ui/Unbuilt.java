package opentrap.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * The shared "not yet implemented" affordance, and the source of the roadmap.
 *
 * <p>OpenTRAP lays out the full interface for a feature before the behaviour
 * behind it exists. A control for an unbuilt feature stays enabled and, when
 * used, opens this panel instead of doing nothing: it names the feature, invites
 * the user to contribute it to the project, and gives them a one-click copy of a
 * short anchor to paste to an AI (or their own notes) to start building it. Whole
 * empty areas embed {@link #panel()} directly.
 *
 * <p>Each entry carries two facts only, both certain: a stable reference code
 * ({@code OT-<TOOL>-<FEATURE>}) and where the feature lives. We deliberately do
 * not describe what the feature does; the person who picks it up knows it, and we
 * would rather not put a wrong description in their hands. Large features are
 * split into independent sub-units, each its own code, so a contributor can take
 * one and finish it to a high standard. The full set of entries is the roadmap
 * (see {@link #all()}).
 */
public final class Unbuilt {

    /** The canonical project that contributions (pull requests) go to. */
    public static final String PROJECT_URL = "https://github.com/opentrap/opentrap";

    private static final List<Unbuilt> ALL = new ArrayList<>();

    private final String code;      // OT-<TOOL>-<FEATURE>[-<PART>]
    private final String location;  // e.g. "Intruder > Resource pool"

    private Unbuilt(String code, String location) {
        this.code = code;
        this.location = location;
        ALL.add(this);
    }

    private static Unbuilt of(String code, String location) {
        return new Unbuilt(code, location);
    }

    // ---- Registry of unbuilt features = the roadmap ------------------------
    // Declaration order is the order shown on the roadmap, grouped by tool.

    // Dashboard
    public static final Unbuilt DASHBOARD_LIVE_TASK  = of("OT-DASHBOARD-LIVE-TASK",  "Dashboard > New live task");
    public static final Unbuilt DASHBOARD_LIVE_CRAWL = of("OT-DASHBOARD-LIVE-CRAWL", "Dashboard > Live crawl view");

    // New scan wizard
    public static final Unbuilt SCAN_TYPE_API        = of("OT-SCAN-TYPE-API",        "New scan > API-only scan");
    public static final Unbuilt SCAN_LOGIN           = of("OT-SCAN-LOGIN",           "New scan > Application login");

    // Intruder
    public static final Unbuilt INTRUDER_RESPOOL     = of("OT-INTRUDER-RESPOOL",     "Intruder > Resource pool");

    // Suite chrome (the browser and search in the top bar are real features)
    public static final Unbuilt CONSOLE              = of("OT-CONSOLE",              "Console");

    // Settings dialog panes (the dialog and the Proxy, Browser, Intruder, Repeater,
    // Sequencer, Project, Sessions and Configuration library panes are built; these are not)
    public static final Unbuilt SETTINGS_COMPARER       = of("OT-SETTINGS-COMPARER",       "Settings > Comparer");
    public static final Unbuilt SETTINGS_SUITE          = of("OT-SETTINGS-SUITE",          "Settings > Suite");

    public String code()     { return code; }
    public String location() { return location; }

    /** The tool/group this feature belongs to, for grouping on the roadmap. */
    public String group() {
        int i = location.indexOf(" > ");
        return i < 0 ? location : location.substring(0, i);
    }

    /** The whole registry, in declaration order: the roadmap. */
    public static List<Unbuilt> all() {
        return java.util.Collections.unmodifiableList(ALL);
    }

    /** The feature name without its tool prefix, as the roadmap shows it. */
    public String feature() {
        int i = location.indexOf(" > ");
        return i < 0 ? location : location.substring(i + 3);
    }

    /**
     * The roadmap as Markdown, grouped by tool in registry order. ROADMAP.md in
     * the repository is this text; regenerate it with
     * {@code java -cp build opentrap.ui.Unbuilt ROADMAP.md} after changing the
     * registry, so the file and the interface never disagree.
     */
    public static String markdown() {
        StringBuilder b = new StringBuilder();
        b.append("# Roadmap\n\n");
        b.append("OpenTRAP is built in the open. Every feature below is laid out in the interface ")
         .append("but not implemented yet; using it in the app opens a dialog that names the same ")
         .append("reference code. Pick one and open a pull request on ")
         .append("[the main project](").append(PROJECT_URL).append(") to build it. Accepted work is ")
         .append("merged with full credit. To discuss a feature or ask for it to be prioritised, ")
         .append("[open an issue](").append(PROJECT_URL).append("/issues).\n\n");
        b.append("Reference codes are stable: quote one in an issue or pull request title. ")
         .append("Large features are split so that each entry can be finished on its own.\n\n");
        b.append("This file is generated from the registry in `src/opentrap/ui/Unbuilt.java`, ")
         .append("which also drives the Roadmap tab under Discover; do not edit it by hand.\n");
        String group = null;
        for (Unbuilt u : ALL) {
            String g = u.location.contains(" > ") ? u.group() : "Suite";
            if (!g.equals(group)) {
                group = g;
                b.append("\n## ").append(g).append("\n\n| Feature | Reference |\n|---|---|\n");
            }
            b.append("| ").append(u.feature()).append(" | `").append(u.code).append("` |\n");
        }
        b.append("\n").append(ALL.size()).append(" open items.\n");
        return b.toString();
    }

    /** Write the roadmap to the file named on the command line (or print it). */
    public static void main(String[] args) throws java.io.IOException {
        if (args.length == 0) { System.out.print(markdown()); return; }
        java.nio.file.Files.writeString(java.nio.file.Path.of(args[0]), markdown());
    }

    /** The clipboard payload: only lines that help an AI build the feature. */
    private String prompt() {
        return "OpenTRAP (Java/Swing security testing suite, OWASP ZAP engine).\n"
             + "Implement: " + location + "  [" + code + "].\n"
             + "Currently a UI placeholder, not wired to the engine.";
    }

    /** Wire a control so using it opens the dialog. Keeps the control enabled. */
    public void attach(AbstractButton b) {
        b.setToolTipText("not yet implemented - click to contribute");
        b.addActionListener(e -> showDialog(b));
    }

    /** Open the modal "not yet implemented" dialog near the given component. */
    public void showDialog(Component near) {
        Window owner = near == null ? null : SwingUtilities.getWindowAncestor(near);
        final JDialog d = new JDialog(owner, "Not yet implemented", Dialog.ModalityType.APPLICATION_MODAL);
        JPanel content = content();
        content.setBorder(Ui.pad(20, 24, 18, 24));

        JButton close = Ui.button("Close");
        close.addActionListener(e -> d.dispose());
        JPanel bar = buttonBar(near, close);
        content.add(Box.createVerticalStrut(16));
        content.add(bar);

        d.setContentPane(content);
        d.pack();
        // Roomy: wide enough that nothing wraps awkwardly, tall enough to show
        // every line in full, and never smaller than its packed size.
        Dimension packed = d.getSize();
        d.setSize(Math.max(760, packed.width), packed.height + 24);
        d.setMinimumSize(d.getSize());
        d.setResizable(true);
        d.setLocationRelativeTo(near);
        d.setVisible(true);
    }

    /** An inline placeholder panel for a whole empty area (e.g. an unbuilt pane). */
    public JComponent panel() {
        return panel(true);
    }

    /**
     * The inline placeholder, centred in its area when {@code centred}, or as a
     * compact left-aligned block to drop into a page of other sections.
     */
    public JComponent panel(boolean centred) {
        JPanel body = content();
        body.add(Box.createVerticalStrut(14));
        body.add(buttonBar(body, null));
        body.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.GRID), Ui.pad(18, 20, 18, 20)));
        body.setMaximumSize(new Dimension(720, Integer.MAX_VALUE));
        if (!centred) {
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.add(body, BorderLayout.WEST);
            return wrap;
        }
        JPanel wrap = new JPanel(new GridBagLayout());
        wrap.setOpaque(false);
        wrap.setBorder(Ui.pad(24, 24, 24, 24));
        wrap.add(body, new GridBagConstraints());
        return wrap;
    }

    /** The shared message + reference + location + copy-text block. */
    private JPanel content() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);

        JLabel h = Ui.heading(location + "  -  not yet implemented");
        h.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(h);
        p.add(Box.createVerticalStrut(8));

        // Sized by rows and columns so its preferred height is honest: a wrapping
        // text area otherwise reports a single line and gets clipped when packed.
        JTextArea note = new JTextArea(
            "This feature is on the OpenTRAP roadmap and has not been implemented "
          + "yet. OpenTRAP is free and open source, built by its community. "
          + "Contributions are welcome: build it and open a pull request on the "
          + "main project, and we'll review and merge it with full credit as an "
          + "OpenTRAP contributor. To discuss it or ask for it to be prioritised, "
          + "open an issue on the project.", 5, 56);
        note.setFont(Ui.PLAIN);
        note.setEditable(false);
        note.setOpaque(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setBorder(null);
        note.setFocusable(false);
        note.setAlignmentX(Component.LEFT_ALIGNMENT);
        note.setMaximumSize(new Dimension(Integer.MAX_VALUE, note.getPreferredSize().height));
        p.add(note);
        p.add(Box.createVerticalStrut(12));

        JPanel refs = Ui.form(
            "Reference", monoValue(code),
            "Location",  Ui.label(location));
        refs.setAlignmentX(Component.LEFT_ALIGNMENT);
        refs.setMaximumSize(new Dimension(580, refs.getPreferredSize().height));
        p.add(refs);
        p.add(Box.createVerticalStrut(10));

        JLabel copyHint = Ui.label("\"Copy for AI\" puts exactly this on the clipboard:");
        copyHint.setForeground(Theme.MUTED);
        copyHint.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(copyHint);
        p.add(Box.createVerticalStrut(4));

        // Shown in full, never scrolled or clipped, so what the user sees is
        // precisely what gets copied.
        String text = prompt();
        JTextArea prompt = new JTextArea(text, text.split("\n").length, 1);
        prompt.setFont(Ui.MONO);
        prompt.setEditable(false);
        prompt.setLineWrap(false);
        prompt.setOpaque(true);
        prompt.setBackground(Theme.SURFACE);
        prompt.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Ui.GRID), Ui.pad(8, 10, 8, 10)));
        prompt.setAlignmentX(Component.LEFT_ALIGNMENT);
        Dimension pd = prompt.getPreferredSize();
        prompt.setPreferredSize(new Dimension(Math.max(540, pd.width), pd.height));
        prompt.setMaximumSize(new Dimension(Integer.MAX_VALUE, pd.height));
        p.add(prompt);
        return p;
    }

    /** Copy / GitHub (and optional Close) row. */
    private JPanel buttonBar(Component near, JButton trailing) {
        JButton copy = Ui.primaryButton("Copy for AI", "copy the build anchor to the clipboard");
        copy.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(prompt()), null);
            String was = copy.getText();
            copy.setText("Copied");
            Timer t = new Timer(1200, ev -> copy.setText(was));
            t.setRepeats(false);
            t.start();
        });
        JButton github = Ui.button("Open project on GitHub", "open the OpenTRAP project page");
        github.addActionListener(e -> openProject(near));

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        bar.setOpaque(false);
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.add(copy);
        bar.add(github);
        if (trailing != null) bar.add(trailing);
        bar.setMaximumSize(new Dimension(640, bar.getPreferredSize().height));
        return bar;
    }

    private static JLabel monoValue(String s) {
        JLabel l = new JLabel(s);
        l.setFont(Ui.MONO);
        return l;
    }

    /** Open the project page in a browser, or copy the URL if that is not possible. */
    public static void openProject(Component near) {
        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(java.net.URI.create(PROJECT_URL));
                return;
            }
        } catch (Exception ignore) {
            // fall through to the clipboard fallback
        }
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(PROJECT_URL), null);
        JOptionPane.showMessageDialog(near,
                "Could not open a browser here.\nProject URL copied to the clipboard:\n" + PROJECT_URL,
                "Open project", JOptionPane.INFORMATION_MESSAGE);
    }
}
