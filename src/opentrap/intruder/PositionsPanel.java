package opentrap.intruder;

import opentrap.core.Bytes;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.Param;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The positions tab: an editable view of the request with {@code §} payload
 * markers, buttons to manage those markers, and the attack-type combo.
 */
public class PositionsPanel extends JPanel {

    /** The payload position marker. */
    static final char MARK = '§';

    private static final String DEFAULT_REQUEST =
            "POST /example?p1=" + MARK + "p1val" + MARK + "&p2=" + MARK + "p2val" + MARK + " HTTP/1.0\r\n"
            + "Cookie: c=" + MARK + "cval" + MARK + "\r\n"
            + "Content-Length: 17\r\n"
            + "\r\n"
            + "p3=" + MARK + "p3val" + MARK + "&p4=" + MARK + "p4val" + MARK + "\r\n";

    private final JTextArea area = new JTextArea(DEFAULT_REQUEST);
    private final JComboBox<AttackType> attackCombo = Ui.combo(AttackType.ordered());
    private final JLabel highlightLabel = Ui.label("0 highlights");
    private final JLabel posLabel = Ui.label("0 payload positions");
    private final JLabel lenLabel = Ui.label("Length: 0");
    private final JTextField editorSearch = Ui.searchField(14);
    private final JButton start = Ui.primaryButton("Start attack", "launch the attack with the current configuration");
    /** Holds the target row supplied by the tool, under the attack type. */
    private final JPanel targetSlot = new JPanel(new BorderLayout());

    private Runnable onChange;

    /** Place the target row (host, port, TLS) under the attack type, as in the reference. */
    public void setTargetRow(JComponent target) {
        targetSlot.removeAll();
        targetSlot.add(target, BorderLayout.CENTER);
        targetSlot.revalidate();
        targetSlot.repaint();
    }

    public PositionsPanel() {
        super(new BorderLayout());
        setBorder(Ui.pad(10, 5, 10, 10));

        area.setFont(Ui.MONO);
        area.setLineWrap(false);
        area.setToolTipText("use the § marker to define the beginning and end of each payload "
                + "position; the inserted payload will replace the text between the markers");
        area.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { counts(); }
            public void removeUpdate(DocumentEvent e)  { counts(); }
            public void changedUpdate(DocumentEvent e) { counts(); }
        });

        // Header, as in the reference: a help mark, the attack type and the
        // orange Start attack together on one left-aligned row, the target row
        // beneath it, then the Positions row with its marker buttons directly
        // above the request editor.
        attackCombo.setToolTipText("the attack type determines the way in which payloads are "
                + "assigned to payload positions");
        attackCombo.addActionListener(e -> fire());
        attackCombo.setPreferredSize(new Dimension(190, attackCombo.getPreferredSize().height));
        start.setIcon(opentrap.ui.Glyphs.play(12, Color.WHITE));
        start.setIconTextGap(6);
        JButton attackHelp = Ui.helpButton("Intruder",
                "Choose an attack type, mark the payload positions in the request below, "
                + "then configure the payloads on the right and start the attack.");
        JPanel attackRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        attackRow.setOpaque(false);
        attackRow.add(attackHelp);
        attackRow.add(attackCombo);
        attackRow.add(start);

        JButton addMark   = Ui.button("Add §", "insert a payload marker");
        JButton clearMark = Ui.button("Clear §", "clear all payload markers");
        JButton autoMark  = Ui.button("Auto §", "automatically set a payload at each URL, "
                + "cookie and body parameter");
        addMark.addActionListener(e -> addMarker());
        clearMark.addActionListener(e -> clearMarkers());
        autoMark.addActionListener(e -> autoPlace());
        JPanel posRow = Ui.row(Ui.bold("Positions"), addMark, clearMark, autoMark);

        targetSlot.setOpaque(false);
        JPanel header = Ui.vbox(attackRow, Box.createVerticalStrut(6), targetSlot,
                Box.createVerticalStrut(2), posRow, Box.createVerticalStrut(4));

        // The editor footer, as in the reference: help, settings, the match
        // steppers and search, then the three counts at the right.
        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        JPanel footLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        footLeft.setOpaque(false);
        footLeft.add(Ui.helpButton("Payload positions",
                "Select a value and press Add \u00a7 to mark it, or press Auto \u00a7 to mark every "
                + "parameter. Each marked pair is one payload position."));
        footLeft.add(opentrap.ui.Glyphs.menuButton("Editor settings", this::editorMenu));
        footLeft.add(opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.chevron(12, opentrap.ui.Theme.ICON, 3),
                "Previous match", () -> step(-1)));
        footLeft.add(opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.chevron(12, opentrap.ui.Theme.ICON, 0),
                "Next match", () -> step(1)));
        footLeft.add(editorSearch);
        JPanel footRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 12, 0));
        footRight.setOpaque(false);
        highlightLabel.setForeground(opentrap.ui.Theme.MUTED);
        posLabel.setForeground(opentrap.ui.Theme.MUTED);
        lenLabel.setForeground(opentrap.ui.Theme.MUTED);
        footRight.add(highlightLabel);
        footRight.add(posLabel);
        footRight.add(lenLabel);
        south.add(footLeft, BorderLayout.WEST);
        south.add(footRight, BorderLayout.EAST);

        editorSearch.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { search(); }
            public void removeUpdate(DocumentEvent e)  { search(); }
            public void changedUpdate(DocumentEvent e) { search(); }
        });

        JScrollPane sp = Ui.scroll(area);
        sp.setRowHeaderView(new opentrap.ui.LineGutter(area));
        add(header, BorderLayout.NORTH);
        add(sp, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);

        counts();
        paintMarkers();
    }

    // ---------------- configuration ----------------

    public void setStartAction(Runnable r) {
        for (java.awt.event.ActionListener l : start.getActionListeners()) start.removeActionListener(l);
        start.addActionListener(e -> r.run());
    }

    public void onChange(Runnable r) { this.onChange = r; }

    private void fire() { if (onChange != null) onChange.run(); }

    /** Load a request, replacing the text and auto-placing markers. */
    public void setRequest(HttpService svc, HttpMessage req) {
        if (req == null) return;
        area.setText(Bytes.str(req.raw()));
        area.setCaretPosition(0);
        autoPlace();
    }

    public AttackType attackType() {
        AttackType t = (AttackType) attackCombo.getSelectedItem();
        return t == null ? AttackType.SNIPER : t;
    }

    /** The current text, markers included. */
    public String markedText() { return area.getText(); }

    /** Replace the marked request text directly, keeping the user's § markers. */
    public void setMarkedText(String s) { area.setText(s == null ? "" : s); }

    /** Number of payload positions, each a matched pair of markers. */
    public int positionCount() {
        int marks = 0;
        String s = area.getText();
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == MARK) marks++;
        return marks / 2;
    }

    // ---------------- marker editing ----------------

    private void addMarker() {
        int a = area.getSelectionStart(), b = area.getSelectionEnd();
        try {
            if (b > a) {
                area.getDocument().insertString(b, String.valueOf(MARK), null);
                area.getDocument().insertString(a, String.valueOf(MARK), null);
            } else {
                area.getDocument().insertString(a, "" + MARK + MARK, null);
            }
        } catch (Exception ignored) {}
        counts();
    }

    private void clearMarkers() {
        area.setText(stripMarkers(area.getText()));
        counts();
    }

    /** Remove all payload markers, keeping the request text. */
    public void clearPositions() { clearMarkers(); }

    static String stripMarkers(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) != MARK) sb.append(s.charAt(i));
        return sb.toString();
    }

    /** Wrap each parameter value (URL, body, cookie, JSON, XML, multipart) in markers. */
    private void autoPlace() {
        String text = stripMarkers(area.getText());
        HttpMessage m = new HttpMessage(Bytes.bytes(text));
        List<int[]> ranges = new ArrayList<>();
        for (Param p : m.parameters()) {
            Param.Type t = p.type();
            if (t == Param.Type.HEADER) continue;
            int vs = p.valueStart(), ve = p.valueEnd();
            if (vs >= 0 && ve >= vs && ve <= text.length()) ranges.add(new int[]{vs, ve});
        }
        // Insert from the end so earlier offsets stay valid.
        ranges.sort(Comparator.comparingInt((int[] r) -> r[0]).reversed());
        StringBuilder sb = new StringBuilder(text);
        for (int[] r : ranges) {
            sb.insert(r[1], MARK);
            sb.insert(r[0], MARK);
        }
        area.setText(sb.toString());
        area.setCaretPosition(0);
        counts();
    }

    private void counts() {
        int n = positionCount();
        posLabel.setText(n + (n == 1 ? " payload position" : " payload positions"));
        lenLabel.setText("Length: " + stripMarkers(area.getText()).length());
        paintMarkers();
        fire();
    }

    // ---------------- editor marks ----------------

    /** Tint every marked payload position, so the positions read at a glance. */
    private void paintMarkers() {
        javax.swing.text.Highlighter h = area.getHighlighter();
        for (javax.swing.text.Highlighter.Highlight hl : h.getHighlights()) {
            if (hl.getPainter() instanceof javax.swing.text.DefaultHighlighter.DefaultHighlightPainter) h.removeHighlight(hl);
        }
        String s = area.getText();
        int from = s.indexOf(MARK);
        while (from >= 0) {
            int to = s.indexOf(MARK, from + 1);
            if (to < 0) break;
            try { h.addHighlight(from, to + 1, MARKER_PAINT); } catch (javax.swing.text.BadLocationException ignored) {}
            from = s.indexOf(MARK, to + 1);
        }
        searchHits = 0;
        String q = editorSearch == null ? "" : editorSearch.getText();
        if (!q.isEmpty()) {
            String hay = s.toLowerCase();
            String needle = q.toLowerCase();
            int i = hay.indexOf(needle);
            while (i >= 0) {
                try { h.addHighlight(i, i + needle.length(), SEARCH_PAINT); } catch (javax.swing.text.BadLocationException ignored) {}
                searchHits++;
                i = hay.indexOf(needle, i + needle.length());
            }
        }
        highlightLabel.setText(searchHits + (searchHits == 1 ? " highlight" : " highlights"));
    }

    private static final javax.swing.text.DefaultHighlighter.DefaultHighlightPainter MARKER_PAINT =
            new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(new Color(0xFF, 0xE2, 0xB3));
    private static final javax.swing.text.DefaultHighlighter.DefaultHighlightPainter SEARCH_PAINT =
            new javax.swing.text.DefaultHighlighter.DefaultHighlightPainter(opentrap.ui.Theme.CONTROL_TINT);

    private int searchHits;
    private int searchAt;

    private void search() { searchAt = 0; paintMarkers(); }

    /** Move the caret to the next or previous search match. */
    private void step(int dir) {
        String q = editorSearch.getText();
        if (q.isEmpty()) return;
        String hay = area.getText().toLowerCase();
        String needle = q.toLowerCase();
        java.util.List<Integer> at = new ArrayList<>();
        int i = hay.indexOf(needle);
        while (i >= 0) { at.add(i); i = hay.indexOf(needle, i + needle.length()); }
        if (at.isEmpty()) return;
        searchAt = (searchAt + dir + at.size()) % at.size();
        int off = at.get(searchAt);
        area.setCaretPosition(off);
        area.select(off, off + needle.length());
        area.requestFocusInWindow();
    }

    /** The editor settings the footer gear offers. */
    private JPopupMenu editorMenu() {
        JPopupMenu m = new JPopupMenu();
        JCheckBoxMenuItem wrap = new JCheckBoxMenuItem("Wrap lines", area.getLineWrap());
        wrap.setFont(Ui.PLAIN);
        wrap.addActionListener(e -> area.setLineWrap(wrap.isSelected()));
        m.add(wrap);
        JMenuItem reset = new JMenuItem("Clear payload positions");
        reset.setFont(Ui.PLAIN);
        reset.addActionListener(e -> clearMarkers());
        m.add(reset);
        return m;
    }
}
