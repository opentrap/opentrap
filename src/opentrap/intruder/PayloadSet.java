package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.util.List;

/**
 * One payload set: a generator-type combo, the selected generator's
 * configuration panel, and a payload-processing panel beneath.
 */
public class PayloadSet extends JPanel {

    private final PayloadGenerator[] generators = {
            new SimpleListGenerator(),
            new RuntimeFileGenerator(),
            new NumbersGenerator(),
            new DatesGenerator(),
            new BruteForcerGenerator(),
            new NullPayloadsGenerator(),
            new CharacterSubstitutionGenerator(),
            new CaseSubstitutionGenerator(),
    };

    private final JComboBox<String> typeCombo;
    private final JPanel cards = new JPanel(new CardLayout());
    private final PayloadProcessor processor = new PayloadProcessor();
    private Runnable onChange;

    public PayloadSet() {
        super();
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        String[] labels = new String[generators.length];
        for (int i = 0; i < generators.length; i++) {
            labels[i] = generators[i].label();
            final int idx = i;
            generators[i].onChange(() -> { if (onChange != null) onChange.run(); });
            cards.add(generators[i].config(), String.valueOf(idx));
        }
        cards.setOpaque(false);
        typeCombo = Ui.combo(labels);
        typeCombo.addActionListener(e -> {
            showCard(typeCombo.getSelectedIndex());
            if (onChange != null) onChange.run();
        });

        JPanel top = Ui.form("Payload type:", typeCombo);
        top.setAlignmentX(LEFT_ALIGNMENT);
        top.setMaximumSize(new Dimension(Integer.MAX_VALUE, top.getPreferredSize().height));
        cards.setAlignmentX(LEFT_ALIGNMENT);
        processor.setAlignmentX(LEFT_ALIGNMENT);
        add(top);
        add(cards);
        add(Box.createVerticalStrut(12));
        add(new JSeparator());
        add(Box.createVerticalStrut(8));
        add(processor);
        showCard(0);
    }

    private void showCard(int i) {
        ((CardLayout) cards.getLayout()).show(cards, String.valueOf(i));
    }

    private PayloadGenerator current() { return generators[typeCombo.getSelectedIndex()]; }

    public void onChange(Runnable r) { this.onChange = r; }

    public void setBase(List<String> base) { for (PayloadGenerator g : generators) g.setBase(base); }

    /** Raw payload count for this set, or -1 when unbounded. */
    public int count() { return current().count(); }

    /** The processed payloads this set will contribute to an attack. */
    public List<String> payloads() {
        List<String> raw = current().generate();
        java.util.List<String> out = new java.util.ArrayList<>(raw.size());
        for (String p : raw) {
            String processed = processor.process(p);
            if (processed != null) out.add(processed); // null = a skip rule dropped it
        }
        return out;
    }
}
