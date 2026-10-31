package opentrap.comparer;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.util.List;

/** Two panes side by side with the differences between them coloured in. */
final class CompareWindow extends JFrame {

    // Comparer colour key: modified = blue, deleted = red, added = green.
    private static final Color DELETED  = opentrap.ui.Theme.DIFF_DELETED;
    private static final Color ADDED    = opentrap.ui.Theme.DIFF_ADDED;
    private static final Color MODIFIED = opentrap.ui.Theme.DIFF_MODIFIED;

    /** Settings > Tools > Comparer: whether new result windows start with their panes synced. */
    private static boolean syncDefault() {
        try { return java.util.prefs.Preferences.userRoot().node("opentrap.v1").getBoolean("comparer.syncViews", false); }
        catch (Exception e) { return false; }
    }

    CompareWindow(ComparerTool.Item one, ComparerTool.Item two, boolean words, Frame owner) {
        List<Diff.Block> blocks = Diff.compare(one.data(), two.data(), words);
        int differences = Diff.differences(blocks);
        setTitle((words ? "word" : "byte") + " compare of #" + one.id() + " and #" + two.id()
                + "  (" + differences + (differences == 1 ? " difference" : " differences") + ")");
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        JTextPane left = pane();
        JTextPane right = pane();
        fill(left, blocks, true);
        fill(right, blocks, false);
        JScrollPane leftScroll = Ui.scroll(left);
        JScrollPane rightScroll = Ui.scroll(right);

        JPanel centre = new JPanel(new GridLayout(1, 2, 25, 0));
        centre.setOpaque(false);
        centre.add(side(one.data().length, leftScroll));
        centre.add(side(two.data().length, rightScroll));

        JPanel root = new JPanel(new BorderLayout(0, 8));
        root.setBorder(Ui.pad(10, 10, 10, 10));
        root.add(centre, BorderLayout.CENTER);
        root.add(footer(leftScroll, rightScroll), BorderLayout.SOUTH);

        setContentPane(root);
        setSize(1100, 720);
        setLocationRelativeTo(owner);
    }

    private static JTextPane pane() {
        JTextPane p = new JTextPane();
        p.setFont(Ui.MONO);
        p.setEditable(false);
        return p;
    }

    private static JComponent side(int length, JScrollPane scroll) {
        JPanel p = new JPanel(new BorderLayout(0, 4));
        p.setOpaque(false);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(Ui.label("Length: " + String.format("%,d", length)), BorderLayout.WEST);
        p.add(header, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    private static void fill(JTextPane pane, List<Diff.Block> blocks, boolean leftSide) {
        StyledDocument doc = pane.getStyledDocument();
        try {
            for (Diff.Block b : blocks) {
                String text = leftSide ? b.left() : b.right();
                if (!text.isEmpty()) doc.insertString(doc.getLength(), text, attributes(b.kind()));
            }
        } catch (BadLocationException e) {
            // appending at the end of a document cannot be out of range
        }
        pane.setCaretPosition(0);
    }

    private static AttributeSet attributes(Diff.Kind kind) {
        if (kind == Diff.Kind.SAME) return null;
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, opentrap.ui.Theme.TEXT);
        StyleConstants.setBackground(a, switch (kind) {
            case DELETED -> DELETED;
            case ADDED -> ADDED;
            default -> MODIFIED;
        });
        return a;
    }

    private static JComponent footer(JScrollPane left, JScrollPane right) {
        JPanel legend = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        legend.setOpaque(false);
        legend.add(Ui.bold("key:  "));
        legend.add(chip(" modified ", MODIFIED));
        legend.add(Ui.bold("  "));
        legend.add(chip(" deleted ", DELETED));
        legend.add(Ui.bold("  "));
        legend.add(chip(" added ", ADDED));

        // Ticked, the right bar drives off the left bar's model, so one thumb
        // moves both panes together. Unticking hands its own model back, carrying
        // the current position over first: without that the pane keeps the synced
        // position while its thumb springs back to the top.
        BoundedRangeModel ownScroll = right.getVerticalScrollBar().getModel();
        JCheckBox sync = Ui.check("sync views", false);
        sync.addActionListener(e -> {
            JScrollBar bar = right.getVerticalScrollBar();
            if (sync.isSelected()) {
                bar.setModel(left.getVerticalScrollBar().getModel());
            } else {
                ownScroll.setValue(bar.getValue());
                bar.setModel(ownScroll);
            }
        });
        if (syncDefault()) sync.doClick();

        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(legend, BorderLayout.WEST);
        p.add(sync, BorderLayout.EAST);
        return p;
    }

    private static JLabel chip(String text, Color background) {
        JLabel l = Ui.bold(text);
        l.setOpaque(true);
        l.setBackground(background);
        l.setForeground(opentrap.ui.Theme.TEXT);
        return l;
    }
}
