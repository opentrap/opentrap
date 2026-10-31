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
                setHorizontalAlignment(SwingConstants.LEADING);
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

    /** A small filled circle coloured by severity, for the issue-type column. */
    public static javax.swing.Icon severityDot(String severity) {
        Color c = switch (severity == null ? "" : severity) {
            case "High" -> SEV_HIGH;
            case "Medium" -> SEV_MEDIUM;
            case "Low" -> SEV_LOW;
            default -> SEV_INFO;
        };
        int d = 10;
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(d + 2, d + 2, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(c);
        g.fillOval(1, 1, d, d);
        g.dispose();
        return new javax.swing.ImageIcon(img);
    }

    /**
     * Show a small severity dot in front of the text of {@code textCol}, coloured
     * by the value in {@code sevCol} on the same row (reads via the model so it
     * survives sorting).
     */
    public static void severityIconColumn(JTable table, int textCol, int sevCol) {
        table.getColumnModel().getColumn(textCol).setCellRenderer(
            new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable t, Object v,
                        boolean sel, boolean focus, int row, int column) {
                    super.getTableCellRendererComponent(t, v, sel, focus, row, column);
                    int mr = t.convertRowIndexToModel(row);
                    Object sev = t.getModel().getValueAt(mr, sevCol);
                    setIcon(severityDot(String.valueOf(sev)));
                    setIconTextGap(6);
                    return this;
                }
            });
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

    /**
     * User-facing text starts with a capital, as in the reference, so a label
     * written in lower case in code still reads as a proper heading or button.
     * Leading spaces and symbols are kept; only the first letter is raised.
     */
    public static String sentence(String text) {
        if (text == null || text.isEmpty()) return text;
        int i = 0;
        while (i < text.length() && !Character.isLetter(text.charAt(i))) i++;
        if (i >= text.length() || Character.isUpperCase(text.charAt(i))) return text;
        return text.substring(0, i) + Character.toUpperCase(text.charAt(i)) + text.substring(i + 1);
    }

    public static JLabel heading(String text) {
        JLabel l = new JLabel(sentence(text));
        l.setFont(HEADING);
        return l;
    }

    public static JLabel label(String text) {
        JLabel l = new JLabel(sentence(text));
        l.setFont(PLAIN);
        return l;
    }

    public static JLabel bold(String text) {
        JLabel l = new JLabel(sentence(text));
        l.setFont(BOLD);
        return l;
    }

    public static JCheckBox check(String text, boolean selected) {
        JCheckBox c = new JCheckBox(sentence(text), selected);
        c.setFont(PLAIN);
        c.setFocusable(false);
        c.setOpaque(false);
        return c;
    }

    public static JRadioButton radio(String text, boolean selected) {
        JRadioButton r = new JRadioButton(sentence(text), selected);
        r.setFont(PLAIN);
        r.setFocusable(false);
        r.setOpaque(false);
        return r;
    }

    public static JButton button(String text) {
        JButton b = new JButton(sentence(text));
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
        JTextField f = new PlaceholderField(text, columns);
        f.setFont(PLAIN);
        return f;
    }

    /**
     * A text field that paints the "JTextField.placeholderText" client property
     * in muted text while it is empty, as every search box in the suite does.
     */
    public static class PlaceholderField extends JTextField {
        public PlaceholderField(String text, int columns) { super(text, columns); }
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Object ph = getClientProperty("JTextField.placeholderText");
            if (ph == null || !getText().isEmpty()) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Theme.MUTED);
            g2.setFont(getFont());
            Insets in = getInsets();
            FontMetrics fm = g2.getFontMetrics();
            g2.drawString(String.valueOf(ph), in.left, (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    /** A search box: placeholder "Search" and a magnifier at its right edge. */
    public static JTextField searchField(int columns) {
        JTextField f = new PlaceholderField("", columns) {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Icon ic = Glyphs.search(14, Theme.MUTED);
                ic.paintIcon(this, g, getWidth() - ic.getIconWidth() - 8, (getHeight() - ic.getIconHeight()) / 2);
            }
        };
        f.setFont(PLAIN);
        f.putClientProperty("JTextField.placeholderText", "Search");
        f.setBorder(BorderFactory.createCompoundBorder(f.getBorder(), BorderFactory.createEmptyBorder(0, 0, 0, 18)));
        return f;
    }

    public static <T> JComboBox<T> combo(T[] items) {
        JComboBox<T> c = new JComboBox<>(items);
        c.setFont(PLAIN);
        c.setMaximumRowCount(14);
        return c;
    }

    /**
     * A wrapping, read-only paragraph. A plain wrapping text area has no idea
     * how wide it will be laid out, so it reports a one-line (or a one-column)
     * preferred size and gets squashed or clipped. This one reports an honest
     * size for its text: up to {@link #NOTE_COLUMNS} wide and as many rows as
     * that needs. Laid out wider it just uses fewer lines.
     */
    public static JTextArea note(String text) {
        JTextArea a = new JTextArea() {
            @Override public Dimension getPreferredSize() {
                Insets in = getInsets();
                int w = getColumns() * getColumnWidth() + in.left + in.right;
                int h = getRows() * getRowHeight() + in.top + in.bottom;
                return new Dimension(w, h);
            }
            @Override public void setText(String t) {
                super.setText(t);
                sizeNote(this);
            }
        };
        a.setFont(PLAIN);
        a.setEditable(false);
        a.setOpaque(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBorder(null);
        a.setFocusable(false);
        a.setText(text);
        return a;
    }

    /** The widest a note lays itself out before wrapping, in characters. */
    public static final int NOTE_COLUMNS = 90;

    /**
     * Size a note to exactly the height its text needs at {@code width}, so a
     * page that knows how wide its notes will be gets no estimate at all.
     */
    public static void sizeNote(JTextArea a, int width) {
        a.setSize(width, Short.MAX_VALUE);
        int real = a.getUI().getPreferredSize(a).height;   // the true wrapped height
        FontMetrics fm = a.getFontMetrics(a.getFont());     // the same units JTextArea sizes columns and rows in
        a.setColumns(Math.max(1, width / Math.max(1, fm.charWidth('m'))));
        a.setRows(Math.max(1, (int) Math.ceil(real / (double) Math.max(1, fm.getHeight()))));
        a.setMaximumSize(new Dimension(width, a.getPreferredSize().height));
    }

    /** Size a note's rows and columns to its text, and stop layouts stretching it. */
    public static void sizeNote(JTextArea a) {
        String t = a.getText();
        int len = t == null ? 0 : t.length();
        int cols = Math.max(24, Math.min(NOTE_COLUMNS, len));
        a.setColumns(cols);
        // Word wrap lands a little short of the column count, so allow for it.
        a.setRows(Math.max(1, (int) Math.ceil(len / (cols * 0.9))));
        a.setMaximumSize(new Dimension(Integer.MAX_VALUE, a.getPreferredSize().height));
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
     * A neat column of equal-width, equal-height buttons, hugging
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
        t.getTableHeader().setFont(PLAIN);
        t.setDefaultRenderer(Boolean.class, new TickRenderer());
        // Column titles read with a leading capital, as in the reference.
        for (int i = 0; i < t.getColumnModel().getColumnCount(); i++) {
            javax.swing.table.TableColumn c = t.getColumnModel().getColumn(i);
            Object v = c.getHeaderValue();
            if (v instanceof String s) c.setHeaderValue(sentence(s));
        }
        t.getTableHeader().setReorderingAllowed(false);
        t.getTableHeader().setToolTipText("click column to sort ascending (SHIFT-click = descending)");
        t.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        t.setGridColor(GRID);
        t.setShowVerticalLines(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setSelectionBackground(SELECTION);
        t.setSelectionForeground(Theme.TEXT);
        leftAlign(t);
    }

    /**
     * Left-align the header labels and every cell, the convention used by other
     * proxy-first suites. The Metal look and feel centres headers and
     * right-aligns numbers by default, which looks untidy, so override both.
     */
    public static void leftAlign(JTable t) {
        javax.swing.table.DefaultTableCellRenderer left = new javax.swing.table.DefaultTableCellRenderer();
        left.setHorizontalAlignment(SwingConstants.LEADING);
        t.setDefaultRenderer(Object.class, left);
        t.setDefaultRenderer(Number.class, left);
        t.setDefaultRenderer(Integer.class, left);
        t.setDefaultRenderer(Long.class, left);
        t.setDefaultRenderer(String.class, left);
        if (t.getTableHeader().getDefaultRenderer()
                instanceof javax.swing.table.DefaultTableCellRenderer h) {
            h.setHorizontalAlignment(SwingConstants.LEADING);
        }
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
        // Never let a column squeeze narrower than its own header text, so the
        // header labels never truncate to "met...", "st...", "MIM..." etc.
        java.awt.FontMetrics fm = t.getTableHeader().getFontMetrics(t.getTableHeader().getFont());
        int cols = t.getColumnModel().getColumnCount();
        for (int i = 0; i < weights.length && i < cols; i++) {
            javax.swing.table.TableColumn c = t.getColumnModel().getColumn(i);
            String name = String.valueOf(c.getHeaderValue());
            int headerMin = fm.stringWidth(name) + 18;
            c.setPreferredWidth(Math.max(weights[i], headerMin));
            c.setMinWidth(Math.max(Math.max(24, weights[i] / 3), headerMin));
        }
    }

    /** Retained for call sites; tables are left-aligned throughout now. */
    /**
     * Sort a history table newest first by its number column, so the latest
     * entry is always at the top as more arrive. A sorter is created when the
     * table has none; an existing one keeps its filter. The user can still
     * click a header to sort another way.
     */
    public static void newestFirst(JTable t, int numberColumn) {
        javax.swing.RowSorter<? extends javax.swing.table.TableModel> rs = t.getRowSorter();
        if (rs == null) {
            rs = new javax.swing.table.TableRowSorter<>(t.getModel());
            t.setRowSorter(rs);
        }
        rs.setSortKeys(java.util.List.of(
                new javax.swing.RowSorter.SortKey(numberColumn, javax.swing.SortOrder.DESCENDING)));
    }

    public static void centerCells(JTable t) {
        leftAlign(t);
    }

    /**
     * Set initial column widths and let the columns stretch in proportion to
     * fill the table's pane, so no table sits in a strip of empty space. A
     * table that needs a different mode sets it after calling this.
     */
    public static void setWidths(JTable t, int... widths) {
        t.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        java.awt.FontMetrics fm = t.getTableHeader().getFontMetrics(t.getTableHeader().getFont());
        for (int i = 0; i < widths.length && i < t.getColumnCount(); i++) {
            javax.swing.table.TableColumn c = t.getColumnModel().getColumn(i);
            int headerMin = fm.stringWidth(String.valueOf(c.getHeaderValue())) + 18;
            c.setPreferredWidth(Math.max(widths[i], headerMin));
            c.setMinWidth(Math.max(Math.max(24, widths[i] / 3), headerMin));
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
        b.setBackground(Theme.SURFACE);
        b.setOpaque(true);
        b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        b.setBorder(new opentrap.ui.theme.RoundedBorder(GRID, null, Theme.RADIUS, new Insets(5, 8, 5, 8)));
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
        int rows = 0;
        for (int i = 0; i + 1 < labelThenComponent.length; i += 2, rows++) {
            Object l = labelThenComponent[i];
            c.gridy = rows;
            c.insets = new Insets(3, 0, 3, 10);
            c.gridx = 0; c.weightx = 0;
            c.fill = GridBagConstraints.NONE;
            if (l instanceof Component lc) {
                // A control standing in for the label (a radio button, say)
                // keeps its own text left-aligned, as a row of options reads.
                c.anchor = GridBagConstraints.LINE_START;
                p.add(lc, c);
            } else {
                c.anchor = GridBagConstraints.LINE_END;
                p.add(label(String.valueOf(l)), c);
            }

            // The field keeps its natural width and starts at the shared edge.
            c.insets = new Insets(3, 0, 3, 0);
            c.gridx = 1; c.weightx = 0;
            c.anchor = GridBagConstraints.LINE_START;
            c.fill = GridBagConstraints.NONE;
            p.add((Component) labelThenComponent[i + 1], c);
        }
        // Spare width goes to a trailing column, so the two columns stay packed
        // to the left instead of drifting towards the centre of a wide panel.
        c.gridx = 2; c.gridy = 0; c.gridheight = Math.max(1, rows);
        c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        p.add(Box.createHorizontalGlue(), c);
        return p;
    }

    /**
     * Assemble a filter bar: the clickable filter button stretched across, then
     * the quick-search label and field on the right, all at one uniform height.
     * Used by the proxy history and the site map so both bars look identical.
     */
    public static JPanel filterBar(JButton filterButton, JComponent... right) {
        JPanel bar = new JPanel(new GridBagLayout());
        bar.setOpaque(false);
        bar.setBorder(pad(4, 4, 4, 4));

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

    /**
     * Boolean cells: a tick when true and nothing when false, as the reference
     * draws Params / Edited / TLS; an editable cell keeps its check box.
     */
    public static final class TickRenderer extends javax.swing.table.DefaultTableCellRenderer {
        private final javax.swing.table.TableCellRenderer box = new JTable().getDefaultRenderer(Boolean.class);
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            if (t.isCellEditable(r, c)) {
                Component cb = box.getTableCellRendererComponent(t, v, sel, f, r, c);
                if (cb instanceof JComponent jc) jc.setOpaque(true);
                return cb;
            }
            super.getTableCellRendererComponent(t, "", sel, f, r, c);
            setIcon(Boolean.TRUE.equals(v) ? Glyphs.tick(14, Theme.TEXT) : null);
            setHorizontalAlignment(CENTER);
            return this;
        }
    }

    /** A small funnel glyph for the filter bar, drawn so it needs no resource. */
    private static Icon funnelIcon() {
        BufferedImage img = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.ICON);
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
