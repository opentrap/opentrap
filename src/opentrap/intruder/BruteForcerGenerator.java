package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.ArrayList;
import java.util.List;

/** Every string over a character set, between a minimum and maximum length. */
class BruteForcerGenerator extends AbstractGenerator {

    private final JTextField charset = Ui.field("abcdefghijklmnopqrstuvwxyz0123456789");
    private final JTextField min = Ui.shortField("1");
    private final JTextField max = Ui.shortField("3");
    private final JPanel panel;

    BruteForcerGenerator() {
        DocumentListener dl = new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { fire(); }
            public void removeUpdate(DocumentEvent e)  { fire(); }
            public void changedUpdate(DocumentEvent e) { fire(); }
        };
        charset.getDocument().addDocumentListener(dl);
        min.getDocument().addDocumentListener(dl);
        max.getDocument().addDocumentListener(dl);

        panel = Ui.vbox(
                Ui.form("Character set:", charset, "Min length:", min, "Max length:", max));
    }

    private int min() { try { return Math.max(0, Integer.parseInt(min.getText().trim())); } catch (Exception e) { return 1; } }
    private int max() { try { return Math.max(min(), Integer.parseInt(max.getText().trim())); } catch (Exception e) { return min(); } }

    @Override public String label() { return " brute forcer"; }
    @Override public JComponent config() { return panel; }

    @Override public int count() {
        String cs = charset.getText();
        if (cs.isEmpty()) return 0;
        long total = 0;
        for (int len = min(); len <= max(); len++) {
            total += (long) Math.pow(cs.length(), len);
            if (total >= CAP) return CAP;
        }
        return (int) Math.min(total, CAP);
    }

    @Override public List<String> generate() {
        List<String> out = new ArrayList<>();
        String cs = charset.getText();
        if (cs.isEmpty()) return out;
        for (int len = min(); len <= max() && out.size() < CAP; len++) {
            emit(cs, new char[len], 0, out);
        }
        return out;
    }

    private void emit(String cs, char[] buf, int pos, List<String> out) {
        if (out.size() >= CAP) return;
        if (pos == buf.length) { out.add(new String(buf)); return; }
        for (int i = 0; i < cs.length(); i++) {
            buf[pos] = cs.charAt(i);
            emit(cs, buf, pos + 1, out);
            if (out.size() >= CAP) return;
        }
    }
}
