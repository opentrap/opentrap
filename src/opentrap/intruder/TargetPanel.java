package opentrap.intruder;

import opentrap.core.HttpService;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/**
 * The attack target as a single row under the attack type, as in the reference:
 * the target URL and whether the request's Host header should be rewritten to
 * match it. The URL is filled in automatically when a request is sent to
 * Intruder.
 */
public class TargetPanel extends JPanel {

    private final JTextField url = Ui.field("", 34);
    private final JCheckBox updateHost = Ui.check("Update Host header to match target", true);

    public TargetPanel() {
        super(new FlowLayout(FlowLayout.LEFT, 8, 2));
        setOpaque(false);
        url.setToolTipText("the server the attack is sent to, as a URL");
        url.putClientProperty("JTextField.placeholderText", "https://host");
        updateHost.setToolTipText("rewrite the request's Host header so it matches the target URL");
        add(Ui.bold("Target:"));
        add(url);
        add(updateHost);
    }

    public void set(HttpService svc) {
        if (svc == null) return;
        url.setText(svc.url());
    }

    /** Whether the Host header should be rewritten to match the target. */
    public boolean updateHostHeader() { return updateHost.isSelected(); }

    public HttpService service() {
        String s = url.getText().trim();
        boolean secure = s.toLowerCase().startsWith("https://");
        String rest = s.replaceFirst("(?i)^https?://", "");
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int port = secure ? 443 : 80;
        int colon = rest.lastIndexOf(':');
        if (colon > 0) {
            try { port = Integer.parseInt(rest.substring(colon + 1)); } catch (NumberFormatException ignored) {}
            rest = rest.substring(0, colon);
        }
        return new HttpService(rest, port, secure);
    }
}
