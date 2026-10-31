package opentrap.decoder;

import opentrap.core.Bytes;
import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * A vertical chain of stages. Each transform applied to a stage appends its
 * result below as a new stage, so a nested encoding is peeled one layer at a
 * time and every intermediate form stays on screen.
 */
public class DecoderTool implements ToolModule {

    private static final int CONTROL_WIDTH = 150;
    private static final int STAGE_HEIGHT = 110;

    private final JPanel root = new JPanel(new BorderLayout(0, 6));
    private final JPanel column = new JPanel();
    private final List<Stage> stages = new ArrayList<>();

    /** Suppresses the editor and combo listeners while the chain is rebuilt. */
    private boolean updating;

    public DecoderTool() {
        column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
        column.setOpaque(false);

        JPanel holder = new JPanel(new BorderLayout());
        holder.setOpaque(false);
        holder.add(column, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(holder);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(25);

        root.setBorder(Ui.pad(8, 8, 8, 8));
        root.add(scroll, BorderLayout.CENTER);

        setData(new byte[0]);
    }

    @Override public String name() { return "decoder"; }

    @Override public Tab[] tabs() { return new Tab[]{ new Tab("decoder", root) }; }

    @Override public java.io.Serializable saveState() {
        if (stages.isEmpty()) return null;
        String input = stages.get(0).area.getText();
        return (input == null || input.isEmpty()) ? null : input;
    }

    @Override public void restoreState(java.io.Serializable state) {
        // Setting the first stage's text fires its listener, which re-runs the
        // whole decode pipeline.
        if (state instanceof String s && !stages.isEmpty()) stages.get(0).area.setText(s);
    }

    @Override public void init(SuiteContext ctx) {
        ctx.registerSink("decoder", (svc, req, resp, caption) -> {
            byte[] data = req != null ? req.raw() : resp != null ? resp.raw() : new byte[0];
            SwingUtilities.invokeLater(() -> setData(data));
        });
    }

    // ---- the chain ----

    /** Replace everything with a single stage holding these bytes. */
    private void setData(byte[] data) {
        stages.clear();
        Stage first = new Stage(true);
        first.data = data == null ? new byte[0] : data;
        chooseView(first);
        stages.add(first);
        render(first);
        relayout();
    }

    /**
     * Recompute every stage from the first editor downwards, adding the stage
     * a transform produces and dropping anything left over below the chain.
     */
    private void rerun() {
        Stage first = stages.get(0);
        first.data = read(first);
        int i = 0;
        while (i < stages.size()) {
            Stage s = stages.get(i);
            s.clearError();
            if (s.opKind == null) break;
            byte[] out;
            try {
                out = Transforms.apply(s.opKind, s.opName, s.data);
            } catch (Exception ex) {
                out = s.data;                    // malformed input leaves the data alone
                s.showError(describe(ex));
            }
            Stage next;
            if (i + 1 < stages.size()) {
                next = stages.get(i + 1);
                next.data = out;
            } else {
                next = new Stage(false);
                next.data = out;
                chooseView(next);
                stages.add(next);
            }
            render(next);
            i++;
        }
        dropBelow(i);
        relayout();
    }

    private void apply(Stage s, String kind, String name) {
        s.opKind = kind;
        s.opName = name;
        dropBelow(stages.indexOf(s));
        rerun();
    }

    private void dropBelow(int index) {
        while (stages.size() > index + 1) stages.remove(stages.size() - 1);
    }

    private void relayout() {
        column.removeAll();
        for (Stage s : stages) column.add(s.panel);
        column.revalidate();
        column.repaint();
    }

    // ---- stage data and display ----

    private void render(Stage s) {
        boolean was = updating;
        updating = true;
        s.area.setText(s.hexView ? Transforms.spacedHex(s.data) : Bytes.str(s.data));
        s.area.setCaretPosition(0);
        updating = was;
    }

    private byte[] read(Stage s) {
        return s.hexView ? Transforms.looseHex(s.area.getText()) : Bytes.bytes(s.area.getText());
    }

    /** Binary data is unreadable as text, so such a stage opens in hex. */
    private void chooseView(Stage s) {
        boolean binary = false;
        for (byte b : s.data) {
            int v = b & 0xff;
            if (v > 127 || v == 127 || (v < 32 && v != 9 && v != 10 && v != 13)) {
                binary = true;
                break;
            }
        }
        s.hexView = binary;
        boolean was = updating;
        updating = true;
        s.hex.setSelected(binary);
        s.text.setSelected(!binary);
        updating = was;
    }

    private void select(JComboBox<String> combo, String item) {
        boolean was = updating;
        updating = true;
        combo.setSelectedItem(item);
        updating = was;
    }

    private void reset(JComboBox<String> combo) {
        select(combo, combo.getItemAt(0));
    }

    private static String describe(Exception ex) {
        String m = ex.getMessage();
        if (m == null || m.isEmpty()) m = ex.getClass().getSimpleName();
        if (Character.isUpperCase(m.charAt(0))) m = Character.toLowerCase(m.charAt(0)) + m.substring(1);
        return m;
    }

    /** The colour each transform is drawn in, in the dropdowns and on the stage it produced. */
    static Color opColour(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase()) {
            case "url" -> new Color(0xE0, 0x3C, 0x3C);
            case "html" -> new Color(0xE8, 0x82, 0x0C);
            case "base64" -> new Color(0xD4, 0xC2, 0x00);
            case "ascii hex" -> new Color(0x2E, 0xA0, 0x4A);
            case "hex" -> opentrap.ui.Theme.CONTROL;
            case "octal" -> new Color(0x1F, 0x9E, 0xA8);
            case "binary" -> new Color(0xC0, 0x3C, 0xC0);
            case "gzip", "deflate", "brotli" -> new Color(0xE0, 0x7A, 0x7A);
            default -> null;
        };
    }

    /** Dropdown rows tinted by their transform's colour. */
    private static final class OpRenderer extends DefaultListCellRenderer {
        @Override public Component getListCellRendererComponent(JList<?> list, Object v, int i, boolean sel, boolean f) {
            super.getListCellRendererComponent(list, v, i, sel, f);
            Color c = opColour(String.valueOf(v));
            if (c != null && !sel && i >= 0) { setOpaque(true); setBackground(c); setForeground(Color.WHITE); }
            return this;
        }
    }

    /** One link in the chain: the data, how it is shown, and what it feeds. */
    private final class Stage {

        final boolean first;
        byte[] data = new byte[0];
        boolean hexView;
        String opKind;
        String opName;

        final JTextArea area = new JTextArea();
        final JRadioButton text = Ui.radio("Text", true);
        final JRadioButton hex = Ui.radio("Hex", false);
        final JComboBox<String> decode = Ui.combo(Transforms.decodeItems());
        final JComboBox<String> encode = Ui.combo(Transforms.encodeItems());
        final JComboBox<String> hash = Ui.combo(Transforms.hashItems());
        final JButton smart = Ui.button("Smart decode", "guess the encoding and decode one layer");
        final JLabel error = Ui.label(" ");
        final JPanel panel = new JPanel(new BorderLayout(6, 0));

        Stage(boolean first) {
            this.first = first;
            area.setFont(Ui.PLAIN);
            area.setLineWrap(true);
            area.setEditable(first);
            area.setBackground(opentrap.ui.Theme.BG);

            ButtonGroup group = new ButtonGroup();
            group.add(text);
            group.add(hex);

            error.setForeground(Ui.ERROR_RED);

            if (first) {
                area.getDocument().addDocumentListener(new DocumentListener() {
                    @Override public void insertUpdate(DocumentEvent e) { edited(); }
                    @Override public void removeUpdate(DocumentEvent e) { edited(); }
                    @Override public void changedUpdate(DocumentEvent e) { edited(); }
                });
            }

            Runnable view = () -> {
                if (updating) return;
                data = read(this);               // read under the old view before switching
                hexView = hex.isSelected();
                render(this);
                if (first) rerun();
            };
            text.addActionListener(e -> view.run());
            hex.addActionListener(e -> view.run());

            decode.setRenderer(new OpRenderer());
            encode.setRenderer(new OpRenderer());
            decode.addActionListener(e -> chosen(decode, Transforms.DECODE, encode, hash));
            encode.addActionListener(e -> chosen(encode, Transforms.ENCODE, decode, hash));
            hash.addActionListener(e -> chosen(hash, Transforms.HASH, decode, encode));
            smart.addActionListener(e -> smartDecode());

            JScrollPane sp = Ui.scroll(area);
            sp.setPreferredSize(new Dimension(200, STAGE_HEIGHT));
            panel.setOpaque(false);
            panel.setBorder(Ui.pad(0, 0, 10, 0));
            panel.add(sp, BorderLayout.CENTER);
            panel.add(controls(), BorderLayout.EAST);
        }

        private JComponent controls() {
            JPanel p = new JPanel();
            p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
            p.setOpaque(false);
            JPanel radios = Ui.row(text, hex);
            if (first) {
                JButton help = opentrap.ui.Glyphs.button(opentrap.ui.Glyphs.help(18, opentrap.ui.Theme.MUTED), "About Decoder", () ->
                        JOptionPane.showMessageDialog(panel, "Type or paste into the top pane, then choose a decoding, encoding or hash.\n"
                                + "Each result opens a new pane beneath; the text it came from is highlighted above.", "Decoder", JOptionPane.INFORMATION_MESSAGE));
                radios.add(Box.createHorizontalStrut(10));
                radios.add(help);
            }
            p.add(fixed(radios));
            p.add(fixed(decode));
            p.add(Box.createVerticalStrut(2));
            p.add(fixed(encode));
            p.add(Box.createVerticalStrut(2));
            p.add(fixed(hash));
            p.add(Box.createVerticalStrut(2));
            p.add(fixed(smart));
            p.add(fixed(error));
            return p;
        }

        /** Pin a control to the column width so long names cannot widen it. */
        private JComponent fixed(JComponent c) {
            int h = c.getPreferredSize().height;
            c.setPreferredSize(new Dimension(CONTROL_WIDTH, h));
            c.setMaximumSize(new Dimension(CONTROL_WIDTH, h));
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            return c;
        }

        private void edited() {
            if (updating) return;
            // the rerun rewrites the documents below, which Swing forbids
            // while a document event is still being delivered
            SwingUtilities.invokeLater(DecoderTool.this::rerun);
        }

        private void chosen(JComboBox<String> combo, String kind, JComboBox<String> a, JComboBox<String> b) {
            if (updating || combo.getSelectedIndex() <= 0) return;
            reset(a);
            reset(b);
            String name = (String) combo.getSelectedItem();
            apply(this, kind, name);
            // The combo returns to its prompt; the colour of the pane below says what was applied.
            reset(combo);
            Color c = opColour(name);
            if (c != null) area.setBackground(new Color(c.getRed(), c.getGreen(), c.getBlue(), 60));
        }

        private void smartDecode() {
            if (updating) return;
            String guess = Transforms.guess(data);
            if (guess == null) {
                showError("no encoding recognised");
                return;
            }
            select(decode, guess);
            reset(encode);
            reset(hash);
            apply(this, Transforms.DECODE, guess);
        }

        void showError(String message) {
            error.setText(message);
            error.setToolTipText(message);
        }

        void clearError() {
            error.setText(" ");
            error.setToolTipText(null);
        }
    }
}
