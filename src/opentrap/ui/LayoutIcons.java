package opentrap.ui;

import javax.swing.*;
import java.awt.*;
import java.util.function.IntConsumer;

/**
 * The three layout marks beside a request/response pair: side by side,
 * stacked, and one at a time. The chosen one is filled with the control
 * colour.
 */
public final class LayoutIcons {
    private LayoutIcons() {}

    public static JComponent create(IntConsumer onChoose) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        p.setOpaque(false);
        ButtonGroup g = new ButtonGroup();
        for (int i = 0; i < 3; i++) {
            final int mode = i;
            JToggleButton b = new JToggleButton(icon(mode, Theme.ICON), i == 0);
            b.setSelectedIcon(icon(mode, Color.WHITE));
            b.setToolTipText(mode == 0 ? "Side by side" : mode == 1 ? "Stacked" : "One at a time");
            b.setFocusable(false);
            b.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
            b.setContentAreaFilled(false);
            b.setOpaque(true);
            b.setBackground(i == 0 ? Theme.CONTROL : Theme.BG);
            b.addActionListener(e -> {
                for (Component c : p.getComponents()) if (c instanceof JToggleButton t) t.setBackground(t.isSelected() ? Theme.CONTROL : Theme.BG);
                onChoose.accept(mode);
            });
            g.add(b);
            p.add(b);
        }
        return p;
    }

    private static Icon icon(int mode, Color c) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(c);
        switch (mode) {
            case 0 -> { g.fillRoundRect(2, 3, 5, 10, 2, 2); g.fillRoundRect(9, 3, 5, 10, 2, 2); }
            case 1 -> { g.fillRoundRect(2, 3, 12, 4, 2, 2); g.fillRoundRect(2, 9, 12, 4, 2, 2); }
            default -> g.fillRoundRect(2, 3, 12, 10, 2, 2);
        }
        g.dispose();
        return new ImageIcon(img);
    }
}
