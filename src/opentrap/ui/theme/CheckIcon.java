package opentrap.ui.theme;

import opentrap.ui.Theme;

import javax.swing.*;
import javax.swing.plaf.UIResource;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;

/** Flat check box and radio button marks: an outline, filled with the accent when on. */
public class CheckIcon implements Icon, UIResource {

    private static final int SIZE = 15;
    private final boolean round;

    public CheckIcon(boolean round) { this.round = round; }

    @Override public int getIconWidth()  { return SIZE; }
    @Override public int getIconHeight() { return SIZE; }

    @Override public void paintIcon(Component c, Graphics g, int x, int y) {
        AbstractButton b = (AbstractButton) c;
        ButtonModel m = b.getModel();
        boolean on = m.isSelected(), enabled = m.isEnabled();
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        Shape box = round
                ? new Ellipse2D.Float(x + 0.5f, y + 0.5f, SIZE - 1, SIZE - 1)
                : new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, SIZE - 1, SIZE - 1, 4, 4);
        Color fill = on ? (enabled ? Theme.CONTROL : Theme.SURFACE_3) : Theme.BG;
        Color edge = on ? fill : (enabled ? Theme.SURFACE_3 : Theme.BORDER);
        if (m.isRollover() && enabled && !on) edge = Theme.CONTROL;
        g2.setColor(fill);
        g2.fill(box);
        g2.setColor(edge);
        g2.setStroke(new BasicStroke(1.2f));
        g2.draw(box);

        if (on) {
            g2.setColor(Color.WHITE);
            if (round) {
                g2.fill(new Ellipse2D.Float(x + 4.5f, y + 4.5f, SIZE - 9, SIZE - 9));
            } else {
                Path2D p = new Path2D.Float();
                p.moveTo(x + 3.5, y + 7.5);
                p.lineTo(x + 6.3, y + 10.3);
                p.lineTo(x + 11.5, y + 4.5);
                g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.draw(p);
            }
        }
        g2.dispose();
    }
}
