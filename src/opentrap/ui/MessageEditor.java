package opentrap.ui;

import opentrap.core.*;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The shared request/response viewer and editor: a
 * Pretty (syntax-highlighted) / Raw / Hex (+ Render) tab set, an Inspector panel
 * docked on the right with collapsible parameter and header sections, and a
 * search bar along the bottom.
 *
 * <p>Views are gated by a bitmask: 1 for requests, 2 for responses. Pretty is a
 * coloured, byte-exact view of the same bytes as Raw (no reformatting), so edits
 * in either map one-to-one onto the message.
 */
public class MessageEditor extends JPanel {

    public static final int REQUEST  = 1;
    public static final int RESPONSE = 2;

    public interface ActionProvider { void contribute(JPopupMenu menu); }

    // Syntax colours for request and response highlighting.
    private static final Color C_HEADER_NAME = Theme.CODE_NAME;
    private static final Color C_STARTLINE   = Theme.CODE_START;
    private static final Color C_STRING      = Theme.CODE_STRING;
    private static final Color C_NUMBER      = Theme.CODE_NUMBER;
    private static final Color C_TAG         = Theme.CODE_NAME;

    private final int flags;
    private final boolean editable;
    private final SuiteContext ctx;

    private HttpMessage message = new HttpMessage(new byte[0]);
    private HttpService service;
    private HttpMessage relatedRequest;

    /**
     * Width held clear at the right of the tab row for the toolbar marks that
     * share that line. The tab row is narrowed by an empty border and the
     * content area is widened back by the same amount, so the pages still fill
     * the editor while the tabs stop short of the marks.
     */
    private int tabRowReserve;

    /**
     * Narrows the tab row by {@code reserve} and widens the content area back
     * by the same amount, so the pages still fill the editor while the tabs
     * stop short of the marks that share their line. The reserve is adjusted in
     * place rather than by reinstalling the UI: replacing a pane's UI during
     * layout churns listeners and is what the resize handler used to do.
     */
    private static final class ReserveTabUI extends opentrap.ui.theme.FlatTabbedPaneUI {
        private int reserve;

        @Override protected void installDefaults() {
            super.installDefaults();
            apply();
        }

        void setReserve(int r) {
            if (r == reserve) return;
            reserve = r;
            apply();
            if (tabPane != null) { tabPane.revalidate(); tabPane.repaint(); }
        }

        private void apply() {
            if (contentBorderInsets == null) return;
            contentBorderInsets = new java.awt.Insets(contentBorderInsets.top, contentBorderInsets.left,
                    contentBorderInsets.bottom, -reserve);
        }
    }

    private final JTabbedPane views = new JTabbedPane() {
        @Override public void updateUI() { setUI(new ReserveTabUI()); }
    };
    // A pretty pane that does not word-wrap: very long unbreakable lines (a
    // base64 VIEWSTATE, a JWT) otherwise render as overlapping, garbled glyphs.
    // Instead such lines extend and the pane scrolls horizontally.
    private final JTextPane prettyPane = new JTextPane() {
        @Override public boolean getScrollableTracksViewportWidth() {
            Component parent = getParent();
            return parent == null || getUI().getPreferredSize(this).width <= parent.getSize().width;
        }
    };
    private final JTextArea rawArea = new JTextArea();
    private final HexView hexView;
    private final JEditorPane renderPane = new JEditorPane();

    private final JTextField searchField = Ui.field("", 18);
    private final JLabel matchLabel = Ui.label("0 highlights");
    private final JLabel selectionLabel = Ui.label("");

    private final Inspector inspector;
    private final JPanel flyout = new JPanel(new BorderLayout());
    private final JTextArea notesArea = new JTextArea();
    private VerticalTab inspectorBtn, notesBtn, actionsBtn;
    private final CustomActionsPanel customActions = new CustomActionsPanel();

    private final List<ActionProvider> providers = new ArrayList<>();
    private boolean updating;
    private Runnable changeListener;
    // A line above the views for something the bytes alone would not say, such
    // as a response whose body was not kept in the project.
    private final JLabel noteLabel = new JLabel();

    public MessageEditor(SuiteContext ctx, int flags, boolean editable) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.flags = flags;
        this.editable = editable;
        this.hexView = new HexView(editable);
        this.inspector = new Inspector();

        prettyPane.setFont(Ui.MONO);
        prettyPane.setEditable(editable);

        rawArea.setFont(Ui.MONO);
        rawArea.setEditable(editable);
        rawArea.setLineWrap(true);
        rawArea.setWrapStyleWord(false);
        rawArea.setTabSize(4);

        renderPane.setEditable(false);
        renderPane.setEditorKit(Theme.htmlKit());   // page colours from the theme, not black on white

        views.setFont(Ui.PLAIN);
        // Never wrap onto a second row: the toolbar marks share the tab line, so
        // a narrow editor scrolls its tabs rather than stacking them.
        views.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        JScrollPane prettyScroll = Ui.scroll(prettyPane);
        prettyScroll.setRowHeaderView(new LineGutter(prettyPane));
        JScrollPane rawScroll = Ui.scroll(rawArea);
        rawScroll.setRowHeaderView(new LineGutter(rawArea));
        views.addTab("Pretty", prettyScroll);
        views.addTab("Raw", rawScroll);
        views.addTab("Hex", hexView);
        if ((flags & RESPONSE) != 0) views.addTab("Render", Ui.scroll(renderPane));
        views.addChangeListener(e -> onViewChanged());

        noteLabel.setFont(Ui.PLAIN);
        noteLabel.setForeground(Theme.MUTED);
        noteLabel.setBorder(Ui.pad(3, 8, 3, 8));
        noteLabel.setVisible(false);
        // The icons sit on the tab row's right end, like the reference, so the
        // tabs and the icons share one line.
        final JComponent icons = buildToolbar();
        final JLayeredPane layer = new JLayeredPane();
        layer.setLayout(null);
        layer.add(views, JLayeredPane.DEFAULT_LAYER);
        layer.add(icons, JLayeredPane.PALETTE_LAYER);
        layer.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                int w = layer.getWidth(), h = layer.getHeight();
                views.setBounds(0, 0, w, h);
                views.doLayout();
                Dimension d = icons.getPreferredSize();
                int reserve = d.width + 12;
                if (reserve != tabRowReserve) {
                    tabRowReserve = reserve;
                    views.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, reserve));
                    if (views.getUI() instanceof ReserveTabUI u) u.setReserve(reserve);
                    views.setBounds(0, 0, w, h);
                    views.doLayout();
                }
                int rowH = views.getTabCount() > 0 ? views.getUI().getTabBounds(views, 0).height : d.height;
                icons.setBounds(w - d.width - 6, Math.max(0, (rowH - d.height) / 2), d.width, d.height);
            }
        });
        JComponent editorSide = new JPanel(new BorderLayout());
        editorSide.add(noteLabel, BorderLayout.NORTH);
        editorSide.add(layer, BorderLayout.CENTER);

        add(editorSide, BorderLayout.CENTER);
        // One Inspector per request/response pair, on the far-right (response)
        // editor. Request-only editors (the left pane) show no strip.
        if ((flags & RESPONSE) != 0) add(buildRightDock(), BorderLayout.EAST);
        add(buildFooter(), BorderLayout.SOUTH);

        DocumentListener edit = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { onEdit(); }
            public void removeUpdate(DocumentEvent e)  { onEdit(); }
            // changedUpdate fires for attribute (colour) changes; ignore it so
            // re-highlighting does not recurse or mutate during a notification.
            public void changedUpdate(DocumentEvent e) {}
        };
        prettyPane.getDocument().addDocumentListener(edit);
        rawArea.getDocument().addDocumentListener(edit);
        rawArea.addCaretListener(e -> updateSelectionLabel());
        prettyPane.addCaretListener(e -> updateSelectionLabel());

        installPopup(prettyPane);
        installPopup(rawArea);
    }

    private JToggleButton wrapToggle;
    private boolean showNewlines;

    /** The view icons: hide headers, word wrap (lit when on), show line endings, and the actions menu. */
    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        bar.setOpaque(false);
        JButton hide = Glyphs.button(eyeOffIcon(), "Hide headers", () -> {
            java.awt.Window w = SwingUtilities.getWindowAncestor(this);
            SettingsDialog.open(w, ctx, "User interface > Message editor");
        });
        wrapToggle = new JToggleButton(Glyphs.wrap(16, Theme.ICON), true);
        wrapToggle.setSelectedIcon(Glyphs.wrap(16, Color.WHITE));
        wrapToggle.setToolTipText("Word wrap");
        wrapToggle.setFocusable(false);
        wrapToggle.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
        wrapToggle.setContentAreaFilled(false);
        wrapToggle.setOpaque(true);
        wrapToggle.setBackground(Theme.CONTROL);
        wrapToggle.addActionListener(e -> {
            boolean on = wrapToggle.isSelected();
            rawArea.setLineWrap(on);
            wrapToggle.setBackground(on ? Theme.CONTROL : Theme.BG);
        });
        rawArea.setLineWrap(true);
        rawArea.setWrapStyleWord(false);
        JButton nl = Glyphs.button(textGlyph("\\n", false, false), "Show line endings", () -> { showNewlines = !showNewlines; refreshViews(); });
        JButton menu = Glyphs.button(Glyphs.hamburger(16, Theme.ICON), "Actions", null);
        menu.addActionListener(e -> buildMenu().show(menu, 0, menu.getHeight()));
        bar.add(hide);
        bar.add(wrapToggle);
        bar.add(nl);
        bar.add(menu);
        return bar;
    }

    private static Icon eyeOffIcon() {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.ICON);
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new java.awt.geom.Arc2D.Double(1, 2, 14, 12, 20, 140, java.awt.geom.Arc2D.OPEN));
        g.draw(new java.awt.geom.Arc2D.Double(1, 2, 14, 12, 200, 140, java.awt.geom.Arc2D.OPEN));
        g.draw(new java.awt.geom.Ellipse2D.Double(5.5, 5.5, 5, 5));
        g.drawLine(2, 14, 14, 2);
        g.dispose();
        return new ImageIcon(img);
    }

    /** The right edge: a vertical Inspector / Notes strip whose buttons flyout a panel. */
    private JComponent buildRightDock() {
        flyout.setVisible(false);
        flyout.setPreferredSize(new Dimension(280, 10));
        notesArea.setFont(Ui.MONO);

        inspectorBtn = new VerticalTab("Inspector");
        notesBtn = new VerticalTab("Notes");
        actionsBtn = new VerticalTab("Custom actions");
        inspectorBtn.addActionListener(e -> {
            if (inspectorBtn.isSelected()) { notesBtn.setSelected(false); actionsBtn.setSelected(false); show("Inspector", inspector); }
            else hideFlyout();
        });
        notesBtn.addActionListener(e -> {
            if (notesBtn.isSelected()) { inspectorBtn.setSelected(false); actionsBtn.setSelected(false); show("Notes", notesPanel()); }
            else hideFlyout();
        });
        actionsBtn.addActionListener(e -> {
            if (actionsBtn.isSelected()) { inspectorBtn.setSelected(false); notesBtn.setSelected(false); show("Custom actions", customActions); }
            else hideFlyout();
        });

        JPanel strip = new JPanel();
        strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
        strip.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Ui.GRID));
        strip.add(inspectorBtn);
        strip.add(notesBtn);
        strip.add(actionsBtn);
        JPanel stripWrap = new JPanel(new BorderLayout());
        stripWrap.add(strip, BorderLayout.NORTH);
        JButton stripGear = Glyphs.button(Glyphs.gear(), "Side panel settings", () -> {
            java.awt.Window w = SwingUtilities.getWindowAncestor(this);
            SettingsDialog.open(w, ctx, "User interface > Side panel");
        });
        JPanel gearWrap = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
        gearWrap.setOpaque(false);
        gearWrap.add(stripGear);
        stripWrap.add(gearWrap, BorderLayout.SOUTH);

        JPanel dock = new JPanel(new BorderLayout());
        dock.add(flyout, BorderLayout.CENTER);
        dock.add(stripWrap, BorderLayout.EAST);
        return dock;
    }

    private void show(String title, JComponent content) {
        flyout.removeAll();
        flyout.setBackground(Theme.BG);
        JPanel head = new JPanel(new BorderLayout());
        head.setBackground(Theme.BG);
        head.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 1, 1, 0, Ui.GRID), Ui.pad(5, 8, 5, 6)));
        JLabel titleLabel = Ui.bold(title);
        titleLabel.setForeground(Theme.TEXT);
        head.add(titleLabel, BorderLayout.WEST);
        JButton close = Glyphs.button(Glyphs.cross(14, Theme.ICON), "Close this panel", null);
        close.addActionListener(e -> { inspectorBtn.setSelected(false); notesBtn.setSelected(false); actionsBtn.setSelected(false); hideFlyout(); });
        JPanel headRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        headRight.setOpaque(false);
        headRight.add(Glyphs.button(Glyphs.chevron(14, Theme.ICON, 0), "Narrow this panel",
                () -> { flyout.setPreferredSize(new Dimension(280, 10)); revalidate(); }));
        headRight.add(Glyphs.button(Glyphs.chevron(14, Theme.ICON, 3), "Widen this panel",
                () -> { flyout.setPreferredSize(new Dimension(420, 10)); revalidate(); }));
        if (content == inspector) {
            headRight.add(Glyphs.button(Glyphs.chevron(14, Theme.MUTED, 1), "Expand all sections", () -> inspector.expandAll(true)));
            headRight.add(Glyphs.button(Glyphs.chevron(14, Theme.MUTED, 2), "Collapse all sections", () -> inspector.expandAll(false)));
        }
        headRight.add(close);
        head.add(headRight, BorderLayout.EAST);
        flyout.add(head, BorderLayout.NORTH);
        flyout.add(content, BorderLayout.CENTER);
        flyout.setVisible(true);
        if (content == inspector) inspector.rebuild();
        revalidate();
        repaint();
    }

    /** The Notes flyout: a formatting toolbar over the plain-text notes. */
    private JComponent notesPanel() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        bar.setOpaque(false);
        JComboBox<String> size = Ui.combo(new String[]{"Aa", "Small", "Normal", "Large", "Heading"});
        size.setToolTipText("text size");
        size.addActionListener(e -> { String v = String.valueOf(size.getSelectedItem()); if ("Heading".equals(v)) wrapSelection("# ", ""); });
        bar.add(Glyphs.button(Glyphs.pencil(14, Theme.ICON), "Text style", null));
        bar.add(size);
        bar.add(Glyphs.button(textGlyph("B", true, false), "Bold", () -> wrapSelection("**", "**")));
        bar.add(Glyphs.button(textGlyph("I", false, true), "Italic", () -> wrapSelection("_", "_")));
        bar.add(Glyphs.button(textGlyph("S", false, false), "Strikethrough", () -> wrapSelection("~~", "~~")));
        bar.add(Glyphs.button(textGlyph("\u2022", false, false), "Bulleted list", () -> prefixLines("- ")));
        bar.add(Glyphs.button(textGlyph("1.", false, false), "Numbered list", () -> prefixLines("1. ")));
        bar.add(Glyphs.button(Glyphs.export(14, Theme.ICON), "Insert link", () -> {
            String url = JOptionPane.showInputDialog(this, "Link URL:");
            if (url != null && !url.isBlank()) notesArea.insert("[" + (notesArea.getSelectedText() == null ? url : notesArea.getSelectedText()) + "](" + url + ")", notesArea.getCaretPosition());
        }));
        bar.add(Glyphs.button(Glyphs.export(14, Theme.ICON), "Open notes in a window", () -> {
            JFrame f = new JFrame("Notes");
            f.setContentPane(Ui.scroll(notesArea));
            f.setSize(600, 400);
            f.setLocationRelativeTo(this);
            f.setVisible(true);
        }));
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(bar, BorderLayout.NORTH);
        p.add(Ui.scroll(notesArea), BorderLayout.CENTER);
        return p;
    }

    private static Icon textGlyph(String text, boolean bold, boolean italic) {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(18, 18, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("Dialog", (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0), 13));
        g.setColor(Theme.ICON);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, (18 - fm.stringWidth(text)) / 2f, 9 + (fm.getAscent() - fm.getDescent()) / 2f);
        if ("S".equals(text)) g.drawLine(3, 9, 15, 9);
        g.dispose();
        return new ImageIcon(img);
    }

    private void wrapSelection(String before, String after) {
        int a = notesArea.getSelectionStart(), b = notesArea.getSelectionEnd();
        String sel = a == b ? "text" : notesArea.getSelectedText();
        notesArea.replaceRange(before + sel + after, a, b);
    }

    private void prefixLines(String prefix) {
        try {
            int from = notesArea.getLineOfOffset(notesArea.getSelectionStart()), to = notesArea.getLineOfOffset(notesArea.getSelectionEnd());
            for (int l = to; l >= from; l--) notesArea.insert(prefix, notesArea.getLineStartOffset(l));
        } catch (Exception ignore) { }
    }

    private void hideFlyout() {
        flyout.setVisible(false);
        revalidate();
        repaint();
    }

    /** A vertical (rotated) tab button for the Inspector/Notes strip. */
    private JPanel buildFooter() {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID), Ui.pad(4, 4, 4, 6)));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        left.setOpaque(false);
        left.add(Glyphs.button(Glyphs.help(), "About the message editor", () -> JOptionPane.showMessageDialog(this,
                "Pretty, Raw and Hex show the same message; edit in any of them.\nSearch finds text in the shown view; the arrows step through matches.",
                "Message editor", JOptionPane.INFORMATION_MESSAGE)));
        left.add(Glyphs.button(Glyphs.gear(), "Message editor settings", () -> {
            java.awt.Window w = SwingUtilities.getWindowAncestor(this);
            SettingsDialog.open(w, ctx, "User interface > Message editor");
        }));
        JButton prev = Glyphs.button(Glyphs.arrow(16, Theme.ICON, 3), "Previous match", () -> highlightMatches(-1));
        JButton next = Glyphs.button(Glyphs.arrow(16, Theme.ICON, 0), "Next match", () -> highlightMatches(1));
        left.add(prev);
        left.add(next);
        p.add(left, BorderLayout.WEST);
        searchField.setToolTipText("search this message");
        searchField.putClientProperty("JTextField.placeholderText", "Search");
        p.add(searchField, BorderLayout.CENTER);
        matchLabel.setText("0 highlights");
        matchLabel.setForeground(Theme.MUTED);
        selectionLabel.setForeground(Theme.MUTED);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        right.setOpaque(false);
        right.add(selectionLabel);
        right.add(matchLabel);
        p.add(right, BorderLayout.EAST);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { highlightMatches(0); }
            public void removeUpdate(DocumentEvent e)  { highlightMatches(0); }
            public void changedUpdate(DocumentEvent e) { highlightMatches(0); }
        });
        return p;
    }

    // ---------------- content ----------------

    public void setMessage(HttpService svc, HttpMessage m) { setMessage(svc, m, null); }

    public void setMessage(HttpService svc, HttpMessage m, HttpMessage related) {
        this.service = svc;
        this.message = m == null ? new HttpMessage(new byte[0]) : m;
        this.relatedRequest = related;
        loadActiveFromMessage();
        refreshViews();
    }

    /** Show a history entry's request: a copy of it, so edits never touch the record. */
    public void showRequest(LogEntry e) {
        setNote(null);
        setMessage(e.service(), e.request() == null ? new HttpMessage(new byte[0]) : e.request().copy());
    }

    /**
     * Show a history entry's response: a copy, with the entry's request as the
     * related message, and a note when the project holds less than the whole body.
     */
    public void showResponse(LogEntry e) {
        setMessage(e.service(), e.response() == null ? new HttpMessage(new byte[0]) : e.response().copy(), e.request());
        setNote(e.bodyNote());
    }

    /** A line shown above the views, or hidden when null. */
    public void setNote(String text) {
        noteLabel.setText(text == null ? "" : text);
        noteLabel.setVisible(text != null && !text.isEmpty());
        noteLabel.getParent().revalidate();
    }

    public HttpMessage message()  { return message; }
    public HttpService service()  { return service; }
    public byte[] bytes()         { return message.raw(); }

    public void highlight(String term) {
        searchField.setText(term == null ? "" : term);
        if (term == null || term.isEmpty()) return;
        int pos = activeText().getText().toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT));
        if (pos >= 0) try { activeText().setCaretPosition(pos); } catch (RuntimeException ignored) {}
    }

    public void setChangeListener(Runnable r) { this.changeListener = r; }
    public void addActionProvider(ActionProvider p) { providers.add(p); }

    /** The currently active text editor (Pretty or Raw); defaults to Pretty. */
    private JTextComponent activeText() {
        return views.getSelectedComponent() == prettyScroll() ? prettyPane : rawArea;
    }

    private Component prettyScroll() { return views.getComponentAt(0); }

    private void loadActiveFromMessage() {
        updating = true;
        String text = Bytes.str(message.raw());
        prettyPane.setText(text);
        applySyntax();
        rawArea.setText(text);
        prettyPane.setCaretPosition(0);
        rawArea.setCaretPosition(0);
        updating = false;
    }

    private void onEdit() {
        if (updating) return;
        message.setRaw(Bytes.bytes(activeText().getText()));
        // Re-colour after the current document notification completes, to avoid
        // "mutate in notification" from changing attributes mid-edit.
        if (activeText() == prettyPane) SwingUtilities.invokeLater(this::reSyntax);
        inspector.rebuild();
        updateSelectionLabel();
        if (changeListener != null) changeListener.run();
    }

    private void reSyntax() {
        updating = true;
        try { applySyntax(); } finally { updating = false; }
    }

    private void onViewChanged() {
        updating = true;
        String text = Bytes.str(message.raw());
        JTextComponent a = activeText();
        if (a.getText().equals(text)) { updating = false; }
        else { a.setText(text); a.setCaretPosition(0); updating = false; }
        if (a == prettyPane) applySyntax();
        updating = false;
        refreshViews();
    }

    private void refreshViews() {
        hexView.setData(message.raw());
        inspector.rebuild();
        if ((flags & RESPONSE) != 0 && views.getSelectedIndex() == views.indexOfTab("Render")) renderHtml();
        updateSelectionLabel();
    }

    private void renderHtml() {
        try {
            String body = message.bodyText();
            if (body == null) body = "";
            body = body.replaceAll("(?is)<script.*?</script>", "")
                       .replaceAll("(?i)<link[^>]*>", "")
                       .replaceAll("(?i)src\\s*=\\s*\"https?://[^\"]*\"", "");
            // Reset the document before each render. Reusing the previous
            // HTMLDocument makes JEditorPane keep showing the earlier page (or
            // nothing at all) on the next setText, which is why most responses
            // came up blank. A fresh document forces a clean re-parse every time.
            renderPane.setDocument(renderPane.getEditorKit().createDefaultDocument());
            if (body.isBlank()) {
                renderPane.setText("<html><body><i>No HTML body to render.</i></body></html>");
            } else {
                renderPane.setText(body);
            }
            renderPane.setCaretPosition(0);
        } catch (Exception e) {
            renderPane.setText("<html><body><i>Could not render this response.</i></body></html>");
        }
    }

    private void updateSelectionLabel() {
        JTextComponent a = activeText();
        int n = Math.abs(a.getSelectionEnd() - a.getSelectionStart());
        selectionLabel.setText(n > 0 ? "Selection: " + n + " (0x" + Integer.toHexString(n) + ")" : "");
    }

    // ---------------- syntax highlighting ----------------

    /** Colour the Pretty view's headers and body without changing any bytes. */
    private void applySyntax() {
        StyledDocument doc = prettyPane.getStyledDocument();
        // Use the document's own text, not getText(): getText() returns the
        // original CRLF string while the document stores LF-normalised content,
        // so their offsets differ and highlighting would drift down the page.
        String text;
        try { text = doc.getText(0, doc.getLength()); }
        catch (javax.swing.text.BadLocationException e) { return; }
        SimpleAttributeSet plain = new SimpleAttributeSet();
        StyleConstants.setForeground(plain, Theme.TEXT);
        doc.setCharacterAttributes(0, text.length(), plain, true);

        int bodyStart = text.indexOf("\r\n\r\n");
        int sep = 4;
        if (bodyStart < 0) { bodyStart = text.indexOf("\n\n"); sep = 2; }
        int headerEnd = bodyStart < 0 ? text.length() : bodyStart;

        // Header lines.
        int pos = 0;
        boolean first = true;
        while (pos < headerEnd) {
            int eol = text.indexOf('\n', pos);
            if (eol < 0 || eol > headerEnd) eol = headerEnd;
            int lineEnd = eol;
            if (first) {
                style(doc, pos, lineEnd - pos, C_STARTLINE, false);
                first = false;
            } else {
                int colon = text.indexOf(':', pos);
                if (colon > pos && colon < lineEnd) style(doc, pos, colon - pos, C_HEADER_NAME, true);
            }
            pos = eol + 1;
        }

        // Body. Colouring is best-effort decoration; colourBody contains any
        // failure so the event thread always survives it.
        if (bodyStart >= 0) {
            int bodyFrom = bodyStart + sep;
            String body = text.substring(Math.min(bodyFrom, text.length()));
            colourBody(doc, bodyFrom, body, message.contentType());
        }
    }

    /** A JSON syntax colourer, substitutable in tests. */
    interface JsonColourer { void colour(StyledDocument doc, int base, String body); }

    /**
     * Colour a body best-effort. Colouring is decoration, so nothing it does
     * may take the app down: a StackOverflowError unwinds to this frame,
     * where catching it is safe, and the body simply shows uncoloured. The
     * trace is omitted on purpose - it is a thousand Pattern frames with the
     * caller cut off, as the field logs of the original crash showed.
     */
    static void colourBody(StyledDocument doc, int base, String body, String ct) {
        colourBody(doc, base, body, ct, MessageEditor::colourJson);
    }

    static void colourBody(StyledDocument doc, int base, String body, String ct, JsonColourer json) {
        try {
            if (ct.contains("json") || looksJson(body)) json.colour(doc, base, body);
            else if (ct.contains("xml") || ct.contains("html") || body.trim().startsWith("<")) colourMarkup(doc, base, body);
        } catch (StackOverflowError e) {
            Debug.error("syntax colouring gave up; body shown uncoloured: " + e, null);
        }
    }

    private static boolean looksJson(String s) { String t = s.trim(); return t.startsWith("{") || t.startsWith("["); }

    static void colourJson(StyledDocument doc, int base, String body) {
        // Quoted strings are found by a linear walk, not a regex. Java's
        // Pattern nests one stack frame per character for a group loop such
        // as "(\\.|[^"\\])*", so one large string value (RUM payloads carry
        // 100 KB+ values) overflowed the AWT event queue's stack and killed
        // the app the moment such a message was rendered. The walk cannot
        // recurse, and pairs spans off exactly as the old regex did: an
        // opening quote closes at the next quote a backslash has not escaped.
        for (Span span : jsonStringSpans(body)) {
            int after = span.end();
            while (after < body.length() && Character.isWhitespace(body.charAt(after))) after++;
            boolean key = after < body.length() && body.charAt(after) == ':';
            style(doc, base + span.start(), span.end() - span.start(), key ? Theme.CODE_KEY : C_STRING, key);
        }
        Matcher num = Pattern.compile("(?<![\\w\"])-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?").matcher(body);
        while (num.find()) style(doc, base + num.start(), num.end() - num.start(), C_NUMBER, false);
    }

    /**
     * A quoted string in a JSON body: {@link #start} inclusive, {@link #end}
     * exclusive (one past the closing quote) - the same convention a Matcher
     * reports for a match that includes both quotes.
     */
    record Span(int start, int end) {}

    /**
     * The quoted-string spans of a JSON body. An unterminated final string
     * yields nothing and nothing beyond it can pair off, matching the
     * previous regex's behaviour.
     */
    static List<Span> jsonStringSpans(String body) {
        ArrayList<Span> out = new ArrayList<>();
        int n = body.length();
        int i = 0;
        while (i < n) {
            if (body.charAt(i) != '"') { i++; continue; }
            int start = i;
            i++;
            boolean closed = false;
            while (i < n) {
                char c = body.charAt(i);
                if (c == '\\') { i += 2; continue; }
                i++;
                if (c == '"') { closed = true; break; }
            }
            if (!closed) break;
            out.add(new Span(start, i));
        }
        return out;
    }

    static void colourMarkup(StyledDocument doc, int base, String body) {
        Matcher tag = Pattern.compile("</?[A-Za-z][\\w:-]*").matcher(body);
        while (tag.find()) style(doc, base + tag.start(), tag.end() - tag.start(), C_TAG, false);
        Matcher attr = Pattern.compile("\"[^\"]*\"").matcher(body);
        while (attr.find()) style(doc, base + attr.start(), attr.end() - attr.start(), C_STRING, false);
    }

    private static void style(StyledDocument doc, int off, int len, Color c, boolean bold) {
        if (off < 0 || len <= 0 || off + len > doc.getLength()) return;
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setForeground(a, c);
        StyleConstants.setBold(a, bold);
        doc.setCharacterAttributes(off, len, a, false);
    }

    // ---------------- search ----------------

    private int lastMatchPos = -1;

    private void highlightMatches(int direction) {
        JTextComponent a = activeText();
        Highlighter h = a.getHighlighter();
        h.removeAllHighlights();
        String term = searchField.getText();
        if (term.isEmpty()) { matchLabel.setText("0 highlights"); return; }

        String text = a.getText().toLowerCase(Locale.ROOT);
        String want = term.toLowerCase(Locale.ROOT);
        int count = 0;
        List<Integer> positions = new ArrayList<>();
        int i = text.indexOf(want);
        while (i >= 0) {
            positions.add(i);
            try {
                h.addHighlight(i, i + want.length(),
                        new DefaultHighlighter.DefaultHighlightPainter(Theme.HIGHLIGHT));
            } catch (BadLocationException ignored) {}
            count++;
            i = text.indexOf(want, i + 1);
        }
        matchLabel.setText(count + (count == 1 ? " highlight" : " highlights"));

        if (!positions.isEmpty() && direction != 0) {
            int idx = positions.indexOf(lastMatchPos);
            idx = (idx + direction + positions.size()) % positions.size();
            lastMatchPos = positions.get(idx);
            a.setCaretPosition(lastMatchPos);
            a.select(lastMatchPos, lastMatchPos + want.length());
            a.requestFocusInWindow();
        } else if (!positions.isEmpty()) {
            lastMatchPos = positions.get(0);
        }
    }

    // ---------------- inspector ----------------

    /** The right-hand Inspector: collapsible parameter and header sections. */
    private final class Inspector extends JPanel {
        private final JPanel stack = new JPanel();

        Inspector() {
            super(new BorderLayout());
            setBackground(Theme.BG);
            stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
            stack.setBackground(Theme.BG);
            JScrollPane sp = new JScrollPane(stack);
            sp.setBorder(null);
            sp.getViewport().setBackground(Theme.BG);
            sp.getVerticalScrollBar().setUnitIncrement(16);
            add(sp, BorderLayout.CENTER);
        }

        private final List<Runnable> toggles = new ArrayList<>();
        private final List<JComponent> bodies = new ArrayList<>();

        /** Open or close every section. */
        void expandAll(boolean open) {
            for (int i = 0; i < bodies.size(); i++) {
                if (bodies.get(i).isVisible() != open) toggles.get(i).run();
            }
        }

        void rebuild() {
            stack.removeAll();
            toggles.clear();
            bodies.clear();
            // One inspector for the pair: request sections (from the shown request
            // or its related request) then response sections.
            HttpMessage req = message.isRequest() ? message : relatedRequest;
            HttpMessage resp = message.isRequest() ? null : message;
            if (req != null) {
                addSection("Request attributes", attributeRows(req, true));
                addSection("Query parameters", rows(req, Param.Type.URL));
                addSection("Body parameters", rows(req, Param.Type.BODY, Param.Type.JSON, Param.Type.XML, Param.Type.MULTIPART));
                addSection("Cookies", rows(req, Param.Type.COOKIE));
                addSection("Request headers", headerRows(req));
            }
            if (resp != null) {
                addSection("Response attributes", attributeRows(resp, false));
                addSection("Response headers", headerRows(resp));
            }
            stack.revalidate();
            stack.repaint();
        }

        private List<String[]> attributeRows(HttpMessage m, boolean req) {
            List<String[]> out = new ArrayList<>();
            if (req) {
                out.add(new String[]{"Method", m.method()});
                out.add(new String[]{"Path", m.path()});
            } else {
                out.add(new String[]{"Status code", String.valueOf(m.statusCode())});
                out.add(new String[]{"Status", m.statusText()});
            }
            return out;
        }

        private void addSection(String titleText, List<String[]> data) {
            final JComponent body = buildTable(data, "Request attributes".equals(titleText));
            body.setVisible(false); // collapsed by default

            JLabel chevron = new JLabel("▾");
            chevron.setForeground(Theme.MUTED);
            JLabel title = new JLabel(titleText);
            title.setFont(Ui.PLAIN);
            title.setForeground(Theme.TEXT);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
            right.setOpaque(false);
            right.add(countPill(data.size()));
            right.add(chevron);

            JPanel header = new JPanel(new BorderLayout());
            header.setBackground(Theme.BG);
            header.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.GRID), Ui.pad(7, 10, 7, 6)));
            header.add(title, BorderLayout.WEST);
            header.add(right, BorderLayout.EAST);
            header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            Runnable toggle = () -> {
                boolean show = !body.isVisible();
                body.setVisible(show);
                chevron.setText(show ? "▴" : "▾");
                stack.revalidate();
            };
            toggles.add(toggle);
            bodies.add(body);
            header.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) { toggle.run(); }
            });

            header.setAlignmentX(LEFT_ALIGNMENT);
            body.setAlignmentX(LEFT_ALIGNMENT);
            header.setMaximumSize(new Dimension(Integer.MAX_VALUE, header.getPreferredSize().height));
            stack.add(header);
            stack.add(body);
        }

        /** A small rounded grey count badge. */
        private JComponent countPill(int n) {
            JLabel l = new JLabel(String.valueOf(n)) {
                @Override protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(Theme.SURFACE_2);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), getHeight(), getHeight());
                    g2.dispose();
                    super.paintComponent(g);
                }
            };
            l.setFont(Ui.PLAIN);
            l.setForeground(Theme.MUTED);
            l.setHorizontalAlignment(SwingConstants.CENTER);
            l.setBorder(BorderFactory.createEmptyBorder(1, 9, 1, 9));
            l.setOpaque(false);
            return l;
        }

        /**
         * A section body: for request attributes a protocol toggle first; then
         * the Name / Value table with a drill-in chevron per row and the column
         * menu in its header, a footer of delete / down / up / add marks, or
         * "It's empty in here" with Add when there is nothing to show.
         */
        private JComponent buildTable(List<String[]> data, boolean attributes) {
            JPanel col = new JPanel();
            col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
            col.setBackground(Theme.BG);
            col.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.GRID));
            if (attributes) {
                JPanel proto = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
                proto.setOpaque(false);
                proto.add(Ui.label("Protocol"));
                JToggleButton h1 = new JToggleButton("HTTP/1"), h2 = new JToggleButton("HTTP/2");
                h1.setFont(Ui.PLAIN); h2.setFont(Ui.PLAIN);
                h1.setFocusable(false); h2.setFocusable(false);
                ButtonGroup g = new ButtonGroup(); g.add(h1); g.add(h2);
                boolean http2 = message != null && message.startLine().contains("HTTP/2");
                (http2 ? h2 : h1).setSelected(true);
                proto.add(h1); proto.add(h2);
                proto.setAlignmentX(LEFT_ALIGNMENT);
                proto.setMaximumSize(new Dimension(Integer.MAX_VALUE, proto.getPreferredSize().height));
                col.add(proto);
            }
            if (data.isEmpty()) {
                JPanel empty = new JPanel();
                empty.setLayout(new BoxLayout(empty, BoxLayout.Y_AXIS));
                empty.setOpaque(false);
                empty.setBorder(Ui.pad(14, 0, 14, 0));
                JLabel l = Ui.label("It's empty in here");
                l.setForeground(Theme.MUTED);
                l.setAlignmentX(CENTER_ALIGNMENT);
                JButton add = Ui.button("Add");
                add.setEnabled(editable);
                add.setAlignmentX(CENTER_ALIGNMENT);
                add.addActionListener(e -> addRow());
                empty.add(l); empty.add(Box.createVerticalStrut(8)); empty.add(add);
                empty.setAlignmentX(LEFT_ALIGNMENT);
                empty.setMaximumSize(new Dimension(Integer.MAX_VALUE, empty.getPreferredSize().height));
                col.add(empty);
                return col;
            }
            AbstractTableModel m = new AbstractTableModel() {
                public int getRowCount() { return data.size(); }
                public int getColumnCount() { return 3; }
                public String getColumnName(int c) { return c == 0 ? "Name" : c == 1 ? "Value" : ""; }
                public Object getValueAt(int r, int c) { return c < 2 ? data.get(r)[c] : ">"; }
            };
            JTable t = new JTable(m);
            Ui.styleTable(t);
            t.setRowHeight(20);
            t.getColumnModel().getColumn(2).setMaxWidth(22);
            t.getColumnModel().getColumn(2).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int r, int c) {
                    super.getTableCellRendererComponent(tb, "", sel, f, r, c);
                    setIcon(Glyphs.chevron(12, Theme.MUTED, 0));
                    setHorizontalAlignment(CENTER);
                    return this;
                }
            });
            t.getColumnModel().getColumn(2).setHeaderRenderer(new javax.swing.table.DefaultTableCellRenderer() {
                @Override public Component getTableCellRendererComponent(JTable tb, Object v, boolean sel, boolean f, int r, int c) {
                    super.getTableCellRendererComponent(tb, "", sel, f, r, c);
                    setIcon(Glyphs.kebab(12, Theme.MUTED));
                    setHorizontalAlignment(CENTER);
                    return this;
                }
            });
            t.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    int r = t.rowAtPoint(e.getPoint());
                    if (r >= 0 && (e.getClickCount() == 2 || t.columnAtPoint(e.getPoint()) == 2)) drill(data.get(r));
                }
            });
            int h = data.size() * 20 + t.getTableHeader().getPreferredSize().height + 2;
            JScrollPane sp = new JScrollPane(t);
            sp.setBorder(null);
            sp.setPreferredSize(new Dimension(240, h));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            sp.setAlignmentX(LEFT_ALIGNMENT);
            col.add(sp);
            JPanel foot = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 2));
            foot.setOpaque(false);
            foot.add(Glyphs.button(Glyphs.trash(14, Theme.MUTED), "Delete the selected row", () -> { if (editable) { int r = t.getSelectedRow(); if (r >= 0) { data.remove(r); m.fireTableDataChanged(); } } }));
            foot.add(Glyphs.button(Glyphs.chevron(14, Theme.MUTED, 1), "Move down", () -> moveRow(t, m, data, 1)));
            foot.add(Glyphs.button(Glyphs.chevron(14, Theme.MUTED, 2), "Move up", () -> moveRow(t, m, data, -1)));
            foot.add(Glyphs.button(Glyphs.plus(14, Theme.MUTED), "Add a row", this::addRow));
            foot.setAlignmentX(LEFT_ALIGNMENT);
            foot.setMaximumSize(new Dimension(Integer.MAX_VALUE, foot.getPreferredSize().height));
            col.add(foot);
            return col;
        }

        private void moveRow(JTable t, AbstractTableModel m, List<String[]> data, int d) {
            int r = t.getSelectedRow(), to = r + d;
            if (r < 0 || to < 0 || to >= data.size()) return;
            data.add(to, data.remove(r));
            m.fireTableDataChanged();
            t.setRowSelectionInterval(to, to);
        }

        private void addRow() {
            if (!editable) { JOptionPane.showMessageDialog(this, "This message is read-only. Send it to Repeater to edit it.", "Inspector", JOptionPane.INFORMATION_MESSAGE); return; }
            JTextField name = Ui.field("", 18), value = Ui.field("", 24);
            JPanel f = Ui.form("Name:", name, "Value:", value);
            if (JOptionPane.showConfirmDialog(this, f, "Add", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION && !name.getText().isBlank()) {
                String line = name.getText().strip() + ": " + value.getText();
                rawArea.insert(line + "\n", Math.max(0, rawArea.getText().indexOf("\n") + 1));
                views.setSelectedIndex(1);
            }
        }

        /** Drill into one row: its decoded value, with the common decodings beneath. */
        private void drill(String[] row) {
            JPanel p = new JPanel(new BorderLayout(0, 6));
            p.setBorder(Ui.pad(8, 8, 8, 8));
            JTextArea v = new JTextArea(row[1], 6, 50);
            v.setFont(Ui.MONO);
            v.setLineWrap(true);
            String decoded;
            try { decoded = java.net.URLDecoder.decode(row[1], java.nio.charset.StandardCharsets.UTF_8); } catch (Exception e) { decoded = row[1]; }
            JTextArea d = new JTextArea(decoded, 6, 50);
            d.setFont(Ui.MONO);
            d.setLineWrap(true);
            d.setEditable(false);
            p.add(Ui.vbox(Ui.bold(row[0]), Ui.scroll(v), Ui.label("URL-decoded:"), Ui.scroll(d)), BorderLayout.CENTER);
            JOptionPane.showMessageDialog(this, p, row[0], JOptionPane.PLAIN_MESSAGE);
        }

        private List<String[]> rows(HttpMessage m, Param.Type... types) {
            List<String[]> out = new ArrayList<>();
            java.util.Set<Param.Type> want = new java.util.HashSet<>(java.util.Arrays.asList(types));
            for (Param p : m.parameters()) if (want.contains(p.type())) out.add(new String[]{p.name(), p.value()});
            return out;
        }

        private List<String[]> headerRows(HttpMessage m) {
            List<String[]> out = new ArrayList<>();
            for (String line : m.headerLines()) {
                int c = line.indexOf(':');
                if (c < 0) out.add(new String[]{line, ""});
                else out.add(new String[]{line.substring(0, c).trim(), line.substring(c + 1).trim()});
            }
            return out;
        }
    }

    // ---------------- context menu ----------------

    private void installPopup(JComponent c) {
        c.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybe(e); }
            @Override public void mouseReleased(MouseEvent e) { maybe(e); }
            private void maybe(MouseEvent e) {
                if (e.isPopupTrigger()) buildMenu().show(e.getComponent(), e.getX(), e.getY());
            }
        });
    }

    private boolean showsRequest(HttpMessage shown) {
        if ((flags & REQUEST) == 0) return false;
        if ((flags & RESPONSE) == 0) return true;
        return shown.isRequest();
    }

    /** Build the right-click action menu. */
    public JPopupMenu buildMenu() {
        JPopupMenu menu = new JPopupMenu();
        boolean haveRequest = (flags & REQUEST) != 0 || relatedRequest != null;
        boolean haveMessage = message != null && message.raw().length > 0;

        // "Send to <tool>" for the standard tools, with keyboard shortcuts.
        boolean anySend = false;
        for (String tool : ctx.sinkNames()) {
            if (Engagement.isEngagement(tool)) continue; // engagement tools go in their submenu
            final String t = tool;
            JMenuItem mi = sendItem(t);
            if (mi == null) continue;
            KeyStroke ks = shortcutFor(t);
            if (ks != null) mi.setAccelerator(ks);
            menu.add(mi);
            anySend = true;
        }
        if (anySend) menu.addSeparator();

        // Engagement tools submenu (CSRF PoC, content discovery, ...).
        List<String> eng = new ArrayList<>();
        for (String tool : ctx.sinkNames()) if (Engagement.isEngagement(tool)) eng.add(tool);
        if (!eng.isEmpty() && service != null && haveRequest) {
            JMenu sub = new JMenu("Engagement tools");
            sub.setFont(Ui.PLAIN);
            for (String t : eng) { JMenuItem mi = sendItem(t); if (mi != null) { mi.setText(Engagement.label(t)); sub.add(mi); } }
            menu.add(sub);
            menu.addSeparator();
        }

        if ((flags & REQUEST) != 0 && editable) {
            addItem(menu, "Change request method", null, e -> toggleMethod());
        }
        if ((flags & RESPONSE) != 0 && service != null && haveMessage) {
            addItem(menu, "Open response in browser", null, e -> openResponseInBrowser());
        }

        addItem(menu, "Copy", KeyStroke.getKeyStroke(KeyEvent.VK_C, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()), e -> {
            String sel = activeText().getSelectedText();
            String text = (sel == null || sel.isEmpty()) ? activeText().getText() : sel;
            clip(text);
        });
        if (service != null) {
            addItem(menu, "Copy URL", null, e -> clip(service.url() + (message.isRequest() ? message.requestTarget() : "")));
            if (message.isRequest()) addItem(menu, "Copy as curl command (bash)", null, e -> clip(asCurl()));
        }

        if (!providers.isEmpty()) {
            menu.addSeparator();
            for (ActionProvider p : providers) p.contribute(menu);
        }
        return menu;
    }

    private JMenuItem sendItem(String t) {
        boolean haveRequest = (flags & REQUEST) != 0 || relatedRequest != null;
        boolean haveMessage = message != null && message.raw().length > 0;
        if (ctx.sinkTakesSingleItem(t)) {
            if (!haveMessage) return null;
            JMenuItem mi = new JMenuItem("Send to " + t);
            mi.setFont(Ui.PLAIN);
            mi.addActionListener(e -> {
                HttpMessage own = message.copy();
                boolean asRequest = showsRequest(own);
                ctx.sendTo(t, service, asRequest ? own : null, asRequest ? null : own, null);
            });
            return mi;
        }
        if (service == null || !haveRequest) return null;
        JMenuItem mi = new JMenuItem("Send to " + t);
        mi.setFont(Ui.PLAIN);
        mi.addActionListener(e -> {
            HttpMessage own = message.copy();
            boolean asRequest = showsRequest(own);
            HttpMessage req = asRequest ? own : (relatedRequest == null ? null : relatedRequest.copy());
            HttpMessage resp = asRequest ? null : own;
            ctx.sendTo(t, service, req, resp, null);
        });
        return mi;
    }

    private static KeyStroke shortcutFor(String tool) {
        int mod = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        return switch (tool) {
            case "intruder"  -> KeyStroke.getKeyStroke(KeyEvent.VK_I, mod);
            case "repeater"  -> KeyStroke.getKeyStroke(KeyEvent.VK_R, mod);
            case "organizer" -> KeyStroke.getKeyStroke(KeyEvent.VK_O, mod);
            default -> null;
        };
    }

    private void openResponseInBrowser() {
        try {
            java.io.File f = java.io.File.createTempFile("opentrap-resp", ".html");
            try (java.io.PrintWriter w = new java.io.PrintWriter(f, java.nio.charset.StandardCharsets.ISO_8859_1)) {
                w.print(message.bodyText());
            }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
                Desktop.getDesktop().browse(f.toURI());
        } catch (Exception ignored) {}
    }

    private String asCurl() {
        StringBuilder b = new StringBuilder("curl -i -s -k ");
        b.append("-X ").append(message.method()).append(' ');
        for (String line : message.headerLines()) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            String n = line.substring(0, c).trim();
            if (n.equalsIgnoreCase("content-length")) continue;
            b.append("-H '").append(line.replace("'", "'\\''")).append("' ");
        }
        byte[] body = message.body();
        if (body.length > 0) b.append("--data-raw '").append(message.bodyText().replace("'", "'\\''")).append("' ");
        b.append('\'').append(service == null ? "" : service.url()).append(message.requestTarget()).append('\'');
        return b.toString();
    }

    private static void clip(String s) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(s), null);
    }

    private void addItem(JComponent menu, String label, KeyStroke ks, java.awt.event.ActionListener l) {
        JMenuItem mi = new JMenuItem(label);
        mi.setFont(Ui.PLAIN);
        if (ks != null) mi.setAccelerator(ks);
        mi.addActionListener(l);
        menu.add(mi);
    }

    /** Switch between GET and POST, moving parameters between URL and body. */
    private void toggleMethod() {
        String method = message.method();
        if ("GET".equalsIgnoreCase(method)) {
            String q = message.query();
            message.setMethod("POST");
            message.setRequestTarget(message.path());
            message.setBody(Bytes.bytes(q));
            message.setHeader("Content-Type", "application/x-www-form-urlencoded");
            message.updateContentLength();
        } else {
            String body = message.bodyText();
            message.setMethod("GET");
            String target = message.path();
            if (!body.isBlank()) target += "?" + body;
            message.setRequestTarget(target);
            message.setBody(new byte[0]);
            message.removeHeader("Content-Type");
            message.removeHeader("Content-Length");
        }
        loadActiveFromMessage();
        refreshViews();
        if (changeListener != null) changeListener.run();
    }

    public void selectView(String name) {
        int i = views.indexOfTab(name);
        if (i < 0) i = views.indexOfTab(capitalise(name));
        if (i >= 0) views.setSelectedIndex(i);
    }

    private static String capitalise(String s) {
        return s == null || s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** Names the engagement-tool sinks that belong in the Engagement tools submenu. */
    private static final class Engagement {
        static boolean isEngagement(String t) { return t.equals("CSRF PoC") || t.equals("content discovery"); }
        static String label(String t) {
            return switch (t) {
                case "CSRF PoC" -> "Generate CSRF PoC";
                case "content discovery" -> "Discover content";
                default -> t;
            };
        }
    }
}
