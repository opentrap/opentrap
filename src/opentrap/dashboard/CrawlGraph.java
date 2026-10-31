package opentrap.dashboard;

import opentrap.ui.Glyphs;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Live crawl view: an empty state while nothing is crawling, and while a
 * crawl runs a live tree of the paths it has discovered, drawn as a radial
 * graph from the host at the centre. Fed with URLs as they are seen.
 */
public final class CrawlGraph extends JPanel {

    private final CardLayout cards = new CardLayout();
    private final Graph graph = new Graph();
    private final JLabel caption = Ui.label("");

    public CrawlGraph() {
        setLayout(cards);
        setOpaque(false);
        JPanel empty = new JPanel(new GridBagLayout());
        empty.setOpaque(false);
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        JLabel icon = new JLabel(Glyphs.search(56, Theme.BORDER));
        JLabel h = Ui.heading("Not currently crawling");
        JLabel t = Ui.label("Display shows when live crawl is active.");
        t.setForeground(Theme.MUTED);
        for (JComponent c : new JComponent[]{icon, h, t}) { c.setAlignmentX(Component.CENTER_ALIGNMENT); col.add(c); col.add(Box.createVerticalStrut(8)); }
        empty.add(col, new GridBagConstraints());
        add(empty, "empty");

        JPanel live = new JPanel(new BorderLayout());
        live.setOpaque(false);
        caption.setForeground(Theme.MUTED);
        caption.setBorder(Ui.pad(6, 8, 4, 8));
        live.add(caption, BorderLayout.NORTH);
        live.add(graph, BorderLayout.CENTER);
        add(live, "live");
        cards.show(this, "empty");
    }

    /** Replace the graph's contents with these URLs (any order). */
    public void setUrls(List<String> urls, boolean crawling) {
        graph.set(urls);
        caption.setText(urls.size() + " location(s) discovered" + (crawling ? ", crawling..." : ""));
        cards.show(this, urls.isEmpty() && !crawling ? "empty" : "live");
    }

    public void clear() { graph.set(List.of()); cards.show(this, "empty"); }

    /** A radial tree of path segments. */
    private static final class Graph extends JComponent {
        private final Node root = new Node("");
        private int count;

        private static final class Node {
            final String name;
            final Map<String, Node> kids = new LinkedHashMap<>();
            Node(String n) { name = n; }
            int leaves() { if (kids.isEmpty()) return 1; int s = 0; for (Node k : kids.values()) s += k.leaves(); return s; }
        }

        void set(List<String> urls) {
            root.kids.clear();
            count = 0;
            for (String u : urls) {
                try {
                    java.net.URI uri = java.net.URI.create(u);
                    String host = uri.getHost() == null ? u : uri.getHost();
                    Node h = root.kids.computeIfAbsent(host, Node::new);
                    String path = uri.getRawPath() == null ? "" : uri.getRawPath();
                    Node cur = h;
                    for (String seg : path.split("/")) {
                        if (seg.isEmpty()) continue;
                        cur = cur.kids.computeIfAbsent(seg, Node::new);
                    }
                    count++;
                } catch (Exception ignore) { }
            }
            repaint();
        }

        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Theme.BG);
            g2.fillRect(0, 0, getWidth(), getHeight());
            if (root.kids.isEmpty()) { g2.dispose(); return; }
            double cx = getWidth() / 2.0, cy = getHeight() / 2.0;
            int depth = depth(root, 0);
            double ring = Math.max(40, Math.min(cx, cy) / Math.max(1, depth + 0.5));
            g2.setFont(Ui.PLAIN.deriveFont(10f));
            layout(g2, root, cx, cy, 0, 0, Math.PI * 2, ring, cx, cy);
            g2.dispose();
        }

        private int depth(Node n, int d) {
            int m = d;
            for (Node k : n.kids.values()) m = Math.max(m, depth(k, d + 1));
            return m;
        }

        private void layout(Graphics2D g, Node n, double x, double y, int level, double a0, double a1, double ring, double cx, double cy) {
            int total = Math.max(1, n.leaves());
            double a = a0;
            for (Node k : n.kids.values()) {
                double span = (a1 - a0) * k.leaves() / total;
                double mid = a + span / 2;
                double kx = cx + Math.cos(mid) * ring * (level + 1);
                double ky = cy + Math.sin(mid) * ring * (level + 1);
                g.setColor(Theme.BORDER);
                g.draw(new Line2D.Double(x, y, kx, ky));
                layout(g, k, kx, ky, level + 1, a, a + span, ring, cx, cy);
                a += span;
            }
            // Draw this node on top of its edges.
            double r = level == 0 ? 7 : 4.5;
            g.setColor(level == 0 ? Ui.ACCENT : n.kids.isEmpty() ? Theme.MUTED : Theme.CONTROL);
            g.fill(new Ellipse2D.Double(x - r, y - r, 2 * r, 2 * r));
            if (level <= 2 && !n.name.isEmpty()) {
                g.setColor(Theme.TEXT);
                String label = n.name.length() > 18 ? n.name.substring(0, 16) + ".." : n.name;
                g.drawString(label, (float) (x + r + 2), (float) (y + 4));
            }
        }
    }
}
