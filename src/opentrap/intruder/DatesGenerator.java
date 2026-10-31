package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Date payloads stepped from one date to another and formatted to a pattern. */
class DatesGenerator extends AbstractGenerator {

    private final JTextField from = Ui.field("2020-01-01");
    private final JTextField to   = Ui.field("2020-12-31");
    private final JTextField step = Ui.shortField("1");
    private final JComboBox<String> unit = Ui.combo(new String[]{"days", "weeks", "months", "years"});
    private final JTextField pattern = Ui.field("yyyy-MM-dd");
    private final JPanel panel;

    private static final DateTimeFormatter ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    DatesGenerator() {
        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { fire(); }
            public void removeUpdate(DocumentEvent e)  { fire(); }
            public void changedUpdate(DocumentEvent e) { fire(); }
        };
        from.getDocument().addDocumentListener(dl);
        to.getDocument().addDocumentListener(dl);
        step.getDocument().addDocumentListener(dl);
        unit.addActionListener(e -> fire());

        panel = Ui.vbox(
                Ui.form("From:", from, "To:", to, "Step:", Ui.row(step, unit), "Format:", pattern));
    }

    private LocalDate date(JTextField f) {
        try { return LocalDate.parse(f.getText().trim(), ISO); } catch (Exception e) { return null; }
    }

    private int step() {
        try { return Math.max(1, Integer.parseInt(step.getText().trim())); } catch (Exception e) { return 1; }
    }

    private LocalDate advance(LocalDate d, int step) {
        return switch (unit.getSelectedIndex()) {
            case 1 -> d.plusWeeks(step);
            case 2 -> d.plusMonths(step);
            case 3 -> d.plusYears(step);
            default -> d.plusDays(step);
        };
    }

    @Override public String label() { return " dates"; }
    @Override public JComponent config() { return panel; }

    @Override public List<String> generate() {
        List<String> out = new ArrayList<>();
        LocalDate f = date(from), t = date(to);
        if (f == null || t == null || f.isAfter(t)) return out;
        DateTimeFormatter fmt;
        try { fmt = DateTimeFormatter.ofPattern(pattern.getText().trim()); }
        catch (Exception e) { fmt = ISO; }
        int s = step();
        LocalDate d = f;
        while (!d.isAfter(t) && out.size() < CAP) {
            out.add(d.format(fmt));
            d = advance(d, s);
        }
        return out;
    }

    @Override public int count() {
        LocalDate f = date(from), t = date(to);
        if (f == null || t == null || f.isAfter(t)) return 0;
        int s = step();
        int n = 0;
        LocalDate d = f;
        while (!d.isAfter(t) && n < CAP) { n++; d = advance(d, s); }
        return n;
    }
}
