package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The payload-processing panel shown beneath a payload set: case changes, a
 * regular-expression match/replace, and an encoding choice. {@link #process}
 * runs each payload through the configured chain before it is used.
 */
public class PayloadProcessor extends JPanel {

    /** The case options shared with the case-substitution generator. */
    static final String[] CASE_OPTIONS = {
            "do not modify", "to lower case", "to upper case", "to Propername", "to ProperName"
    };

    private final JComboBox<String> caseCombo = Ui.combo(CASE_OPTIONS);

    private final JTextField matchField   = Ui.field("", 14);
    private final JTextField replaceField = Ui.field("", 14);
    private final JCheckBox replaceAll     = Ui.check("replace all", true);

    private final JTextField prefixField = Ui.field("", 14);
    private final JTextField suffixField = Ui.field("", 14);

    private final JRadioButton encNone   = Ui.radio("Do not encode", false);
    private final JRadioButton encUrl    = Ui.radio("URL-encode these characters:", true);
    private final JRadioButton encB64    = Ui.radio("Base-64 encode", false);
    private final JRadioButton encHash   = Ui.radio("Hash", false);
    private final JRadioButton encUrlDec = Ui.radio("URL-decode", false);
    private final JRadioButton encB64Dec = Ui.radio("Base-64 decode", false);
    private final JTextField urlChars  = Ui.field(".\\=<>?+&*;:\"{}|^`#", 14);
    private final JComboBox<String> hashAlgo =
            Ui.combo(new String[]{"MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512"});

    private final JTextField skipField = Ui.field("", 14);

    public PayloadProcessor() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        ButtonGroup enc = new ButtonGroup();
        enc.add(encNone); enc.add(encUrl); enc.add(encB64); enc.add(encHash);
        enc.add(encUrlDec); enc.add(encB64Dec);

        JLabel heading = Ui.heading("Payload processing");
        heading.setAlignmentX(LEFT_ALIGNMENT);
        add(heading);
        add(Box.createVerticalStrut(6));

        // Lay the labelled fields on one grid: labels right-aligned in a shared
        // column, fields at their natural width in the next column, one per row
        // so nothing overflows this narrow panel.
        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        GridBagConstraints c = new GridBagConstraints();
        int y = 0;
        y = formRow(form, c, y, "Case:", caseCombo);
        y = formRow(form, c, y, "Match regex:", matchField);
        y = formRow(form, c, y, "Replace with:", Ui.row(replaceField, replaceAll));
        y = formRow(form, c, y, "Add prefix:", prefixField);
        y = formRow(form, c, y, "Add suffix:", suffixField);
        // Absorb spare width on the right so the label/field columns stay packed
        // to the left under the heading instead of centring in the panel.
        c.gridx = 2; c.gridy = 0; c.weightx = 1; c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        form.add(Box.createHorizontalGlue(), c);
        add(left(form));

        add(Box.createVerticalStrut(10));
        add(left(Ui.heading("Payload encoding")));
        add(Box.createVerticalStrut(2));
        // Every encoding choice is wrapped the same way so the radios line up at
        // one left margin, whether or not they carry a trailing field.
        JPanel enc2 = new JPanel();
        enc2.setOpaque(false);
        enc2.setLayout(new BoxLayout(enc2, BoxLayout.Y_AXIS));
        for (JComponent r : new JComponent[]{
                Ui.row(encNone), Ui.row(encUrl, urlChars), Ui.row(encB64),
                Ui.row(encHash, hashAlgo), Ui.row(encUrlDec), Ui.row(encB64Dec)}) {
            enc2.add(left(r));
        }
        add(left(enc2));

        add(Box.createVerticalStrut(10));
        add(left(Ui.form("Skip payload if it matches regex:", skipField)));
    }

    private JComponent left(JComponent c) { c.setAlignmentX(LEFT_ALIGNMENT); return c; }

    /** One label/field row on a shared grid: label right-aligned in column 0,
     *  field at its natural width in column 1. Returns the next row index. */
    private static int formRow(JPanel grid, GridBagConstraints c, int y, String label, JComponent field) {
        c.gridy = y;
        c.gridx = 0; c.weightx = 0; c.fill = GridBagConstraints.NONE;
        c.anchor = GridBagConstraints.LINE_END; c.insets = new Insets(2, 0, 2, 8);
        grid.add(Ui.label(label), c);
        c.gridx = 1; c.anchor = GridBagConstraints.LINE_START; c.insets = new Insets(2, 0, 2, 0);
        grid.add(field, c);
        return y + 1;
    }

    // ---------------- processing ----------------

    /** Process one payload, or return null when a skip rule says to drop it. */
    public String process(String payload) {
        String s = applyCase(payload, caseCombo.getSelectedIndex());
        s = applyRegex(s);
        s = prefixField.getText() + s + suffixField.getText();
        s = applyEncoding(s);
        if (skips(s, skipField.getText())) return null;
        return s;
    }

    /** True when the payload matches the skip expression (empty = never skip). */
    static boolean skips(String payload, String regex) {
        if (regex == null || regex.isEmpty()) return false;
        try { return Pattern.compile(regex).matcher(payload).find(); }
        catch (Exception e) { return false; }
    }

    static String urlDecode(String s) {
        try { return java.net.URLDecoder.decode(s, StandardCharsets.ISO_8859_1); }
        catch (Exception e) { return s; }
    }

    static String b64Decode(String s) {
        try { return new String(Base64.getDecoder().decode(s), StandardCharsets.ISO_8859_1); }
        catch (Exception e) { return s; }
    }

    private String applyRegex(String s) {
        String rx = matchField.getText();
        if (rx.isEmpty()) return s;
        try {
            Matcher m = Pattern.compile(rx).matcher(s);
            String with = Matcher.quoteReplacement(replaceField.getText());
            return replaceAll.isSelected() ? m.replaceAll(with) : m.replaceFirst(with);
        } catch (Exception e) {
            return s;
        }
    }

    private String applyEncoding(String s) {
        if (encUrl.isSelected()) {
            String chars = urlChars.getText();
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (chars.indexOf(c) >= 0) {
                    out.append('%').append(String.format("%02X", (int) c & 0xFF));
                } else {
                    out.append(c);
                }
            }
            return out.toString();
        }
        if (encB64.isSelected()) {
            return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.ISO_8859_1));
        }
        if (encUrlDec.isSelected()) return urlDecode(s);
        if (encB64Dec.isSelected()) return b64Decode(s);
        if (encHash.isSelected()) {
            try {
                MessageDigest md = MessageDigest.getInstance((String) hashAlgo.getSelectedItem());
                byte[] d = md.digest(s.getBytes(StandardCharsets.ISO_8859_1));
                StringBuilder sb = new StringBuilder(d.length * 2);
                for (byte b : d) sb.append(String.format("%02x", b));
                return sb.toString();
            } catch (Exception e) {
                return s;
            }
        }
        return s;
    }

    /** Apply one of {@link #CASE_OPTIONS} by index. */
    static String applyCase(String s, int mode) {
        return switch (mode) {
            case 1 -> s.toLowerCase();
            case 2 -> s.toUpperCase();
            case 3 -> properName(s);       // Propername: first letter only
            case 4 -> properCase(s);       // ProperName: each word
            default -> s;
        };
    }

    private static String properName(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase();
    }

    private static String properCase(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean startOfWord = true;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetter(c)) {
                out.append(startOfWord ? Character.toUpperCase(c) : Character.toLowerCase(c));
                startOfWord = false;
            } else {
                out.append(c);
                startOfWord = true;
            }
        }
        return out.toString();
    }
}
