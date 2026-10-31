package opentrap.ui;

import opentrap.core.Debug;
import opentrap.core.SuiteContext;

import javax.swing.*;
import java.awt.*;

/** Suite-wide communication and display options. */
public class SuiteOptionsPanel extends JPanel {

    public SuiteOptionsPanel(SuiteContext ctx) {
        super(new BorderLayout());
        OptionsPage page = new OptionsPage();

        JTextField connect = Ui.field(String.valueOf(ctx.settings().getInt("timeout.connect", 15)), 6);
        JTextField read    = Ui.field(String.valueOf(ctx.settings().getInt("timeout.read", 30)), 6);
        connect.addActionListener(e -> ctx.settings().setInt("timeout.connect", parse(connect, 15)));
        read.addActionListener(e -> ctx.settings().setInt("timeout.read", parse(read, 30)));
        page.section("timeouts (seconds)",
                OptionsPage.fieldRow("connect", connect, ""),
                OptionsPage.fieldRow("read", read, ""));

        JCheckBox unpack = Ui.check("unpack gzip / deflate in responses",
                ctx.settings().getBool("http.unpackGzip", true));
        unpack.addActionListener(e -> ctx.settings().setBool("http.unpackGzip", unpack.isSelected()));
        JCheckBox allowRemote = Ui.check("allow the renderer to make HTTP requests (for images, etc.)",
                ctx.settings().getBool("render.allowHttp", false));
        allowRemote.addActionListener(e -> ctx.settings().setBool("render.allowHttp", allowRemote.isSelected()));
        page.section("HTTP", unpack, allowRemote);

        // Display scale control.
        String[] scales = {"100%", "125%", "150%", "200%"};
        JComboBox<String> scaleCombo = Ui.combo(scales);
        int cur = (int) Math.round(UiScale.desired() * 100);
        scaleCombo.setSelectedItem(cur + "%");
        scaleCombo.addActionListener(e -> {
            String v = String.valueOf(scaleCombo.getSelectedItem()).replace("%", "");
            try {
                StateIO.saveBeforeRestart(ctx);
                UiScale.changeTo(Double.parseDouble(v) / 100.0, Main.launchArgs);
            }
            catch (Exception ex) { Debug.log("scale change: " + ex); }
        });
        JTextArea scaleNote = Ui.note(
            "Scales the whole interface using real HiDPI rendering, so fonts, columns and "
            + "borders all grow together and stay crisp. Changing it restarts the app. Use "
            + "this if the app looks tiny on a high-density screen, or too large for a small "
            + "one. You can also launch with -Dopentrap.scale=2 (or 1.5, etc.).");
        scaleNote.setPreferredSize(new java.awt.Dimension(600, 60));

        // Theme control: like scale, a change relaunches so the whole interface
        // (including the colours the Ui constants cache) comes up consistent.
        JComboBox<String> themeCombo = Ui.combo(new String[]{"light", "dark"});
        themeCombo.setSelectedItem(Theme.darkPreferred() ? "dark" : "light");
        themeCombo.addActionListener(e -> {
            boolean wantDark = "dark".equals(themeCombo.getSelectedItem());
            if (wantDark == Theme.dark) return;
            ctx.settings().set("ui.theme", wantDark ? "dark" : "light");
            StateIO.saveBeforeRestart(ctx);
            if (UiScale.relaunchWith(UiScale.desired(), Main.launchArgs)) System.exit(0);
        });
        JTextArea themeNote = Ui.note("Switches between the light and dark palette. "
                + "Changing it restarts the app so every panel comes up in the new theme.");
        themeNote.setMaximumSize(new java.awt.Dimension(600, themeNote.getPreferredSize().height));

        page.section("display",
                OptionsPage.labelRow("interface scale", scaleCombo),
                scaleNote,
                OptionsPage.labelRow("theme", themeCombo),
                themeNote);

        JTextArea about = Ui.note(
            "OpenTRAP is an HTTP interception and testing suite.\n\n" +
            "To use the proxy: set your browser to use 127.0.0.1 port " + opentrap.proxy.ProxyListener.DEFAULT_PORT + " for HTTP and HTTPS, "
            + "then browse to http://opentrap/ and install the certificate authority so that "
            + "TLS traffic can be inspected.");
        about.setPreferredSize(new Dimension(600, 90));
        page.section("about", about);

        add(page, BorderLayout.CENTER);
    }

    private static int parse(JTextField f, int def) {
        try { return Integer.parseInt(f.getText().trim()); } catch (Exception e) { return def; }
    }
}
