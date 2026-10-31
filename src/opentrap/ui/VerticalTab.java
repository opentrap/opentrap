package opentrap.ui;

import javax.swing.*;
import java.awt.*;

/**
 * A tab button with vertical text, for the slim strips on the right edge of a
 * panel (the message editor's Inspector / Notes, Intruder's Payloads / Resource
 * pool / Settings). Selected: accent text on the selection tint.
 */
public final class VerticalTab extends JToggleButton {

    private final Icon mark;

    public VerticalTab(String text) { this(text, null); }

    /** With a mark drawn above the rotated text, as Intruder's strip has. */
    public VerticalTab(String text, Icon mark) {
        super(text);
        this.mark = mark;
        setFont(Ui.PLAIN);
        setFocusable(false);
        setContentAreaFilled(false);
        setBorder(BorderFactory.createEmptyBorder());
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
    }
    @Override public Dimension getPreferredSize() {
        FontMetrics fm = getFontMetrics(getFont());
        int extra = mark == null ? 0 : mark.getIconHeight() + 6;
        return new Dimension(Math.max(fm.getHeight() + 10, mark == null ? 0 : mark.getIconWidth() + 10),
                fm.stringWidth(getText()) + 26 + extra);
    }
    @Override public Dimension getMaximumSize() { return getPreferredSize(); }
    @Override protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        if (isSelected()) { g2.setColor(Ui.SELECTION); g2.fillRect(0, 0, getWidth(), getHeight()); }
        int top = 0;
        if (mark != null) {
            top = mark.getIconHeight() + 6;
            mark.paintIcon(this, g2, (getWidth() - mark.getIconWidth()) / 2, 4);
        }
        g2.setColor(isSelected() ? Ui.ACCENT : Theme.MUTED);
        g2.setFont(getFont());
        FontMetrics fm = g2.getFontMetrics();
        int tw = fm.stringWidth(getText());
        double cy = top + (getHeight() - top) / 2.0;
        g2.rotate(-Math.PI / 2, getWidth() / 2.0, cy);
        g2.drawString(getText(), (float) (getWidth() / 2.0 - tw / 2.0),
                (float) (cy + (fm.getAscent() - fm.getDescent()) / 2.0));
        g2.dispose();
    }
}
