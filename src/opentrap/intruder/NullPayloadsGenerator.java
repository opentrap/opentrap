package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.util.ArrayList;
import java.util.List;

/**
 * Empty payloads, useful for repeating the base request a fixed number of
 * times or indefinitely.
 */
class NullPayloadsGenerator extends AbstractGenerator {

    private final JRadioButton fixed      = Ui.radio("generate", true);
    private final JRadioButton indefinite = Ui.radio("continue indefinitely", false);
    private final JTextField countField = Ui.shortField("100");
    private final JPanel panel;

    NullPayloadsGenerator() {
        ButtonGroup g = new ButtonGroup();
        g.add(fixed); g.add(indefinite);
        fixed.addActionListener(e -> fire());
        indefinite.addActionListener(e -> fire());
        countField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e)  { fire(); }
            public void removeUpdate(DocumentEvent e)  { fire(); }
            public void changedUpdate(DocumentEvent e) { fire(); }
        });
        panel = Ui.vbox(
                Ui.row(fixed, countField, Ui.label("payloads")),
                Ui.row(indefinite));
    }

    private int fixedCount() {
        try { return Math.max(0, Integer.parseInt(countField.getText().trim())); } catch (Exception e) { return 0; }
    }

    @Override public String label() { return " null payloads"; }
    @Override public JComponent config() { return panel; }

    @Override public int count() {
        if (indefinite.isSelected()) return -1;
        return Math.min(fixedCount(), CAP);
    }

    @Override public List<String> generate() {
        List<String> out = new ArrayList<>();
        int n = indefinite.isSelected() ? CAP : Math.min(fixedCount(), CAP);
        for (int i = 0; i < n; i++) out.add("");
        return out;
    }
}
