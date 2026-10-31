package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Payload processing and payload encoding, laid out as the reference draws
 * them: an ordered list of rules with Add / Edit / Remove / Up / Down beside
 * it, then a separate encoding block.
 *
 * <p>{@link #process} runs a payload through each enabled rule in order and
 * then applies the encoding. A rule that drops the payload returns null, which
 * takes it out of the attack.
 */
public class PayloadProcessor extends JPanel {

    /** The case options shared with the case-substitution generator. */
    static final String[] CASE_OPTIONS = {
            "do not modify", "to lower case", "to upper case", "to Propername", "to ProperName"
    };

    /** What a rule does. The label is what the table shows. */
    enum Kind {
        CASE("Change case"),
        MATCH_REPLACE("Match/replace"),
        PREFIX("Add prefix"),
        SUFFIX("Add suffix"),
        SKIP("Skip if matches regex"),
        URL_ENCODE_ALL("URL-encode all characters"),
        URL_DECODE("URL-decode"),
        BASE64_ENCODE("Base64-encode"),
        BASE64_DECODE("Base64-decode"),
        HASH("Hash");

        final String label;
        Kind(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    /** One processing step: its kind, whether it runs, and its parameters. */
    static final class Rule {
        boolean enabled = true;
        Kind kind;
        String a = "";          // regex, prefix, suffix, hash algorithm, or case option
        String b = "";          // replacement
        boolean all = true;     // replace every match rather than the first

        Rule(Kind kind) { this.kind = kind; }

        /** How the rule reads in the table. */
        String describe() {
            return switch (kind) {
                case CASE          -> "Change case: " + a;
                case MATCH_REPLACE -> "Match/replace: \"" + a + "\" -> \"" + b + "\"";
                case PREFIX        -> "Add prefix: " + a;
                case SUFFIX        -> "Add suffix: " + a;
                case SKIP          -> "Skip if matches: " + a;
                case HASH          -> "Hash: " + a;
                default            -> kind.label;
            };
        }
    }

    private final List<Rule> rules = new ArrayList<>();
    private final RuleModel model = new RuleModel();
    private final JTable table = new JTable(model);

    private final JCheckBox encodeOn = Ui.check("URL-encode these characters:", true);
    private final JTextField urlChars = Ui.field(".\\=<>?+&*;:\"{}|^`#", 18);

    public PayloadProcessor() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setMaxWidth(70);
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(360, 130));

        JButton add    = Ui.button("Add", "add a processing rule");
        JButton edit   = Ui.button("Edit", "edit the selected rule");
        JButton remove = Ui.button("Remove", "delete the selected rule");
        JButton up     = Ui.button("Up", "run the selected rule earlier");
        JButton down   = Ui.button("Down", "run the selected rule later");
        add.addActionListener(e -> {
            Rule r = RuleDialog.show(this, null);
            if (r != null) { rules.add(r); model.fireTableDataChanged(); }
        });
        edit.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i < 0) return;
            Rule r = RuleDialog.show(this, rules.get(i));
            if (r != null) { rules.set(i, r); model.fireTableRowsUpdated(i, i); }
        });
        remove.addActionListener(e -> {
            int i = table.getSelectedRow();
            if (i >= 0) { rules.remove(i); model.fireTableDataChanged(); }
        });
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));

        JPanel rulesRow = new JPanel(new BorderLayout(8, 0));
        rulesRow.setOpaque(false);
        rulesRow.add(Ui.buttonColumn(add, edit, remove, up, down), BorderLayout.WEST);
        rulesRow.add(sp, BorderLayout.CENTER);

        // The check box stands in for the row's label, so it and the field sit
        // on one line and the field keeps its own height.
        JPanel encoding = new JPanel(new BorderLayout());
        encoding.setOpaque(false);
        encoding.add(Ui.leftForm(encodeOn, urlChars), BorderLayout.NORTH);

        add(Ui.section("Payload processing",
                "You can define rules to perform various processing tasks on each payload "
              + "before it is used.", rulesRow));
        add(Ui.section("Payload encoding",
                "This setting can be used to URL-encode selected characters within the final "
              + "payload, for safe transmission within HTTP requests.", encoding));
    }

    private void move(int delta) {
        int i = table.getSelectedRow();
        int to = i + delta;
        if (i < 0 || to < 0 || to >= rules.size()) return;
        rules.add(to, rules.remove(i));
        model.fireTableDataChanged();
        table.setRowSelectionInterval(to, to);
    }

    /** Enabled / Rule, as the reference labels the two columns. */
    private final class RuleModel extends AbstractTableModel {
        private final String[] cols = {"Enabled", "Rule"};
        @Override public int getRowCount() { return rules.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c == 0) { rules.get(r).enabled = Boolean.TRUE.equals(v); fireTableRowsUpdated(r, r); }
        }
        @Override public Object getValueAt(int r, int c) {
            Rule rule = rules.get(r);
            return c == 0 ? rule.enabled : rule.describe();
        }
    }

    // ---- running the chain --------------------------------------------------

    /** The processed payload, or null when a rule drops it. */
    public String process(String payload) {
        String s = payload;
        for (Rule r : rules) {
            if (!r.enabled) continue;
            s = apply(r, s);
            if (s == null) return null;
        }
        return encodeOn.isSelected() ? urlEncode(s, urlChars.getText()) : s;
    }

    private static String apply(Rule r, String s) {
        switch (r.kind) {
            case CASE: {
                int mode = java.util.Arrays.asList(CASE_OPTIONS).indexOf(r.a);
                return applyCase(s, Math.max(0, mode));
            }
            case MATCH_REPLACE: {
                if (r.a.isEmpty()) return s;
                try {
                    Matcher m = Pattern.compile(r.a).matcher(s);
                    String with = Matcher.quoteReplacement(r.b);
                    return r.all ? m.replaceAll(with) : m.replaceFirst(with);
                } catch (Exception e) { return s; }
            }
            case PREFIX: return r.a + s;
            case SUFFIX: return s + r.a;
            case SKIP:   return skips(s, r.a) ? null : s;
            case URL_ENCODE_ALL: return urlEncodeAll(s);
            case URL_DECODE:     return urlDecode(s);
            case BASE64_ENCODE:  return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.ISO_8859_1));
            case BASE64_DECODE:  return b64Decode(s);
            case HASH:           return hash(s, r.a);
            default: return s;
        }
    }

    static String urlEncode(String s, String chars) {
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (chars.indexOf(c) >= 0) out.append('%').append(String.format("%02X", (int) c & 0xFF));
            else out.append(c);
        }
        return out.toString();
    }

    private static String urlEncodeAll(String s) {
        StringBuilder out = new StringBuilder(s.length() * 3);
        for (byte b : s.getBytes(StandardCharsets.ISO_8859_1)) out.append('%').append(String.format("%02X", b & 0xFF));
        return out.toString();
    }

    private static String hash(String s, String algorithm) {
        try {
            MessageDigest md = MessageDigest.getInstance(algorithm == null || algorithm.isEmpty() ? "MD5" : algorithm);
            byte[] d = md.digest(s.getBytes(StandardCharsets.ISO_8859_1));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return s;
        }
    }

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

    /** Add / edit one rule: pick the kind, then fill in what it needs. */
    private static final class RuleDialog {
        static Rule show(Component parent, Rule existing) {
            JComboBox<Kind> kind = Ui.combo(Kind.values());
            JTextField a = Ui.field("", 18);
            JTextField b = Ui.field("", 18);
            JCheckBox all = Ui.check("Replace all", true);
            JComboBox<String> caseCombo = Ui.combo(CASE_OPTIONS);
            JComboBox<String> hashAlgo = Ui.combo(new String[]{"MD5", "SHA-1", "SHA-256", "SHA-384", "SHA-512"});
            if (existing != null) {
                kind.setSelectedItem(existing.kind);
                a.setText(existing.a);
                b.setText(existing.b);
                all.setSelected(existing.all);
                caseCombo.setSelectedItem(existing.a);
                hashAlgo.setSelectedItem(existing.a);
            }

            JPanel fields = new JPanel(new CardLayout());
            fields.setOpaque(false);
            fields.add(new JPanel(), "none");
            fields.add(Ui.leftForm("Case:", Ui.natural(caseCombo)), "case");
            fields.add(Ui.leftForm("Match regex:", a, "Replace with:", b, "", all), "replace");
            fields.add(Ui.leftForm("Text:", a), "text");
            fields.add(Ui.leftForm("Regex:", a), "regex");
            fields.add(Ui.leftForm("Algorithm:", Ui.natural(hashAlgo)), "hash");

            Runnable showCard = () -> {
                Kind k = (Kind) kind.getSelectedItem();
                String card = switch (k == null ? Kind.CASE : k) {
                    case CASE -> "case";
                    case MATCH_REPLACE -> "replace";
                    case PREFIX, SUFFIX -> "text";
                    case SKIP -> "regex";
                    case HASH -> "hash";
                    default -> "none";
                };
                ((CardLayout) fields.getLayout()).show(fields, card);
            };
            kind.addActionListener(e -> showCard.run());
            showCard.run();

            JPanel body = new JPanel(new BorderLayout(0, 8));
            body.setOpaque(false);
            body.add(Ui.leftForm("Rule type:", Ui.natural(kind)), BorderLayout.NORTH);
            body.add(fields, BorderLayout.CENTER);

            int ok = JOptionPane.showConfirmDialog(parent, body,
                    existing == null ? "Add payload processing rule" : "Edit payload processing rule",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return null;

            Kind k = (Kind) kind.getSelectedItem();
            Rule r = new Rule(k == null ? Kind.CASE : k);
            r.enabled = existing == null || existing.enabled;
            r.b = b.getText();
            r.all = all.isSelected();
            r.a = switch (r.kind) {
                case CASE -> String.valueOf(caseCombo.getSelectedItem());
                case HASH -> String.valueOf(hashAlgo.getSelectedItem());
                default -> a.getText();
            };
            return r;
        }
    }
}
