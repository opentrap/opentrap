package opentrap.ui;

import javax.swing.*;
import java.awt.*;

/**
 * The numbered-tab strip Repeater and Intruder use: each tab carries a close
 * mark, a "+" tab opens a new one, and a search mark and a collapse chevron
 * sit at the right end of the row.
 */
public final class TabStrip {
    private TabStrip() {}

    /** Give the tab at {@code index} a title with a close mark. */
    public static void closable(JTabbedPane tabs, int index) {
        if (index < 0) return;
        String title = tabs.getTitleAt(index);
        Component comp = tabs.getComponentAt(index);
        JPanel head = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        head.setOpaque(false);
        JLabel l = new JLabel(title);
        l.setFont(Ui.PLAIN);
        head.add(l);
        JButton x = Glyphs.button(Glyphs.cross(12, Theme.MUTED), "Close tab", () -> {
            int i = tabs.indexOfComponent(comp);
            if (i >= 0 && realTabCount(tabs) > 1) tabs.remove(i);
        });
        x.setBorder(BorderFactory.createEmptyBorder(0, 2, 0, 0));
        head.add(x);
        tabs.setTabComponentAt(index, head);
        // Selecting the tab via its header.
        head.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mousePressed(java.awt.event.MouseEvent e) {
                int i = tabs.indexOfComponent(comp);
                if (i >= 0) tabs.setSelectedIndex(i);
            }
        });
    }

    private static int realTabCount(JTabbedPane tabs) {
        int n = 0;
        for (int i = 0; i < tabs.getTabCount(); i++) if (!"+".equals(tabs.getTitleAt(i))) n++;
        return n;
    }

    /**
     * Wrap a tab pane with the "+" tab and the right-hand marks. {@code onNew}
     * runs when the "+" tab is chosen; the new tab is expected to select itself.
     */
    public static JComponent wrap(JTabbedPane tabs, Runnable onNew) {
        // The "+" tab stays last; choosing it opens a new tab instead.
        JPanel plusBody = new JPanel();
        tabs.addTab("+", plusBody);
        JLabel plus = new JLabel(Glyphs.plus(14, Theme.ICON));
        plus.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        tabs.setTabComponentAt(tabs.getTabCount() - 1, plus);
        tabs.addChangeListener(e -> {
            int i = tabs.getSelectedIndex();
            if (i >= 0 && tabs.getComponentAt(i) == plusBody) {
                SwingUtilities.invokeLater(() -> {
                    onNew.run();
                    // Keep "+" last.
                    int p = tabs.indexOfComponent(plusBody);
                    if (p >= 0 && p != tabs.getTabCount() - 1) {
                        tabs.remove(p);
                        tabs.addTab("+", plusBody);
                        tabs.setTabComponentAt(tabs.getTabCount() - 1, plus);
                    }
                    if (tabs.getSelectedComponent() == plusBody && tabs.getTabCount() > 1) tabs.setSelectedIndex(tabs.getTabCount() - 2);
                });
            }
        });
        // Any tab added later also keeps "+" at the end.
        tabs.addContainerListener(new java.awt.event.ContainerAdapter() {
            @Override public void componentAdded(java.awt.event.ContainerEvent e) {
                int p = tabs.indexOfComponent(plusBody);
                if (p >= 0 && p != tabs.getTabCount() - 1) SwingUtilities.invokeLater(() -> {
                    int q = tabs.indexOfComponent(plusBody);
                    if (q >= 0 && q != tabs.getTabCount() - 1) {
                        Component sel = tabs.getSelectedComponent();
                        tabs.remove(q);
                        tabs.addTab("+", plusBody);
                        tabs.setTabComponentAt(tabs.getTabCount() - 1, plus);
                        if (sel != null && sel != plusBody) tabs.setSelectedComponent(sel);
                    }
                });
            }
        });

        final JPanel marks = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        marks.setOpaque(false);
        marks.add(Glyphs.button(Glyphs.search(16, Theme.ICON), "Search tabs", () -> {
            String q = JOptionPane.showInputDialog(tabs, "Tab name or number:");
            if (q == null || q.isBlank()) return;
            for (int i = 0; i < tabs.getTabCount(); i++) if (tabs.getTitleAt(i).toLowerCase().contains(q.toLowerCase())) { tabs.setSelectedIndex(i); return; }
        }));
        marks.add(Glyphs.button(Glyphs.chevron(14, Theme.ICON, 2), "Collapse the tab strip", () -> tabs.setTabPlacement(tabs.getTabPlacement() == JTabbedPane.TOP ? JTabbedPane.BOTTOM : JTabbedPane.TOP)));

        final JLayeredPane layer = new JLayeredPane();
        layer.setLayout(null);
        layer.add(tabs, JLayeredPane.DEFAULT_LAYER);
        layer.add(marks, JLayeredPane.PALETTE_LAYER);
        layer.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int w = layer.getWidth(), h = layer.getHeight();
                tabs.setBounds(0, 0, w, h);
                tabs.doLayout();
                Dimension d = marks.getPreferredSize();
                int rowH = tabs.getTabCount() > 0 ? tabs.getUI().getTabBounds(tabs, 0).height : d.height;
                marks.setBounds(w - d.width - 8, Math.max(0, (rowH - d.height) / 2), d.width, d.height);
            }
        });
        return layer;
    }
}
