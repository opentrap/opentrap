package opentrap.proxy;

import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/**
 * Add or edit one interception rule: how it joins the chain, what it looks at,
 * how it compares, and against what.
 */
final class InterceptRuleDialog {

    private InterceptRuleDialog() {}

    /**
     * @param first whether this rule starts the chain, in which case it has no
     *              boolean operator to join it to anything.
     */
    static ProxyRule show(Component parent, ProxyRule existing, boolean response, boolean first) {
        ProxyRule r = existing == null ? new ProxyRule() : existing;

        JComboBox<String> op = Ui.combo(new String[]{"and", "or"});
        op.setSelectedItem(r.booleanOp == null ? "or" : r.booleanOp);
        op.setEnabled(!first);

        String[] items = response ? ProxyRule.RESPONSE_ITEMS : ProxyRule.REQUEST_ITEMS;
        JComboBox<String> item = Ui.combo(items);
        item.setSelectedItem(r.matchItem);

        JComboBox<String> rel = Ui.combo(ProxyRule.relationshipsFor(r.matchItem, response));
        rel.setSelectedItem(r.relationship);

        JTextField condition = Ui.field(r.condition == null ? "" : r.condition, 28);
        condition.setEnabled(ProxyRule.takesCondition(r.matchItem));

        // Changing what the rule looks at changes what it can be asked, and
        // whether it needs anything to compare against.
        item.addActionListener(e -> {
            String chosen = String.valueOf(item.getSelectedItem());
            rel.setModel(new DefaultComboBoxModel<>(ProxyRule.relationshipsFor(chosen, response)));
            condition.setEnabled(ProxyRule.takesCondition(chosen));
            if (!condition.isEnabled()) condition.setText("");
        });

        JPanel body = Ui.leftForm(
                "Boolean operator:", Ui.natural(op),
                "Match type:", Ui.natural(item),
                "Relationship:", Ui.natural(rel),
                "Condition:", condition);
        body.setBorder(Ui.pad(10, 12, 10, 12));

        int ok = JOptionPane.showConfirmDialog(parent, body,
                existing == null ? "Add interception rule" : "Edit interception rule",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return null;

        ProxyRule out = existing == null ? new ProxyRule() : existing;
        out.booleanOp = first ? "or" : String.valueOf(op.getSelectedItem());
        out.matchItem = String.valueOf(item.getSelectedItem());
        out.relationship = String.valueOf(rel.getSelectedItem());
        out.condition = condition.isEnabled() ? condition.getText().trim() : "";
        if (existing == null) out.enabled = true;
        return out;
    }
}
