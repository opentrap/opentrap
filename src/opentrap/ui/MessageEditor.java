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

    private final JTabbedPane views = new JTabbedPane();
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
    private final JLabel matchLabel = Ui.label("0 matches");
    private final JLabel selectionLabel = Ui.label("");

    private final Inspector inspector;
    private final JPanel flyout = new JPanel(new BorderLayout());
    private final JTextArea notesArea = new JTextArea();
    private VerticalTab inspectorBtn, notesBtn;

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
        views.addTab("Pretty", Ui.scroll(prettyPane));
        views.addTab("Raw", Ui.scroll(rawArea));
        views.addTab("Hex", hexView);
        if ((flags & RESPONSE) != 0) views.addTab("Render", Ui.scroll(renderPane));
        views.addChangeListener(e -> onViewChanged());

        noteLabel.setFont(Ui.PLAIN);
        noteLabel.setForeground(Theme.MUTED);
        noteLabel.setBorder(Ui.pad(3, 8, 3, 8));
        noteLabel.setVisible(false);
        JPanel top = new JPanel(new BorderLayout());
        top.add(buildToolbar(), BorderLayout.NORTH);
        top.add(noteLabel, BorderLayout.SOUTH);
        JComponent editorSide = new JPanel(new BorderLayout());
        editorSide.add(top, BorderLayout.NORTH);
        editorSide.add(views, BorderLayout.CENTER);

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

    private JComponent buildToolbar() {
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        bar.setOpaque(false);
        JToggleButton wrap = new JToggleButton("↵");
        wrap.setFont(Ui.PLAIN);
        wrap.setFocusable(false);
        wrap.setMargin(new Insets(1, 5, 1, 5));
        wrap.setToolTipText("word-wrap");
        wrap.addActionListener(e -> rawArea.setLineWrap(wrap.isSelected()));
        JButton menu = new JButton("≡");
        menu.setFont(Ui.PLAIN);
        menu.setFocusable(false);
        menu.setMargin(new Insets(1, 5, 1, 5));
        menu.setToolTipText("actions");
        menu.addActionListener(e -> buildMenu().show(menu, 0, menu.getHeight()));
        bar.add(wrap);
        bar.add(menu);
        return bar;
    }

    /** The right edge: a vertical Inspector / Notes strip whose buttons flyout a panel. */
    private JComponent buildRightDock() {
        flyout.setVisible(false);
        flyout.setPreferredSize(new Dimension(280, 10));
        notesArea.setFont(Ui.MONO);

        inspectorBtn = new VerticalTab("Inspector");
        notesBtn = new VerticalTab("Notes");
        inspectorBtn.addActionListener(e -> {
            if (inspectorBtn.isSelected()) { notesBtn.setSelected(false); show("Inspector", inspector); }
            else hideFlyout();
        });
        notesBtn.addActionListener(e -> {
            if (notesBtn.isSelected()) { inspectorBtn.setSelected(false); show("Notes", Ui.scroll(notesArea)); }
            else hideFlyout();
        });

        JPanel strip = new JPanel();
        strip.setLayout(new BoxLayout(strip, BoxLayout.Y_AXIS));
        strip.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Ui.GRID));
        strip.add(inspectorBtn);
        strip.add(notesBtn);
        JPanel stripWrap = new JPanel(new BorderLayout());
        stripWrap.add(strip, BorderLayout.NORTH);

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
        JButton close = Ui.button("x", "close");
        close.setMargin(new Insets(0, 5, 0, 5));
        close.addActionListener(e -> { inspectorBtn.setSelected(false); notesBtn.setSelected(false); hideFlyout(); });
        head.add(close, BorderLayout.EAST);
        flyout.add(head, BorderLayout.NORTH);
        flyout.add(content, BorderLayout.CENTER);
        flyout.setVisible(true);
        if (content == inspector) inspector.rebuild();
        revalidate();
        repaint();
    }

    private void hideFlyout() {
        flyout.setVisible(false);
        revalidate();
        repaint();
    }

    /** A vertical (rotated) tab button for the Inspector/Notes strip. */
    private JPanel buildFooter() {
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(Ui.pad(3, 4, 3, 4));
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        JButton prev = Ui.button("<", "previous match");
        JButton next = Ui.button(">", "next match");
        prev.setMargin(new Insets(0, 6, 0, 6));
        next.setMargin(new Insets(0, 6, 0, 6));
        searchField.setToolTipText("search this message");
        left.add(Ui.label("Search:"));
        left.add(searchField);
        left.add(prev);
        left.add(next);
        left.add(matchLabel);
        p.add(left, BorderLayout.WEST);
        // A flexible centre gap keeps the match count and the length label apart,
        // and a left margin guarantees a gap even when the editor is narrow so
        // they never run together ("0 matcheslength: 344").
        p.add(Box.createHorizontalGlue(), BorderLayout.CENTER);
        selectionLabel.setBorder(Ui.pad(0, 14, 0, 2));
        p.add(selectionLabel, BorderLayout.EAST);

        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { highlightMatches(0); }
            public void removeUpdate(DocumentEvent e)  { highlightMatches(0); }
            public void changedUpdate(DocumentEvent e) { highlightMatches(0); }
        });
        next.addActionListener(e -> highlightMatches(1));
        prev.addActionListener(e -> highlightMatches(-1));
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
        if (n > 0) selectionLabel.setText("selection: " + n + " (0x" + Integer.toHexString(n) + ")");
        else selectionLabel.setText("length: " + message.length());
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
        if (term.isEmpty()) { matchLabel.setText("0 matches"); return; }

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
        matchLabel.setText(count + (count == 1 ? " match" : " matches"));

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

        void rebuild() {
            stack.removeAll();
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
            final JComponent body = buildTable(data);
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
            header.addMouseListener(new MouseAdapter() {
                @Override public void mouseClicked(MouseEvent e) {
                    boolean show = !body.isVisible();
                    body.setVisible(show);
                    chevron.setText(show ? "▴" : "▾");
                    stack.revalidate();
                }
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

        private JComponent buildTable(List<String[]> data) {
            AbstractTableModel m = new AbstractTableModel() {
                public int getRowCount() { return data.size(); }
                public int getColumnCount() { return 2; }
                public String getColumnName(int c) { return c == 0 ? "Name" : "Value"; }
                public Object getValueAt(int r, int c) { return data.get(r)[c]; }
            };
            JTable t = new JTable(m);
            Ui.styleTable(t);
            t.setRowHeight(20);
            int h = Math.max(data.size(), 1) * 20 + t.getTableHeader().getPreferredSize().height + 2;
            JScrollPane sp = new JScrollPane(t);
            sp.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Ui.GRID));
            sp.setPreferredSize(new Dimension(240, h));
            sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
            return sp;
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
            f.deleteOnExit();   // the browser keeps the file open; clean up at exit
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
