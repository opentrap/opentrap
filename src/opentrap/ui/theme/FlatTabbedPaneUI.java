package opentrap.ui.theme;

import opentrap.ui.Theme;

import javax.swing.*;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;

/**
 * Tabs as a row of labels on the page background. The selected tab carries a
 * short accent underline and full-strength text; the others are muted and
 * brighten under the pointer. The tab row is closed by one hairline. Titles
 * are shown with a leading capital so the strip reads as a set of headings.
 */
public class FlatTabbedPaneUI extends BasicTabbedPaneUI {

    private int hover = -1;

    /**
     * Held so they can be taken off again. Swing calls uninstallUI whenever a
     * pane's UI is replaced (a theme switch, or any setUI), and that nulls
     * tabPane. A listener left attached to the pane then fires against a null
     * tabPane and throws on the event thread, once per mouse move, until the
     * interface stops repainting.
     */
    private java.awt.event.MouseMotionListener hoverMotion;
    private java.awt.event.MouseListener hoverExit;

    public static ComponentUI createUI(JComponent c) { return new FlatTabbedPaneUI(); }

    @Override protected void installDefaults() {
        super.installDefaults();
        tabPane.setOpaque(true);
        tabPane.setBackground(Theme.BG);
    }

    @Override protected void installListeners() {
        super.installListeners();
        hoverMotion = new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                JTabbedPane p = tabPane;
                if (p == null) return;
                int i = tabForCoordinate(p, e.getX(), e.getY());
                if (i != hover) { hover = i; p.repaint(); }
            }
        };
        hoverExit = new java.awt.event.MouseAdapter() {
            @Override public void mouseExited(MouseEvent e) {
                JTabbedPane p = tabPane;
                if (p == null) return;
                hover = -1;
                p.repaint();
            }
        };
        tabPane.addMouseMotionListener(hoverMotion);
        tabPane.addMouseListener(hoverExit);
    }

    @Override protected void uninstallListeners() {
        if (tabPane != null) {
            if (hoverMotion != null) tabPane.removeMouseMotionListener(hoverMotion);
            if (hoverExit != null) tabPane.removeMouseListener(hoverExit);
        }
        hoverMotion = null;
        hoverExit = null;
        hover = -1;
        super.uninstallListeners();
    }

    /** Never stretch a row of tabs to fill the width; tabs keep their natural size. */
    @Override protected boolean shouldPadTabRun(int p, int run) { return false; }

    @Override protected int getTabLabelShiftX(int p, int i, boolean s) { return 0; }
    @Override protected int getTabLabelShiftY(int p, int i, boolean s) { return 0; }
    @Override protected int calculateTabHeight(int p, int i, int fontHeight) { return fontHeight + tabInsets.top + tabInsets.bottom + 4; }

    @Override protected void paintTabBackground(Graphics g, int p, int i, int x, int y, int w, int h, boolean sel) {
        Color custom = tabPane.getBackgroundAt(i);
        if (custom != null && !custom.equals(tabPane.getBackground())) {
            // A tool asking for attention flashes its tab with the accent.
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.ACCENT_LIGHT);
            g2.fillRoundRect(x + 2, y + 3, w - 4, h - 6, 8, 8);
            g2.dispose();
        }
    }

    @Override protected void paintTabBorder(Graphics g, int p, int i, int x, int y, int w, int h, boolean sel) {
        if (sel) {
            g.setColor(Theme.ACCENT);
            g.fillRect(x + 8, y + h - 2, w - 16, 2);
        }
    }

    @Override protected void paintText(Graphics g, int p, Font font, FontMetrics fm, int i, String title,
                                       Rectangle r, boolean sel) {
        String shown = title == null || title.isEmpty() ? "" : Character.toUpperCase(title.charAt(0)) + title.substring(1);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setFont(font);
        Color fg = tabPane.getForegroundAt(i);
        if (!tabPane.isEnabledAt(i)) fg = Theme.BORDER;
        else if (sel) fg = Theme.TAB_TEXT_ON;
        else if (fg == null || fg instanceof javax.swing.plaf.UIResource) fg = Theme.TAB_TEXT;
        g2.setColor(fg);
        int tw = fm.stringWidth(shown);
        g2.drawString(shown, r.x + (r.width - tw) / 2, r.y + fm.getAscent());
        g2.dispose();
    }

    @Override protected void paintContentBorder(Graphics g, int p, int sel) {
        // The hairline under the tab row runs the full width, even when a right
        // inset keeps the end of the row clear for the suite's tool icons.
        int w = tabPane.getWidth();
        Insets ins = tabPane.getInsets();
        int y = ins.top + calculateTabAreaHeight(p, runCount, maxTabHeight) - 1;
        g.setColor(Theme.BORDER);
        g.fillRect(0, y, w, 1);
    }

    @Override protected void paintFocusIndicator(Graphics g, int p, Rectangle[] rects, int i, Rectangle ir, Rectangle tr, boolean sel) {}

    @Override protected int calculateTabWidth(int p, int i, FontMetrics fm) {
        // Measure the capitalised title so the label is never clipped.
        String t = tabPane.getTitleAt(i);
        int base = super.calculateTabWidth(p, i, fm);
        if (t != null && !t.isEmpty()) {
            String shown = Character.toUpperCase(t.charAt(0)) + t.substring(1);
            base += Math.max(0, fm.stringWidth(shown) - fm.stringWidth(t));
        }
        return base;
    }
}
