package opentrap.intruder;

import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The payloads tab: a selector for the active payload set, the configuration
 * of each set, and live payload/request counts. The number of sets is driven
 * by the attack type and the number of payload positions.
 */
public class PayloadsPanel extends JPanel {

    /** Maximum payload sets, matching the original. */
    static final int MAX_SETS = 8;

    private final PayloadSet[] sets = new PayloadSet[MAX_SETS];
    private final JPanel cards = new JPanel(new CardLayout());
    private final JComboBox<String> setCombo = new JComboBox<>();
    private final JLabel payloadCount = Ui.label("0");
    private final JLabel requestCount = Ui.label("0");
    private final JButton start = Ui.primaryButton("start attack", "launch the attack with the current configuration");

    private AttackType attackType = AttackType.SNIPER;
    private int positions = 0;
    private int activeSetCount = 1;
    /** Which set currently holds the shared count labels, or -1 for none. */
    private int summaryAt = -1;

    public PayloadsPanel() {
        super(new BorderLayout());
        setBorder(Ui.pad(10, 10, 10, 10));

        for (int i = 0; i < MAX_SETS; i++) {
            sets[i] = new PayloadSet();
            sets[i].onChange(this::refreshCounts);
            cards.add(sets[i], String.valueOf(i));
        }
        cards.setOpaque(false);

        setCombo.setFont(Ui.PLAIN);
        setCombo.setToolTipText("choose the payload set to configure; the attack type "
                + "determines the number of payload sets available");
        setCombo.addActionListener(e -> {
            int i = setCombo.getSelectedIndex();
            if (i >= 0 && positions > 0) {
                ((CardLayout) cards.getLayout()).show(cards, String.valueOf(i));
                refreshCounts();
            }
        });

        // As in the reference: payload position (set), then the counts, in one
        // aligned form. Start attack lives in the attack header on the left.
        // The panel header, then the four summary rows: labels left-aligned in
        // a shared column with the combos stretched across the rest, as the
        // reference lays its side panels out.
        JPanel heading = new JPanel(new BorderLayout());
        heading.setOpaque(false);
        heading.setBorder(Ui.pad(0, 0, 8, 0));
        JLabel title = Ui.bold("Payloads");
        title.setFont(Ui.HEADING.deriveFont(14f));
        heading.add(title, BorderLayout.WEST);
        heading.add(Ui.helpButton("Payload sets",
                "One payload set feeds each payload position. The attack type decides how many "
                + "sets there are and how their payloads are combined."), BorderLayout.EAST);
        JPanel top = Ui.vbox(
                heading,
                Ui.leftForm("Payload position:", setCombo),
                Box.createVerticalStrut(2));

        add(top, BorderLayout.NORTH);
        add(cards, BorderLayout.CENTER);

        setAttack(AttackType.SNIPER, 0);
    }

    public void setStartAction(Runnable r) {
        for (java.awt.event.ActionListener l : start.getActionListeners()) start.removeActionListener(l);
        start.addActionListener(e -> r.run());
    }

    /** Re-derive the number of payload sets from attack type and positions. */
    public void setAttack(AttackType type, int positions) {
        this.attackType = type;
        this.positions = positions;
        this.activeSetCount = Math.min(Math.max(1, type.setCount(positions)), MAX_SETS);

        int keep = Math.max(0, Math.min(setCombo.getSelectedIndex(), activeSetCount - 1));
        setCombo.removeAllItems();
        if (positions == 0) {
            // Nothing is marked yet, so say so rather than offering set 1.
            setCombo.addItem("No payload positions configured");
        } else {
            for (int i = 1; i <= activeSetCount; i++) setCombo.addItem(String.valueOf(i));
            setCombo.setSelectedIndex(keep);
        }
        refreshCounts();
    }

    /** The payload sets this attack will draw from, in position order. */
    public List<PayloadSet> activeSets() {
        List<PayloadSet> out = new ArrayList<>();
        for (int i = 0; i < activeSetCount; i++) out.add(sets[i]);
        return out;
    }

    private void refreshCounts() {
        int idx = positions == 0 ? 0 : setCombo.getSelectedIndex();
        if (idx < 0 || idx >= MAX_SETS) idx = 0;
        // One label cannot sit in two containers, so the count rows move into
        // whichever set is on screen.
        if (idx != summaryAt) {
            sets[idx].setSummary(payloadCount, requestCount);
            summaryAt = idx;
        }
        int c = sets[idx].count();
        payloadCount.setText(c < 0 ? "unknown" : String.valueOf(c));

        int[] sizes = new int[activeSetCount];
        for (int i = 0; i < activeSetCount; i++) {
            int n = sets[i].count();
            sizes[i] = n < 0 ? 0 : n;
        }
        long reqs = attackType.requestCount(positions, sizes);
        requestCount.setText(String.valueOf(reqs));
    }
}
