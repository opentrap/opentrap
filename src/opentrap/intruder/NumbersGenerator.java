package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** Numeric payloads, sequential or random, in decimal or hexadecimal. */
class NumbersGenerator extends AbstractGenerator {

    private final JTextField from = Ui.shortField("1");
    private final JTextField to   = Ui.shortField("100");
    private final JTextField step = Ui.shortField("1");
    private final JRadioButton sequential = Ui.radio("sequential", true);
    private final JRadioButton random     = Ui.radio("random", false);
    private final JRadioButton decimal     = Ui.radio("decimal", true);
    private final JRadioButton hex         = Ui.radio("hex", false);
    private final JPanel panel;

    NumbersGenerator() {
        ButtonGroup order = new ButtonGroup();
        order.add(sequential); order.add(random);
        ButtonGroup radix = new ButtonGroup();
        radix.add(decimal); radix.add(hex);

        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { fire(); }
            public void removeUpdate(DocumentEvent e)  { fire(); }
            public void changedUpdate(DocumentEvent e) { fire(); }
        };
        from.getDocument().addDocumentListener(dl);
        to.getDocument().addDocumentListener(dl);
        step.getDocument().addDocumentListener(dl);

        panel = Ui.vbox(
                Ui.form("From:", from, "To:", to, "Step:", step),
                Ui.row(sequential, random),
                Ui.row(decimal, hex));
    }

    private double d(JTextField f, double def) {
        try { return Double.parseDouble(f.getText().trim()); } catch (Exception e) { return def; }
    }

    @Override public String label() { return " numbers"; }
    @Override public JComponent config() { return panel; }

    @Override public int count() {
        double f = d(from, 0), t = d(to, 0), s = d(step, 1);
        if (s == 0) return 0;
        int n = (int) Math.floor(Math.abs(t - f) / Math.abs(s)) + 1;
        return Math.min(Math.max(n, 0), CAP);
    }

    @Override public List<String> generate() {
        double f = d(from, 0), t = d(to, 0), s = Math.abs(d(step, 1));
        List<String> out = new ArrayList<>();
        if (s == 0) return out;
        int n = count();
        boolean whole = f == Math.rint(f) && t == Math.rint(t) && s == Math.rint(s);
        if (random.isSelected()) {
            Random rnd = new Random();
            double lo = Math.min(f, t), hi = Math.max(f, t);
            for (int i = 0; i < n; i++) {
                double v = lo + rnd.nextDouble() * (hi - lo);
                out.add(format(whole ? Math.rint(v) : v, whole));
            }
        } else {
            double dir = t >= f ? 1 : -1;
            for (int i = 0; i < n; i++) out.add(format(f + dir * s * i, whole));
        }
        return out;
    }

    private String format(double v, boolean whole) {
        if (hex.isSelected()) return Long.toHexString((long) v);
        if (whole) return String.valueOf((long) v);
        return String.valueOf(v);
    }
}
