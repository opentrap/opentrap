package opentrap.ui;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.Issue;
import opentrap.core.SuiteContext;

import javax.swing.*;
import java.awt.*;

/** A small dialog for noting a finding by hand, attached to the chosen request. */
public final class ManualIssue {
    private ManualIssue() {}

    public static void record(Component parent, SuiteContext ctx, HttpService svc,
                              HttpMessage req, HttpMessage resp) {
        JTextField title = new JTextField(30);
        JComboBox<Issue.Severity> sev = new JComboBox<>(Issue.Severity.values());
        sev.setSelectedItem(Issue.Severity.MEDIUM);
        JComboBox<Issue.Confidence> conf = new JComboBox<>(Issue.Confidence.values());
        conf.setSelectedItem(Issue.Confidence.FIRM);
        JTextArea detail = new JTextArea(6, 30);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);

        JPanel p = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int row = 0;
        addRow(p, c, row++, "Title", title);
        addRow(p, c, row++, "Severity", sev);
        addRow(p, c, row++, "Confidence", conf);
        c.gridx = 0; c.gridy = row; c.anchor = GridBagConstraints.NORTHWEST;
        p.add(Ui.label("Detail"), c);
        c.gridx = 1; c.gridy = row; c.weightx = 1;
        p.add(new JScrollPane(detail), c);

        int r = JOptionPane.showConfirmDialog(parent, p, "Record an issue",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (r != JOptionPane.OK_OPTION) return;
        String t = title.getText().trim();
        if (t.isEmpty()) return;

        Issue.Builder b = Issue.builder(t)
                .severity((Issue.Severity) sev.getSelectedItem())
                .confidence((Issue.Confidence) conf.getSelectedItem())
                .service(svc)
                .detail(detail.getText().trim())
                .dedupe(Issue.Dedupe.PATH);
        if (req != null) { b.path(req.path()); b.request(req.copy()); }
        if (resp != null) b.response(resp.copy());
        Issue issue = b.build();
        issue.setSource("Manual");
        ctx.addIssue(issue);
    }

    private static void addRow(JPanel p, GridBagConstraints c, int row, String label, JComponent field) {
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        p.add(Ui.label(label), c);
        c.gridx = 1; c.gridy = row; c.weightx = 1;
        p.add(field, c);
    }
}
