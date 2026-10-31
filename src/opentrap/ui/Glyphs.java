package opentrap.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;

/**
 * Small monochrome glyphs shared across the suite: the kebab, the help mark,
 * the gear and the little toolbar actions (add, edit, duplicate, export,
 * delete, star). Drawn at a given size so they need no image files and scale
 * with the interface. Every tool that needs one of these icons uses the same
 * drawing, so the same action looks the same everywhere.
 */
public final class Glyphs {
    private Glyphs() {}

    private interface Draw { void paint(Graphics2D g, int s); }

    private static Icon make(int size, Color colour, Draw d) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setColor(colour == null ? Theme.ICON : colour);
        g.setStroke(new BasicStroke(Math.max(1.2f, size / 11f)));
        d.paint(g, size);
        g.dispose();
        return new ImageIcon(img);
    }

    public static final int SIZE = 16;

    /** Three vertical dots: the "more actions" menu on tables and panes. */
    public static Icon kebab() { return kebab(SIZE, null); }
    public static Icon kebab(int size, Color c) {
        return make(size, c, (g, s) -> {
            double r = Math.max(1.2, s / 9.0);
            double cx = s / 2.0;
            for (double cy : new double[]{s * 0.2, s * 0.5, s * 0.8})
                g.fill(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        });
    }

    /** A question mark in a ring: contextual help. */
    public static Icon help() { return help(SIZE, null); }
    public static Icon help(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Ellipse2D.Double(1, 1, s - 2.5, s - 2.5));
            g.setFont(new Font("Dialog", Font.BOLD, Math.round(s * 0.7f)));
            FontMetrics fm = g.getFontMetrics();
            g.drawString("?", (float) (s / 2.0 - fm.stringWidth("?") / 2.0),
                    (float) (s / 2.0 + (fm.getAscent() - fm.getDescent()) / 2.0));
        });
    }

    /** A gear: ring, hub and eight teeth. */
    public static Icon gear() { return gear(SIZE, null); }
    public static Icon gear(int size, Color c) {
        return make(size, c, (g, s) -> {
            double cx = s / 2.0, cy = s / 2.0, ri = s * 0.3, ro = s * 0.46;
            g.setStroke(new BasicStroke(Math.max(1.6f, s / 7f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
            for (int a = 0; a < 360; a += 45) {
                double r = Math.toRadians(a);
                g.draw(new Line2D.Double(cx + ri * Math.cos(r), cy + ri * Math.sin(r),
                        cx + ro * Math.cos(r), cy + ro * Math.sin(r)));
            }
            g.setStroke(new BasicStroke(Math.max(1.2f, s / 11f)));
            g.draw(new Ellipse2D.Double(cx - ri, cy - ri, 2 * ri, 2 * ri));
            g.fill(new Ellipse2D.Double(cx - ri / 2.5, cy - ri / 2.5, ri / 1.25, ri / 1.25));
        });
    }

    /** A plus sign. */
    public static Icon plus() { return plus(SIZE, null); }
    public static Icon plus(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Line2D.Double(s / 2.0, 2, s / 2.0, s - 2));
            g.draw(new Line2D.Double(2, s / 2.0, s - 2, s / 2.0));
        });
    }

    /** A plus sign in a ring, the "new item" badge used on primary buttons. */
    public static Icon plusRing(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Ellipse2D.Double(1, 1, s - 2.5, s - 2.5));
            g.draw(new Line2D.Double(s / 2.0, s * 0.28, s / 2.0, s * 0.72));
            g.draw(new Line2D.Double(s * 0.28, s / 2.0, s * 0.72, s / 2.0));
        });
    }

    /** A pencil: edit. */
    public static Icon pencil() { return pencil(SIZE, null); }
    public static Icon pencil(int size, Color c) {
        return make(size, c, (g, s) -> {
            Path2D p = new Path2D.Double();
            p.moveTo(s * 0.2, s * 0.8); p.lineTo(s * 0.2, s * 0.62); p.lineTo(s * 0.66, s * 0.16);
            p.lineTo(s * 0.84, s * 0.34); p.lineTo(s * 0.38, s * 0.8); p.closePath();
            g.draw(p);
            g.draw(new Line2D.Double(s * 0.58, s * 0.24, s * 0.76, s * 0.42));
        });
    }

    /** Two overlapping squares: duplicate / copy. */
    public static Icon copy() { return copy(SIZE, null); }
    public static Icon copy(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.drawRoundRect((int) (s * 0.32), (int) (s * 0.32), (int) (s * 0.55), (int) (s * 0.55), 2, 2);
            g.drawRoundRect((int) (s * 0.12), (int) (s * 0.12), (int) (s * 0.55), (int) (s * 0.55), 2, 2);
        });
    }

    /** A box with an arrow leaving its corner: export / open externally. */
    public static Icon export() { return export(SIZE, null); }
    public static Icon export(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Path2D.Double() {{
                moveTo(s * 0.55, s * 0.18); lineTo(s * 0.18, s * 0.18); lineTo(s * 0.18, s * 0.82);
                lineTo(s * 0.82, s * 0.82); lineTo(s * 0.82, s * 0.45);
            }});
            g.draw(new Line2D.Double(s * 0.45, s * 0.55, s * 0.86, s * 0.14));
            g.draw(new Line2D.Double(s * 0.62, s * 0.14, s * 0.86, s * 0.14));
            g.draw(new Line2D.Double(s * 0.86, s * 0.14, s * 0.86, s * 0.38));
        });
    }

    /** A bin: delete. */
    public static Icon trash() { return trash(SIZE, null); }
    public static Icon trash(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Line2D.Double(s * 0.15, s * 0.25, s * 0.85, s * 0.25));
            g.draw(new Line2D.Double(s * 0.38, s * 0.25, s * 0.38, s * 0.14));
            g.draw(new Line2D.Double(s * 0.62, s * 0.25, s * 0.62, s * 0.14));
            g.draw(new Line2D.Double(s * 0.38, s * 0.14, s * 0.62, s * 0.14));
            g.draw(new Path2D.Double() {{
                moveTo(s * 0.24, s * 0.25); lineTo(s * 0.3, s * 0.86); lineTo(s * 0.7, s * 0.86); lineTo(s * 0.76, s * 0.25);
            }});
            g.draw(new Line2D.Double(s * 0.42, s * 0.4, s * 0.44, s * 0.74));
            g.draw(new Line2D.Double(s * 0.58, s * 0.4, s * 0.56, s * 0.74));
        });
    }

    /** A five-pointed star, filled or outlined: ratings. */
    public static Icon star(int size, Color c, boolean filled) {
        return make(size, c, (g, s) -> {
            Path2D p = new Path2D.Double();
            double cx = s / 2.0, cy = s / 2.0, ro = s * 0.46, ri = s * 0.2;
            for (int i = 0; i < 10; i++) {
                double r = (i % 2 == 0) ? ro : ri;
                double a = -Math.PI / 2 + i * Math.PI / 5;
                double x = cx + r * Math.cos(a), y = cy + r * Math.sin(a);
                if (i == 0) p.moveTo(x, y); else p.lineTo(x, y);
            }
            p.closePath();
            if (filled) g.fill(p); else g.draw(p);
        });
    }

    /** A chevron pointing right (expand / drill in). */
    public static Icon chevronRight() { return chevron(SIZE, null, 0); }
    public static Icon chevronDown()  { return chevron(SIZE, null, 1); }
    public static Icon chevronUp()    { return chevron(SIZE, null, 2); }
    /** @param dir 0 right, 1 down, 2 up, 3 left */
    public static Icon chevron(int size, Color c, int dir) {
        return make(size, c, (g, s) -> {
            double a = s * 0.32, b = s * 0.68, m = s / 2.0;
            Path2D p = new Path2D.Double();
            switch (dir) {
                case 1 -> { p.moveTo(a, a + 2); p.lineTo(m, b); p.lineTo(b, a + 2); }
                case 2 -> { p.moveTo(a, b - 2); p.lineTo(m, a); p.lineTo(b, b - 2); }
                case 3 -> { p.moveTo(b - 2, a); p.lineTo(a, m); p.lineTo(b - 2, b); }
                default -> { p.moveTo(a + 2, a); p.lineTo(b, m); p.lineTo(a + 2, b); }
            }
            g.draw(p);
        });
    }

    /** A thin arrow (up, down, left, right). */
    public static Icon arrow(int size, Color c, int dir) {
        return make(size, c, (g, s) -> {
            double m = s / 2.0, a = s * 0.2, b = s * 0.8;
            switch (dir) {
                case 1 -> { g.draw(new Line2D.Double(m, a, m, b)); g.draw(new Line2D.Double(m - 4, b - 4, m, b)); g.draw(new Line2D.Double(m + 4, b - 4, m, b)); }
                case 2 -> { g.draw(new Line2D.Double(m, a, m, b)); g.draw(new Line2D.Double(m - 4, a + 4, m, a)); g.draw(new Line2D.Double(m + 4, a + 4, m, a)); }
                case 3 -> { g.draw(new Line2D.Double(a, m, b, m)); g.draw(new Line2D.Double(a + 4, m - 4, a, m)); g.draw(new Line2D.Double(a + 4, m + 4, a, m)); }
                default -> { g.draw(new Line2D.Double(a, m, b, m)); g.draw(new Line2D.Double(b - 4, m - 4, b, m)); g.draw(new Line2D.Double(b - 4, m + 4, b, m)); }
            }
        });
    }

    /** A lightning bolt: the suite accent mark used for custom actions. */
    public static Icon bolt(int size, Color c) {
        return make(size, c, (g, s) -> {
            Path2D p = new Path2D.Double();
            p.moveTo(s * 0.58, s * 0.08); p.lineTo(s * 0.24, s * 0.56); p.lineTo(s * 0.48, s * 0.56);
            p.lineTo(s * 0.4, s * 0.92); p.lineTo(s * 0.78, s * 0.42); p.lineTo(s * 0.54, s * 0.42); p.closePath();
            g.fill(p);
        });
    }

    /** An open book: documentation / library. */
    public static Icon book() { return book(SIZE, null); }
    public static Icon book(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Path2D.Double() {{
                moveTo(s * 0.5, s * 0.25); curveTo(s * 0.4, s * 0.15, s * 0.2, s * 0.15, s * 0.1, s * 0.2);
                lineTo(s * 0.1, s * 0.8); curveTo(s * 0.2, s * 0.75, s * 0.4, s * 0.75, s * 0.5, s * 0.85);
                curveTo(s * 0.6, s * 0.75, s * 0.8, s * 0.75, s * 0.9, s * 0.8);
                lineTo(s * 0.9, s * 0.2); curveTo(s * 0.8, s * 0.15, s * 0.6, s * 0.15, s * 0.5, s * 0.25);
            }});
            g.draw(new Line2D.Double(s * 0.5, s * 0.25, s * 0.5, s * 0.85));
        });
    }

    /** A funnel: filter. */
    public static Icon funnel(int size, Color c) {
        return make(size, c, (g, s) -> {
            Path2D p = new Path2D.Double();
            p.moveTo(s * 0.1, s * 0.15); p.lineTo(s * 0.9, s * 0.15); p.lineTo(s * 0.58, s * 0.52);
            p.lineTo(s * 0.58, s * 0.85); p.lineTo(s * 0.42, s * 0.78); p.lineTo(s * 0.42, s * 0.52); p.closePath();
            g.draw(p);
        });
    }

    /** A magnifier: search. */
    public static Icon search(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Ellipse2D.Double(s * 0.12, s * 0.12, s * 0.52, s * 0.52));
            g.draw(new Line2D.Double(s * 0.6, s * 0.6, s * 0.9, s * 0.9));
        });
    }

    /** A tick. */
    public static Icon tick(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.setStroke(new BasicStroke(Math.max(1.6f, s / 8f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Path2D.Double() {{ moveTo(s * 0.2, s * 0.52); lineTo(s * 0.42, s * 0.74); lineTo(s * 0.82, s * 0.3); }});
        });
    }

    /** A cross. */
    public static Icon cross(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Line2D.Double(s * 0.25, s * 0.25, s * 0.75, s * 0.75));
            g.draw(new Line2D.Double(s * 0.75, s * 0.25, s * 0.25, s * 0.75));
        });
    }

    /** A small filled circle: the unread / attention dot. */
    public static Icon dot(int size, Color c) {
        return make(size, c, (g, s) -> g.fill(new Ellipse2D.Double(1, 1, s - 2, s - 2)));
    }

    /** The GitHub mark, simplified: a circle with the cat silhouette suggested. */
    public static Icon github(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.fill(new Ellipse2D.Double(1, 1, s - 2, s - 2));
            g.setColor(Theme.BG);
            g.fill(new Ellipse2D.Double(s * 0.3, s * 0.3, s * 0.4, s * 0.36));
            g.fill(new Rectangle(Math.round(s * 0.42f), Math.round(s * 0.6f), Math.round(s * 0.16f), Math.round(s * 0.3f)));
        });
    }

    /** The bookmark used for favourites / installed marks in lists. */
    public static Icon bookmark(int size, Color c, boolean filled) {
        return make(size, c, (g, s) -> {
            Path2D p = new Path2D.Double();
            p.moveTo(s * 0.25, s * 0.12); p.lineTo(s * 0.75, s * 0.12); p.lineTo(s * 0.75, s * 0.88);
            p.lineTo(s * 0.5, s * 0.68); p.lineTo(s * 0.25, s * 0.88); p.closePath();
            if (filled) g.fill(p); else g.draw(p);
        });
    }

    /** Three horizontal lines: the actions menu. */
    public static Icon hamburger(int size, Color c) {
        return make(size, c, (g, s) -> {
            for (double y : new double[]{s * 0.25, s * 0.5, s * 0.75}) g.draw(new Line2D.Double(s * 0.15, y, s * 0.85, y));
        });
    }

    /** Word wrap: lines with a returning arrow. */
    public static Icon wrap(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Line2D.Double(s * 0.15, s * 0.25, s * 0.85, s * 0.25));
            g.draw(new Line2D.Double(s * 0.15, s * 0.5, s * 0.7, s * 0.5));
            g.draw(new java.awt.geom.Arc2D.Double(s * 0.5, s * 0.5, s * 0.36, s * 0.3, -90, 180, java.awt.geom.Arc2D.OPEN));
            g.draw(new Line2D.Double(s * 0.4, s * 0.8, s * 0.68, s * 0.8));
            g.draw(new Line2D.Double(s * 0.48, s * 0.72, s * 0.4, s * 0.8));
            g.draw(new Line2D.Double(s * 0.48, s * 0.88, s * 0.4, s * 0.8));
            g.draw(new Line2D.Double(s * 0.15, s * 0.8, s * 0.3, s * 0.8));
        });
    }

    /** A flat icon-only button, the style used for every toolbar glyph. */
    /** A tray: the mark on the Organizer's Inbox collection. */
    public static Icon inbox(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Path2D.Double() {{
                moveTo(1.5, s * 0.55); lineTo(s * 0.3, 1.5); lineTo(s * 0.7, 1.5); lineTo(s - 1.5, s * 0.55);
            }});
            g.draw(new java.awt.geom.Rectangle2D.Double(1.5, s * 0.55, s - 3, s * 0.4 - 1.5));
        });
    }

    /** A filled triangle: the mark on a button that starts something. */
    public static Icon play(int size, Color c) {
        return make(size, c, (g, s) -> {
            int m = Math.max(2, s / 5);
            g.fill(new Polygon(new int[]{m, m, s - m}, new int[]{m - 1, s - m + 1, s / 2}, 3));
        });
    }

    /** A clock face: the mark for a resource pool, which is about pacing. */
    public static Icon clock(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.draw(new Ellipse2D.Double(1.5, 1.5, s - 3.5, s - 3.5));
            double cx = (s - 1) / 2.0, cy = (s - 1) / 2.0;
            g.draw(new Line2D.Double(cx, cy, cx, cy - s / 4.0));
            g.draw(new Line2D.Double(cx, cy, cx + s / 5.0, cy));
        });
    }

    /** The section mark, the glyph Intruder uses to bracket a payload position. */
    public static Icon section(int size, Color c) {
        return make(size, c, (g, s) -> {
            g.setFont(new Font("Dialog", Font.BOLD, Math.round(s * 0.85f)));
            FontMetrics fm = g.getFontMetrics();
            String t = "\u00a7";
            g.drawString(t, (float) (s / 2.0 - fm.stringWidth(t) / 2.0),
                    (float) (s / 2.0 + (fm.getAscent() - fm.getDescent()) / 2.0));
        });
    }

    public static JButton button(Icon icon, String tip, Runnable action) {
        JButton b = new JButton(icon);
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setContentAreaFilled(false);
        b.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        if (action != null) b.addActionListener(e -> action.run());
        return b;
    }

    /** A kebab button that opens the given menu beneath itself. */
    public static JButton menuButton(String tip, java.util.function.Supplier<JPopupMenu> menu) {
        JButton b = button(kebab(), tip, null);
        b.addActionListener(e -> {
            JPopupMenu m = menu.get();
            if (m != null) m.show(b, 0, b.getHeight());
        });
        return b;
    }
}
