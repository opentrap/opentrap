package opentrap.ui;

import javax.swing.*;
import javax.swing.border.Border;
import java.awt.*;
import java.awt.image.BufferedImage;

/** Shared look-and-feel constants and small widget factories. */
public final class Ui {
    private Ui() {}

    // Base sizes; actual fonts derive from these through UiScale so the whole
    // interface scales from one factor.
    public static final Font PLAIN   = new Font("Dialog", Font.PLAIN, 12);
    public static final Font BOLD    = new Font("Dialog", Font.BOLD, 12);
    public static final Font MONO    = new Font(Font.MONOSPACED, Font.PLAIN, 12);
    public static final Font HEADING = new Font("Dialog", Font.BOLD, 13);

    public static final Color ACCENT      = Theme.ACCENT;
    public static final Color PANEL_BG    = Theme.SURFACE;
    public static final Color GRID        = Theme.BORDER;
    public static final Color ERROR_RED   = new Color(0xCC, 0x00, 0x00);
    public static final Color ZEBRA       = Theme.ACCENT_FAINT;
    public static final Color SELECTION   = Theme.ACCENT_LIGHT;

    // Severity palette, shared by the dashboard badges and the issue tree so a
    // finding's colour means the same thing everywhere.
    public static final Color SEV_HIGH   = new Color(0xD5, 0x1F, 0x3D);
    public static final Color SEV_MEDIUM = new Color(0xE8, 0x82, 0x0C);
    public static final Color SEV_LOW    = new Color(0x5C, 0x9A, 0xCF);
    public static final Color SEV_INFO   = new Color(0x88, 0x88, 0x88);

    public static Color severityColor(opentrap.core.Issue.Severity s) {
        if (s == null) return SEV_INFO;
        return switch (s) {
            case HIGH -> SEV_HIGH;
            case MEDIUM -> SEV_MEDIUM;
            case LOW -> SEV_LOW;
            default -> SEV_INFO;
        };
    }

    /** Colour the given table column by its severity label (High/Medium/Low/Information). */
    public static void severityColumn(JTable table, int col) {
        javax.swing.table.DefaultTableCellRenderer r = new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(JTable t, Object v,
                    boolean sel, boolean focus, int row, int column) {
                super.getTableCellRendererComponent(t, v, sel, focus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                setFont(BOLD);
                String s = (v == null) ? "" : v.toString();
                Color c = switch (s) {
                    case "High" -> SEV_HIGH;
                    case "Medium" -> SEV_MEDIUM;
                    case "Low" -> SEV_LOW;
                    default -> SEV_INFO;
                };
                setForeground(sel ? t.getSelectionForeground() : c);
                return this;
            }
        };
        table.getColumnModel().getColumn(col).setCellRenderer(r);
    }

    /** A small filled dot in a severity's colour, for the issue tree. */
    public static Icon severityDot(opentrap.core.Issue.Severity s) {
        final Color c = severityColor(s);
        return new Icon() {
            @Override public int getIconWidth()  { return 12; }
            @Override public int getIconHeight() { return 12; }
            @Override public void paintIcon(Component comp, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c);
                g2.fillOval(x + 1, y + 1, 9, 9);
                g2.setColor(c.darker());
                g2.drawOval(x + 1, y + 1, 9, 9);
                g2.dispose();
            }
        };
    }

    public static JLabel heading(String text) {
        JLabel l = new JLabel(text);
        l.setFont(HEADING);
        return l;
    }

    public static JLabel label(String text) {
        JLabel l = new JLabel(text);
        l.setFont(PLAIN);
        return l;
    }

    public static JLabel bold(String text) {
        JLabel l = new JLabel(text);
        l.setFont(BOLD);
        return l;
    }

    public static JCheckBox check(String text, boolean selected) {
        JCheckBox c = new JCheckBox(text, selected);
        c.setFont(PLAIN);
        c.setFocusable(false);
        c.setOpaque(false);
        return c;
    }

    public static JRadioButton radio(String text, boolean selected) {
        JRadioButton r = new JRadioButton(text, selected);
        r.setFont(PLAIN);
        r.setFocusable(false);
        r.setOpaque(false);
        return r;
    }

    public static JButton button(String text) {
        JButton b = new JButton(text);
        b.setFont(PLAIN);
        return b;
    }

    public static JButton button(String text, String tooltip) {
        JButton b = button(text);
        b.setToolTipText(tooltip);
        return b;
    }

    /** The one button on a panel that starts its main action, filled with the accent. */
    public static JButton primaryButton(String text, String tooltip) {
        JButton b = button(text, tooltip);
        return Theme.primary(b);
    }

    public static JTextField field(String text, int columns) {
        JTextField f = new JTextField(text, columns);
        f.setFont(PLAIN);
        return f;
    }

    public static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> c = new JComboBox<>(items);
        c.setFont(PLAIN);
        c.setMaximumRowCount(14);
        return c;
    }

    public static JTextArea note(String text) {
        JTextArea a = new JTextArea(text);
        a.setFont(PLAIN);
        a.setEditable(false);
        a.setOpaque(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBorder(null);
        a.setFocusable(false);
        return a;
    }

    public static Border pad(int t, int l, int b, int r) {
        return BorderFactory.createEmptyBorder(t, l, b, r);
    }

    public static JPanel row(Component... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        p.setOpaque(false);
        for (Component c : parts) p.add(c);
        return p;
    }

    public static JPanel vbox(Component... parts) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setOpaque(false);
        for (Component c : parts) {
            if (c instanceof JComponent jc) jc.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(c);
        }
        return p;
    }

    /**
     * A neat column of equal-width, equal-height buttons (Burp style), hugging
     * the top. Use this for any vertical group of buttons so they line up instead
     * of each being sized to its own text.
     */
    public static JPanel buttonColumn(AbstractButton... buttons) {
        JPanel grid = new JPanel(new GridLayout(buttons.length, 1, 0, 5));
        grid.setOpaque(false);
        for (AbstractButton b : buttons) grid.add(b);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.add(grid, BorderLayout.NORTH); // keep buttons their natural height, aligned to the top
        return wrap;
    }

    /** A neat row of equal-width buttons. */
    public static JPanel buttonRow(AbstractButton... buttons) {
        JPanel grid = new JPanel(new GridLayout(1, buttons.length, 5, 0));
        grid.setOpaque(false);
        for (AbstractButton b : buttons) grid.add(b);
        JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrap.setOpaque(false);
        wrap.add(grid);
        return wrap;
    }

    /** Configure a table with the conventions used throughout the suite. */
    public static void styleTable(JTable t) {
        t.setFont(PLAIN);
        t.setRowHeight(22);
        t.getTableHeader().setFont(BOLD);
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setToolTipText("click column to sort ascending (SHIFT-click = descending)");
        t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        t.setGridColor(GRID);
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setSelectionBackground(SELECTION);
        t.setSelectionForeground(Color.BLACK);
    }

    /** Center the text in every non-boolean cell of a table, for a tidy grid. */
    /**
     * Size columns proportionally to the given weights and let them fill the
     * viewport width, adapting to the window. Long columns (host, URL) get more
     * space; a horizontal scrollbar appears only when the window is too narrow
     * for the minimum widths. Call after the model is set.
     */
    public static void distributeWidths(JTable t, int... weights) {
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        t.getTableHeader().setResizingAllowed(true);
        t.getTableHeader().setReorderingAllowed(false);
        int cols = t.getColumnModel().getColumnCount();
        for (int i = 0; i < weights.length && i < cols; i++) {
            javax.swing.table.TableColumn c = t.getColumnModel().getColumn(i);
            c.setPreferredWidth(weights[i]);
            c.setMinWidth(Math.max(24, weights[i] / 3));
        }
    }

    public static void centerCells(JTable t) {
        javax.swing.table.DefaultTableCellRenderer r = new javax.swing.table.DefaultTableCellRenderer();
        r.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        t.setDefaultRenderer(Object.class, r);
        t.setDefaultRenderer(Number.class, r);
        t.setDefaultRenderer(String.class, r);
        t.setDefaultRenderer(Integer.class, r);
        // Center the header labels too.
        ((javax.swing.table.DefaultTableCellRenderer) t.getTableHeader().getDefaultRenderer())
                .setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
    }

    /**
     * Set initial column widths and let the columns stretch in proportion to
     * fill the table's pane, so no table sits in a strip of empty space. A
     * table that needs a different mode sets it after calling this.
     */
    public static void setWidths(JTable t, int... widths) {
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        for (int i = 0; i < widths.length && i < t.getColumnCount(); i++) {
            javax.swing.table.TableColumn c = t.getColumnModel().getColumn(i);
            c.setPreferredWidth(widths[i]);
            c.setMinWidth(Math.max(24, widths[i] / 3));
        }
    }

    /**
     * Give a split pane a sensible initial division and keep it proportional as
     * the window resizes, instead of a fixed divider that leaves one pane tiny.
     * weight is the fraction given to the first (top/left) component.
     */
    public static void balance(javax.swing.JSplitPane sp, double weight) {
        sp.setResizeWeight(weight);
        sp.setContinuousLayout(true);
        sp.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean set = false;
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int dim = sp.getOrientation() == javax.swing.JSplitPane.VERTICAL_SPLIT
                        ? sp.getHeight() : sp.getWidth();
                if (!set && dim > 0) { set = true; sp.setDividerLocation(weight); }
            }
        });
    }

    public static JScrollPane scroll(Component c) {
        JScrollPane s = new JScrollPane(c);
        s.getVerticalScrollBar().setUnitIncrement(16);
        s.setBorder(BorderFactory.createLineBorder(GRID));
        if (c instanceof JTable t) {
            // The space under the last row reads as part of the table, not a
            // grey hole: same background, and the table paints to the bottom.
            t.setFillsViewportHeight(true);
            s.getViewport().setBackground(t.getBackground());
        }
        return s;
    }

    /** Truncate long labels the way the original context menus did. */
    /**
     * A clickable filter bar, styled so it is obviously pressable: a funnel
     * icon, a button border and a trailing hint. Used by the proxy history and
     * the site map so the filter popup is clearly a click away. Set the text and
     * action on the returned button.
     */
    public static JButton filterButton() {
        JButton b = new JButton();
        b.setFont(PLAIN);
        b.setHorizontalAlignment(SwingConstants.LEFT);
        b.setFocusable(false);
        b.setIcon(funnelIcon());
        b.setIconTextGap(8);
        b.setBackground(Color.WHITE);
        b.setOpaque(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT), pad(4, 8, 4, 8)));
        b.setToolTipText("click to open the filter and full-text search");
        return b;
    }

    /**
     * A tidy label-and-field form. Labels are right-aligned in a fixed first
     * column and fields share a left edge in the second, so every field lines up
     * no matter how long its label is.
     *
     * <p>Use this wherever a panel has more than one labelled input, so the whole
     * app stays aligned rather than each label pushing its field to a different
     * place. Pass label, component, label, component, ...
     */
    public static JPanel form(Object... labelThenComponent) {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        for (int i = 0; i + 1 < labelThenComponent.length; i += 2) {
            c.gridy = i / 2;
            c.insets = new Insets(2, 0, 2, 8);
            c.gridx = 0; c.weightx = 0;
            c.anchor = GridBagConstraints.LINE_END;
            c.fill = GridBagConstraints.NONE;
            p.add(label(String.valueOf(labelThenComponent[i])), c);

            c.insets = new Insets(2, 0, 2, 0);
            c.gridx = 1; c.weightx = 1;
            c.anchor = GridBagConstraints.LINE_START;
            c.fill = GridBagConstraints.HORIZONTAL;
            p.add((Component) labelThenComponent[i + 1], c);
        }
        return p;
    }

    /**
     * Assemble a filter bar: the clickable filter button stretched across, then
     * the quick-search label and field on the right, all at one uniform height.
     * Used by the proxy history and the site map so both bars look identical.
     */
    public static JPanel filterBar(JButton filterButton, JComponent... right) {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                pad(2, 2, 3, 2), BorderFactory.createLineBorder(GRID)));

        // The field's natural height is the common height; shrink the button to it
        // so the two are the same size rather than the button standing taller.
        int h = 0;
        for (JComponent p : right) h = Math.max(h, p.getPreferredSize().height);
        if (h > 0) {
            Dimension d = filterButton.getPreferredSize();
            filterButton.setPreferredSize(new Dimension(d.width, h));
        }

        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.CENTER;
        c.gridx = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        bar.add(filterButton, c);

        c.weightx = 0; c.fill = GridBagConstraints.NONE;
        for (JComponent p : right) {
            c.gridx++;
            c.insets = new Insets(0, 6, 0, 2);
            bar.add(p, c);
        }
        return bar;
    }

    /** A small funnel glyph for the filter bar, drawn so it needs no resource. */
    private static Icon funnelIcon() {
        BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(ACCENT);
        Polygon funnel = new Polygon(new int[]{1, 13, 8, 8, 6, 6}, new int[]{2, 2, 7, 12, 12, 7}, 6);
        g.fill(funnel);
        g.dispose();
        return new ImageIcon(img);
    }

    public static String truncate(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return s.substring(0, 25) + "..." + s.substring(s.length() - 30);
    }
}
