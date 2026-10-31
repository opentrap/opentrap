package opentrap.ui;

import opentrap.core.Scope;

import javax.swing.*;
import java.awt.*;

/**
 * "Add URL to include in scope" / "Add URL to exclude from scope": an advanced
 * scope rule as protocol, host or IP range, port and file, each a regular
 * expression or blank to match anything. Paste URL fills the fields from a
 * URL on the clipboard. Returns the rule, or null when cancelled.
 */
public final class AddScopeUrlDialog {

    private AddScopeUrlDialog() {}

    public static Scope.Rule show(Component parent, boolean include, Scope.Rule editing) {
        String title = (editing == null ? "Add" : "Edit") + " URL to " + (include ? "include in" : "exclude from") + " scope";
        Window owner = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        final JDialog d = new JDialog(owner, title, Dialog.ModalityType.APPLICATION_MODAL);
        final Scope.Rule[] out = {null};

        JComboBox<String> protocol = Ui.combo(new String[]{"Any", "HTTP", "HTTPS"});
        JTextField host = Ui.field(editing == null ? "" : editing.host, 36);
        JTextField port = Ui.field(editing == null ? "" : editing.port, 36);
        JTextField file = Ui.field(editing == null ? "" : editing.file, 36);
        host.putClientProperty("JTextField.placeholderText", "Enter regex, IP range or leave blank");
        port.putClientProperty("JTextField.placeholderText", "Enter regex or leave blank");
        file.putClientProperty("JTextField.placeholderText", "Enter regex or leave blank");
        if (editing != null) protocol.setSelectedItem(switch (editing.protocol == null ? "any" : editing.protocol.toLowerCase()) {
            case "http" -> "HTTP"; case "https" -> "HTTPS"; default -> "Any"; });

        JPanel body = new JPanel();
        body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        body.setBorder(Ui.pad(14, 18, 8, 18));
        JPanel lead = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        lead.setOpaque(false);
        lead.add(new JLabel(Glyphs.help(16, Theme.MUTED)));
        JTextArea note = Ui.note("Specify a regular expression to match each URL component, or leave blank to match any item. "
                + "An IP range can be specified instead of a hostname.");
        Ui.sizeNote(note, 480);
        lead.add(note);
        lead.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(lead);
        body.add(Box.createVerticalStrut(10));
        JPanel form = Ui.form("Protocol:", protocol, "Host or IP range:", host, "Port:", port, "File:", file);
        form.setAlignmentX(Component.LEFT_ALIGNMENT);
        body.add(form);

        JButton paste = Ui.button("Paste URL", "fill the fields from a URL on the clipboard");
        paste.addActionListener(e -> {
            try {
                Object t = Toolkit.getDefaultToolkit().getSystemClipboard().getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                if (t == null) return;
                java.net.URI u = java.net.URI.create(String.valueOf(t).trim());
                if (u.getScheme() != null) protocol.setSelectedItem("https".equalsIgnoreCase(u.getScheme()) ? "HTTPS" : "HTTP");
                if (u.getHost() != null) host.setText("^" + java.util.regex.Pattern.quote(u.getHost()).replace("\\Q", "").replace("\\E", "").replace(".", "\\.") + "$");
                if (u.getPort() > 0) port.setText("^" + u.getPort() + "$");
                if (u.getRawPath() != null && !u.getRawPath().isEmpty()) file.setText("^" + u.getRawPath().replace(".", "\\.") + ".*");
            } catch (Exception ignore) {
                // not a URL on the clipboard: leave the fields alone
            }
        });
        JButton ok = Ui.primaryButton("OK", "add the rule");
        JButton cancel = Ui.button("Cancel");
        ok.addActionListener(e -> {
            Scope.Rule r = editing == null ? new Scope.Rule() : editing;
            r.protocol = String.valueOf(protocol.getSelectedItem()).toLowerCase();
            r.host = host.getText().strip();
            r.port = port.getText().strip();
            r.file = file.getText().strip();
            r.prefix = null;
            if (editing == null) r.enabled = true;
            out[0] = r;
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(Ui.pad(4, 18, 10, 18));
        bar.add(paste, BorderLayout.WEST);
        JPanel okc = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        okc.add(ok); okc.add(cancel);
        bar.add(okc, BorderLayout.EAST);

        JPanel root = new JPanel(new BorderLayout());
        root.add(body, BorderLayout.CENTER);
        root.add(bar, BorderLayout.SOUTH);
        d.setContentPane(root);
        d.pack();
        d.setMinimumSize(new Dimension(560, d.getHeight()));
        d.setLocationRelativeTo(parent);
        d.setVisible(true);
        return out[0];
    }
}
