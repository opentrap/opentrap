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
    /** The "Payload type" row, so the panel can put the counts under it. */
    private JPanel typeRow;
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

        this.typeRow = Ui.leftForm("Payload type:", typeCombo);
        JPanel top = this.typeRow;
        top.setAlignmentX(LEFT_ALIGNMENT);
        top.setMaximumSize(new Dimension(Integer.MAX_VALUE, top.getPreferredSize().height));
        JPanel config = Ui.section("Payload configuration",
                "This payload type lets you configure the list of values that are used as payloads.",
                cards);
        config.setAlignmentX(LEFT_ALIGNMENT);
        processor.setAlignmentX(LEFT_ALIGNMENT);
        add(top);
        add(config);
        add(processor);
        showCard(0);
    }

    /**
     * Put the panel's count rows directly under the type row, which is the
     * order the reference uses: position, type, then the two counts.
     */
    void setSummary(javax.swing.JLabel payloadCount, javax.swing.JLabel requestCount) {
        int at = getComponentZOrder(typeRow);
        remove(typeRow);
        typeRow = Ui.leftForm("Payload type:", typeCombo,
                "Payload count:", Ui.natural(payloadCount),
                "Request count:", Ui.natural(requestCount));
        typeRow.setAlignmentX(LEFT_ALIGNMENT);
        typeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, typeRow.getPreferredSize().height));
        add(typeRow, at);
        revalidate();
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
