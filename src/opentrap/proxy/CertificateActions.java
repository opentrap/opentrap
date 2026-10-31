package opentrap.proxy;

import opentrap.core.SuiteContext;

import javax.swing.*;
import java.awt.Component;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The CA certificate actions beneath the proxy listeners: hand the certificate
 * to another tool, adopt one from another installation, or start again with a
 * fresh authority.
 */
final class CertificateActions {

    private CertificateActions() {}

    /** Export the certificate for another tool, or import a store to replace this one. */
    static void importExport(Component parent, SuiteContext ctx, ProxyServer server) {
        String[] choices = {"Export certificate (DER)", "Export certificate (PEM)",
                            "Import a CA store", "Cancel"};
        int pick = JOptionPane.showOptionDialog(parent,
                "Export this installation's CA certificate so a browser or another tool can trust "
                + "it, or import a CA store exported from another installation.",
                "Import / export CA certificate", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, choices, choices[0]);
        CertificateAuthority ca = server == null ? null : server.ca();
        if (ca == null || pick < 0 || pick == 3) return;
        try {
            if (pick == 0 || pick == 1) {
                boolean der = pick == 0;
                JFileChooser fc = new JFileChooser();
                fc.setSelectedFile(new File(der ? "opentrap-ca.der" : "opentrap-ca.pem"));
                if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
                Path out = fc.getSelectedFile().toPath();
                if (der) Files.write(out, ca.caCertificateDer());
                else Files.writeString(out, ca.caCertificatePem());
                ctx.alert("proxy", "exported the CA certificate to " + out);
                JOptionPane.showMessageDialog(parent, "Written to " + out,
                        "Export CA certificate", JOptionPane.INFORMATION_MESSAGE);
            } else {
                JFileChooser fc = new JFileChooser();
                fc.setDialogTitle("Choose a CA store (ca.p12)");
                if (fc.showOpenDialog(parent) != JFileChooser.APPROVE_OPTION) return;
                Path in = fc.getSelectedFile().toPath();
                Files.copy(in, ca.storePath(), StandardCopyOption.REPLACE_EXISTING);
                ctx.alert("proxy", "imported a CA store from " + in);
                JOptionPane.showMessageDialog(parent,
                        "The CA store has been replaced. Restart OpenTRAP for listeners to use it.",
                        "Import CA store", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent, "Could not complete that: " + e.getMessage(),
                    "CA certificate", JOptionPane.WARNING_MESSAGE);
        }
    }

    /**
     * Throw the current authority away so a new one is generated. Every client
     * that trusted the old certificate stops trusting this proxy, which is why
     * it is confirmed first.
     */
    static void regenerate(Component parent, SuiteContext ctx, ProxyServer server) {
        int ok = JOptionPane.showConfirmDialog(parent,
                "Generate a new CA certificate?\n\n"
                + "Every browser and tool that trusts the current certificate will stop trusting "
                + "this proxy until it is given the new one.",
                "Regenerate CA certificate", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return;
        try {
            Files.deleteIfExists(server.ca().storePath());
            ctx.alert("proxy", "deleted the CA store; a new authority is generated at the next start");
            JOptionPane.showMessageDialog(parent,
                    "The CA store has been removed. A new certificate is generated when OpenTRAP "
                    + "next starts; trust it in your browser then.",
                    "Regenerate CA certificate", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent, "Could not remove the CA store: " + e.getMessage(),
                    "Regenerate CA certificate", JOptionPane.WARNING_MESSAGE);
        }
    }
}
