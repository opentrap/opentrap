package opentrap.ui;

import javax.swing.*;
import java.awt.*;

/**
 * The options page pattern: a vertical stack of titled sections separated by
 * rules, inside a scroll pane. Used by most tools and by the suite options.
 */
public class OptionsPage extends JPanel {

    private final JPanel stack = new JPanel();
    private boolean first = true;

    public OptionsPage() {
        super(new BorderLayout());
        stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
        stack.setBorder(Ui.pad(12, 14, 30, 14));
        stack.setOpaque(false);
        // The page scrolls only when the user scrolls it. Text fields and
        // tables inside it ask to be scrolled into view as they are built and
        // focused, and honouring that opened pages part-way down with the
        // first section hidden.
        JPanel holder = new JPanel(new BorderLayout()) {
            @Override public void scrollRectToVisible(Rectangle r) {}
        };
        holder.setOpaque(false);
        holder.add(stack, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(holder);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                SwingUtilities.invokeLater(() -> sp.getViewport().setViewPosition(new Point(0, 0)));
            }
        });
        sp.setBorder(null);
        sp.getVerticalScrollBar().setUnitIncrement(25);
        sp.getViewport().setBackground(Theme.BG);
        holder.setBackground(Theme.BG);
        stack.setBackground(Theme.BG);
        add(sp, BorderLayout.CENTER);
    }

    /** Add a section with a heading and a body of stacked components. */
    public JPanel section(String title, Component... parts) {
        title = Ui.sentence(title);
        if (!first) {
            JSeparator sep = new JSeparator();
            sep.setMaximumSize(new Dimension(620, 10));
            sep.setAlignmentX(Component.LEFT_ALIGNMENT);
            JPanel wrap = new JPanel(new BorderLayout());
            wrap.setOpaque(false);
            wrap.setBorder(Ui.pad(16, 0, 16, 0));
            wrap.setMaximumSize(new Dimension(620, 32));
            wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
            wrap.add(sep, BorderLayout.CENTER);
            stack.add(wrap);
        }
        first = false;

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setOpaque(false);
        body.setAlignmentX(Component.LEFT_ALIGNMENT);

        if (title != null && !title.isEmpty()) {
            JLabel h = Ui.heading(title);
            h.setAlignmentX(Component.LEFT_ALIGNMENT);
            h.setBorder(Ui.pad(0, 0, 8, 0));
            body.add(h);
        }
        for (Component c : parts) {
            if (c instanceof JTextArea ta && ta.getLineWrap()) {
                // Bound wrapping notes to the page width and let height follow,
                // so a page never grows wider than its viewport for a paragraph.
                Ui.sizeNote(ta, 600);
                ta.setAlignmentX(Component.LEFT_ALIGNMENT);
            } else if (c instanceof JComponent jc) {
                jc.setAlignmentX(Component.LEFT_ALIGNMENT);
                Dimension pref = jc.getPreferredSize();
                jc.setMaximumSize(new Dimension(Math.max(620, pref.width), pref.height));
            }
            body.add(c);
            body.add(Box.createVerticalStrut(4));
        }
        stack.add(body);
        return body;
    }

    /** Add an already-built component as its own section. */
    public void addSection(Component c) {
        if (c instanceof JComponent jc) jc.setAlignmentX(Component.LEFT_ALIGNMENT);
        stack.add(c);
    }

    /** Shared fixed-width label column, so every settings input lines up. */
    private static final int LABEL_W = 130;

    private static JLabel formLabel(String text) {
        JLabel l = Ui.label(text);
        l.setHorizontalAlignment(SwingConstants.RIGHT);
        l.setPreferredSize(new Dimension(LABEL_W, l.getPreferredSize().height));
        return l;
    }

    /** A labelled numeric or text field row; the label sits in the shared column. */
    public static JPanel fieldRow(String label, JComponent field, String suffix) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        p.add(formLabel(label));
        p.add(field);
        if (suffix != null && !suffix.isEmpty()) p.add(Ui.label(suffix));
        return p;
    }

    /** A labelled control (combo box, etc.) row sharing the same label column. */
    public static JPanel labelRow(String label, Component control) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        p.add(formLabel(label));
        p.add(control);
        return p;
    }

    public static JPanel indent(Component c, int px) {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(Ui.pad(0, px, 0, 0));
        p.add(c, BorderLayout.CENTER);
        return p;
    }
}
