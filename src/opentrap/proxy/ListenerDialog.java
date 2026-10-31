package opentrap.proxy;

import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;

/**
 * Add or edit one proxy listener, over the five tabs the reference uses:
 * Binding, Request handling, Certificate, TLS Protocols and HTTP.
 *
 * <p>Returns the edited configuration, or null when the dialog is cancelled.
 * The listener handed in is never modified; a copy is returned, so a cancel
 * genuinely leaves the original alone.
 */
final class ListenerDialog {

    private ListenerDialog() {}

    static ProxyListener show(Component parent, ProxyListener existing, String title) {
        final ProxyListener l = existing == null ? new ProxyListener() : existing.copy();

        // ---- Binding -------------------------------------------------------
        JTextField port = Ui.field(String.valueOf(l.port), 8);
        JRadioButton loopback = Ui.radio("Loopback only", l.bind == ProxyListener.Bind.LOOPBACK);
        JRadioButton all = Ui.radio("All interfaces", l.bind == ProxyListener.Bind.ALL);
        JRadioButton specific = Ui.radio("Specific address:", l.bind == ProxyListener.Bind.SPECIFIC);
        ButtonGroup bindGroup = new ButtonGroup();
        bindGroup.add(loopback); bindGroup.add(all); bindGroup.add(specific);
        JComboBox<String> address = Ui.combo(LocalAddresses.all().toArray(new String[0]));
        if (l.bindAddress != null && !l.bindAddress.isBlank()) address.setSelectedItem(l.bindAddress);
        address.setEnabled(specific.isSelected());
        for (JRadioButton r : new JRadioButton[]{loopback, all, specific}) {
            r.addActionListener(e -> address.setEnabled(specific.isSelected()));
        }

        JPanel binding = tab("These settings control how OpenTRAP binds the proxy listener.",
                Ui.leftForm("Bind to port:", Ui.natural(port),
                        "Bind to address:", Ui.natural(loopback),
                        "", Ui.natural(all),
                        "", Ui.natural(Ui.row(specific, address))));

        // ---- Request handling ---------------------------------------------
        JTextField redirectHost = Ui.field(l.redirectHost == null ? "" : l.redirectHost, 22);
        JTextField redirectPort = Ui.field(l.redirectPort > 0 ? String.valueOf(l.redirectPort) : "", 8);
        JCheckBox forceTls = Ui.check("Force use of TLS", l.forceTls);
        JCheckBox invisible = Ui.check("Support invisible proxying (enable only if needed)", l.invisible);
        JPanel handlingBody = new JPanel();
        handlingBody.setLayout(new BoxLayout(handlingBody, BoxLayout.Y_AXIS));
        handlingBody.setOpaque(false);
        addLeft(handlingBody, Ui.leftForm("Redirect to host:", redirectHost,
                                          "Redirect to port:", Ui.natural(redirectPort)));
        addLeft(handlingBody, Box.createVerticalStrut(8));
        addLeft(handlingBody, forceTls);
        addLeft(handlingBody, Box.createVerticalStrut(14));
        JTextArea note = Ui.note("Invisible proxy support allows non-proxy-aware clients to connect "
                + "directly to the listener.");
        addLeft(handlingBody, note);
        addLeft(handlingBody, Box.createVerticalStrut(6));
        addLeft(handlingBody, invisible);
        JPanel handling = tab("These settings control whether OpenTRAP redirects requests received "
                + "by this listener.", handlingBody);

        // ---- Certificate ---------------------------------------------------
        JRadioButton selfSigned = Ui.radio("Use a self-signed certificate", l.cert == ProxyListener.Cert.SELF_SIGNED);
        JRadioButton perHost = Ui.radio("Generate CA-signed per-host certificates", l.cert == ProxyListener.Cert.PER_HOST);
        JRadioButton named = Ui.radio("Generate a CA-signed certificate with a specific hostname:",
                l.cert == ProxyListener.Cert.SPECIFIC_HOSTNAME);
        JRadioButton custom = Ui.radio("Use a custom certificate (PKCS#12):", l.cert == ProxyListener.Cert.CUSTOM);
        ButtonGroup certGroup = new ButtonGroup();
        certGroup.add(selfSigned); certGroup.add(perHost); certGroup.add(named); certGroup.add(custom);
        JTextField certHost = Ui.field(l.certHostname == null ? "" : l.certHostname, 28);
        JTextField certFile = Ui.field(l.certFile == null ? "" : l.certFile, 24);
        JPasswordField certPass = new JPasswordField(l.certPassword == null ? "" : l.certPassword, 20);
        certPass.setFont(Ui.PLAIN);
        JButton pick = Ui.button("Select file ...", "choose a PKCS#12 file");
        pick.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(pick) == JFileChooser.APPROVE_OPTION) {
                certFile.setText(fc.getSelectedFile().getAbsolutePath());
            }
        });
        Runnable certEnable = () -> {
            certHost.setEnabled(named.isSelected());
            certFile.setEnabled(custom.isSelected());
            certPass.setEnabled(custom.isSelected());
            pick.setEnabled(custom.isSelected());
        };
        for (JRadioButton r : new JRadioButton[]{selfSigned, perHost, named, custom}) {
            r.addActionListener(e -> certEnable.run());
        }
        certEnable.run();
        JPanel certBody = new JPanel();
        certBody.setLayout(new BoxLayout(certBody, BoxLayout.Y_AXIS));
        certBody.setOpaque(false);
        addLeft(certBody, selfSigned);
        addLeft(certBody, perHost);
        addLeft(certBody, named);
        addLeft(certBody, indent(certHost));
        addLeft(certBody, Box.createVerticalStrut(6));
        addLeft(certBody, custom);
        addLeft(certBody, indent(Ui.leftForm("File:", Ui.natural(Ui.row(certFile, pick)),
                                             "Password:", Ui.natural(certPass))));
        JPanel certificate = tab("These settings control the server TLS certificate that is "
                + "presented to TLS clients.", certBody);

        // ---- TLS Protocols -------------------------------------------------
        boolean customProtocols = l.tlsProtocols != null && !l.tlsProtocols.isBlank();
        JRadioButton defaultProtocols = Ui.radio("Use the default protocols of your Java installation", !customProtocols);
        JRadioButton pickProtocols = Ui.radio("Use custom protocols", customProtocols);
        ButtonGroup tlsGroup = new ButtonGroup();
        tlsGroup.add(defaultProtocols); tlsGroup.add(pickProtocols);
        JTextField protocols = Ui.field(customProtocols ? l.tlsProtocols : "TLSv1.2, TLSv1.3", 28);
        protocols.setEnabled(customProtocols);
        for (JRadioButton r : new JRadioButton[]{defaultProtocols, pickProtocols}) {
            r.addActionListener(e -> protocols.setEnabled(pickProtocols.isSelected()));
        }
        JPanel tlsBody = new JPanel();
        tlsBody.setLayout(new BoxLayout(tlsBody, BoxLayout.Y_AXIS));
        tlsBody.setOpaque(false);
        addLeft(tlsBody, defaultProtocols);
        addLeft(tlsBody, pickProtocols);
        addLeft(tlsBody, indent(protocols));
        JPanel tlsTab = tab("These settings control the TLS protocols that this proxy listener will "
                + "use when performing TLS negotiation with the browser. If you are experiencing "
                + "problems with TLS negotiation, you can use these settings to request use of "
                + "specific protocols.", tlsBody);

        // ---- HTTP ----------------------------------------------------------
        JCheckBox http2 = Ui.check("Support HTTP/2", l.http2);
        JPanel httpBody = new JPanel();
        httpBody.setLayout(new BoxLayout(httpBody, BoxLayout.Y_AXIS));
        httpBody.setOpaque(false);
        addLeft(httpBody, http2);
        JPanel httpTab = tab("This setting enables use of the HTTP/2 protocol by the listener.", httpBody);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setFont(Ui.PLAIN);
        tabs.addTab("Binding", binding);
        tabs.addTab("Request handling", handling);
        tabs.addTab("Certificate", certificate);
        tabs.addTab("TLS Protocols", tlsTab);
        tabs.addTab("HTTP", httpTab);
        tabs.setPreferredSize(new Dimension(700, 430));

        int ok = JOptionPane.showConfirmDialog(parent, tabs, title,
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return null;

        try { l.port = Integer.parseInt(port.getText().trim()); }
        catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(parent, "The port must be a number.", title,
                    JOptionPane.WARNING_MESSAGE);
            return null;
        }
        l.bind = loopback.isSelected() ? ProxyListener.Bind.LOOPBACK
               : all.isSelected() ? ProxyListener.Bind.ALL : ProxyListener.Bind.SPECIFIC;
        l.bindAddress = String.valueOf(address.getSelectedItem());
        l.redirectHost = redirectHost.getText().trim();
        try { l.redirectPort = Integer.parseInt(redirectPort.getText().trim()); }
        catch (NumberFormatException e) { l.redirectPort = 0; }
        l.forceTls = forceTls.isSelected();
        l.invisible = invisible.isSelected();
        l.cert = selfSigned.isSelected() ? ProxyListener.Cert.SELF_SIGNED
               : named.isSelected() ? ProxyListener.Cert.SPECIFIC_HOSTNAME
               : custom.isSelected() ? ProxyListener.Cert.CUSTOM : ProxyListener.Cert.PER_HOST;
        l.certHostname = certHost.getText().trim();
        l.certFile = certFile.getText().trim();
        l.certPassword = new String(certPass.getPassword());
        l.tlsProtocols = pickProtocols.isSelected() ? protocols.getText().trim() : "";
        l.http2 = http2.isSelected();
        return l;
    }

    /** One tab: a help mark and its sentence, then the controls. */
    private static JPanel tab(String description, JComponent body) {
        JPanel p = new JPanel(new BorderLayout(0, 12));
        p.setOpaque(false);
        p.setBorder(Ui.pad(14, 16, 14, 16));
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        head.add(new JLabel(opentrap.ui.Glyphs.help(18, opentrap.ui.Theme.ICON)), BorderLayout.WEST);
        JTextArea d = Ui.note(description);
        Ui.sizeNote(d, 560);
        head.add(d, BorderLayout.CENTER);
        p.add(head, BorderLayout.NORTH);
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.setOpaque(false);
        wrap.setBorder(Ui.pad(0, 28, 0, 0));
        wrap.add(body, BorderLayout.NORTH);
        p.add(wrap, BorderLayout.CENTER);
        return p;
    }

    private static void addLeft(JPanel column, Component c) {
        if (c instanceof JComponent jc) jc.setAlignmentX(Component.LEFT_ALIGNMENT);
        column.add(c);
    }

    /** A control that belongs to the radio button above it. */
    private static JComponent indent(JComponent c) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        p.setOpaque(false);
        p.setBorder(Ui.pad(0, 22, 0, 0));
        p.add(c);
        return p;
    }
}
