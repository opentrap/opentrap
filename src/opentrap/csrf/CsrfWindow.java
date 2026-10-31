package opentrap.csrf;

import opentrap.core.*;
import opentrap.ui.*;

import javax.swing.*;
import java.awt.*;

/**
 * The CSRF proof-of-concept engagement tool: a popup window opened from the
 * right-click "send to CSRF PoC" action, seeded with the chosen request. It
 * generates a self-submitting HTML page that reproduces the request cross-site.
 */
public class CsrfWindow extends JFrame {

    private final SuiteContext ctx;
    private final MessageEditor reqEditor;
    private final JTextArea output = new JTextArea();
    private final JTextField hostField = Ui.field("", 16);
    private final JTextField portField = Ui.field("443", 5);
    private final JCheckBox sslBox = Ui.check("SSL", true);

    public CsrfWindow(SuiteContext ctx, HttpService svc, HttpMessage req) {
        super("CSRF proof of concept");
        this.ctx = ctx;
        reqEditor = new MessageEditor(ctx, MessageEditor.REQUEST, true);
        if (svc != null) { hostField.setText(svc.host()); portField.setText(String.valueOf(svc.port())); sslBox.setSelected(svc.secure()); }
        reqEditor.setMessage(svc, req == null ? new HttpMessage(new byte[0]) : req.copy());
        output.setFont(Ui.MONO);

        JButton gen = Ui.primaryButton("generate", "build the CSRF proof of concept");
        JButton save = Ui.button("save...");
        JButton copy = Ui.button("copy");
        JButton open = Ui.button("open in browser", "write to a temp file and open it");
        gen.addActionListener(e -> generate());
        save.addActionListener(e -> save());
        copy.addActionListener(e -> copy());
        open.addActionListener(e -> openInBrowser());

        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
        north.add(gen); north.add(save); north.add(copy); north.add(open);
        north.add(Ui.label("host")); north.add(hostField);
        north.add(Ui.label("port")); north.add(portField);
        north.add(sslBox);

        JPanel reqPanel = new JPanel(new BorderLayout());
        reqPanel.add(Ui.bold(" request"), BorderLayout.NORTH);
        reqPanel.add(reqEditor, BorderLayout.CENTER);
        JPanel outPanel = new JPanel(new BorderLayout());
        outPanel.add(Ui.bold(" CSRF proof of concept"), BorderLayout.NORTH);
        outPanel.add(Ui.scroll(output), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, reqPanel, outPanel);
        split.setDividerSize(5);
        Ui.balance(split, 0.5);

        setLayout(new BorderLayout());
        add(north, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        // Dispose on close: a hidden-but-undisposed frame is retained by AWT
        // for the rest of the session, dragging its whole component tree and
        // the request copy in the editor with it.
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setSize(820, 560);
        setLocationRelativeTo(ctx.frame());
        generate();
    }

    private HttpService svc() {
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); } catch (Exception e) { port = sslBox.isSelected() ? 443 : 80; }
        return new HttpService(hostField.getText().trim(), port, sslBox.isSelected());
    }

    private void generate() {
        output.setText(CsrfPoc.generate(svc(), new HttpMessage(reqEditor.bytes())));
        output.setCaretPosition(0);
    }

    private void save() {
        JFileChooser fc = new JFileChooser();
        fc.setSelectedFile(new java.io.File("csrf-poc.html"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try (java.io.PrintWriter w = new java.io.PrintWriter(fc.getSelectedFile(), java.nio.charset.StandardCharsets.UTF_8)) {
            w.print(output.getText());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "save failed: " + ex.getMessage());
        }
    }

    private void copy() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(output.getText()), null);
        ctx.alert("CSRF PoC", "copied to clipboard");
    }

    private void openInBrowser() {
        try {
            java.io.File f = java.io.File.createTempFile("opentrap-csrf", ".html");
            f.deleteOnExit();   // the browser keeps the file open; clean up at exit
            try (java.io.PrintWriter w = new java.io.PrintWriter(f, java.nio.charset.StandardCharsets.UTF_8)) { w.print(output.getText()); }
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(f.toURI());
            } else {
                ctx.alert("CSRF PoC", "saved to " + f.getAbsolutePath() + " (open it manually)");
            }
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "could not open: " + ex.getMessage());
        }
    }
}
