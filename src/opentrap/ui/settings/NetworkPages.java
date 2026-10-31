package opentrap.ui.settings;

import opentrap.core.Settings;
import opentrap.core.SuiteContext;
import opentrap.ui.OptionsPage;
import opentrap.ui.Ui;

import javax.swing.*;

/** Settings > Network pages: Connections, DNS, TLS, HTTP. */
public final class NetworkPages {
    private NetworkPages() {}

    public static SettingsPage connections(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("Platform authentication", SettingsPage.Kind.BOTH,
                "Credentials the suite presents to servers that ask for HTTP authentication (Basic, NTLM, Digest), per destination host.",
                () -> { s.setBool("auth.platform", true); s.set("auth.platform.rows", ""); s.setBool("auth.prompt", false); },
                SettingsPage.overrideRow(s, "auth.overrideProject"),
                SettingsPage.check(s, "auth.platform", true, "Do platform authentication"),
                new KeyedTable(s, "auth.platform.rows", new String[]{"Enabled", "Destination host", "Type", "Username", "Password", "Domain", "Domain hostname"}, 120),
                SettingsPage.check(s, "auth.prompt", false, "Prompt for credentials on platform authentication failure"));
        p.block("Timeouts", SettingsPage.Kind.PROJECT,
                "How long the tools wait, in seconds, before giving up on a connection or a response. 0 or blank means never time out.",
                () -> { s.setInt("timeout.connect", 15); s.setInt("timeout.read", 30); s.setInt("timeout.openEnded", 10); s.setInt("timeout.dns", 300); s.setInt("timeout.dnsFailed", 60); },
                OptionsPage.fieldRow("Connect:", SettingsPage.number(s, "timeout.connect", 15, 6), ""),
                OptionsPage.fieldRow("Normal:", SettingsPage.number(s, "timeout.read", 30, 6), ""),
                OptionsPage.fieldRow("Open-ended responses:", SettingsPage.number(s, "timeout.openEnded", 10, 6), ""),
                OptionsPage.fieldRow("Domain name resolution:", SettingsPage.number(s, "timeout.dns", 300, 6), ""),
                OptionsPage.fieldRow("Failed domain name resolution:", SettingsPage.number(s, "timeout.dnsFailed", 60, 6), ""));
        p.block("Upstream proxy servers", SettingsPage.Kind.BOTH,
                "Send requests for particular destination hosts through another proxy, with authentication if it needs it.",
                null,
                SettingsPage.overrideRow(s, "upstream.overrideProject"),
                new opentrap.proxy.UpstreamProxyPanel(ctx));
        JCheckBox useSocks = SettingsPage.check(s, "socks.enabled", false, "Use SOCKS proxy");
        JLabel socksStatus = Ui.label(s.get("socks.host", "").isEmpty() ? "No proxy configured." : "SOCKS proxy: " + s.get("socks.host", "") + ":" + s.get("socks.port", "1080"));
        JButton configure = Ui.button("Configure", "set the SOCKS proxy host, port and credentials");
        configure.addActionListener(e -> {
            JTextField host = Ui.field(s.get("socks.host", ""), 24), port = Ui.field(s.get("socks.port", "1080"), 6), user = Ui.field(s.get("socks.user", ""), 24);
            JPasswordField pass = new JPasswordField(s.get("socks.password", ""), 24);
            pass.setFont(Ui.PLAIN);
            JCheckBox dns = Ui.check("Do DNS lookups over SOCKS proxy", s.getBool("socks.dns", true));
            JPanel form = Ui.vbox(Ui.form("Host:", host, "Port:", port, "Username:", user, "Password:", pass), dns);
            form.setBorder(Ui.pad(10, 10, 10, 10));
            if (JOptionPane.showConfirmDialog(configure, form, "SOCKS proxy", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            s.set("socks.host", host.getText().strip()); s.set("socks.port", port.getText().strip()); s.set("socks.user", user.getText().strip());
            s.set("socks.password", new String(pass.getPassword())); s.setBool("socks.dns", dns.isSelected());
            socksStatus.setText(host.getText().isBlank() ? "No proxy configured." : "SOCKS proxy: " + host.getText().strip() + ":" + port.getText().strip());
        });
        p.block("SOCKS proxy", SettingsPage.Kind.BOTH,
                "Route all outgoing connections through a SOCKS proxy. Hosts listed below bypass it.",
                () -> SettingsPage.clearPrefix(s, "socks."),
                SettingsPage.overrideRow(s, "socks.overrideProject"),
                useSocks, Ui.row(socksStatus, configure),
                Ui.note("If you want certain hosts to bypass the SOCKS proxy, specify them here."),
                new KeyedTable(s, "socks.bypass", new String[]{"Host"}, 90, "Add", "Remove", "Paste URL", "Load ..."));
        return p;
    }

    public static SettingsPage dns(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JRadioButton[] r = SettingsPage.radios(s, "dns.mode", "system", new String[]{"system", "ipv4", "ipv6"},
                "Use system's default behavior", "Prefer IPv4", "Prefer IPv6");
        JButton clear = Ui.button("Clear DNS cache", "forget every cached name lookup");
        clear.addActionListener(e -> {
            java.security.Security.setProperty("networkaddress.cache.ttl", "0");
            JOptionPane.showMessageDialog(clear, "The DNS cache has been cleared.", "DNS", JOptionPane.INFORMATION_MESSAGE);
        });
        p.block("DNS resolution", SettingsPage.Kind.PROJECT,
                "Which address family the tools prefer when a name resolves to both IPv4 and IPv6 addresses.",
                () -> s.set("dns.mode", "system"), r[0], r[1], r[2], clear);
        p.block("Hostname resolution overrides", SettingsPage.Kind.PROJECT,
                "Resolve these hostnames to fixed IP addresses instead of asking DNS, as a hosts file would.",
                null, new KeyedTable(s, "dns.overrides", new String[]{"Enabled", "Hostname", "IP address"}, 110));
        return p;
    }

    public static SettingsPage tls(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        JRadioButton[] r = SettingsPage.radios(s, "tls.protocols", "all", new String[]{"all", "default", "custom"},
                "Use all supported protocols and ciphers of your Java installation",
                "Use the default protocols and ciphers of your Java installation",
                "Use custom protocols and ciphers");
        p.block("TLS negotiation", SettingsPage.Kind.PROJECT,
                "How the tools negotiate TLS with servers, and whether server certificates are checked.",
                () -> SettingsPage.clearPrefix(s, "tls."),
                Ui.bold("TLS trust"),
                SettingsPage.check(s, "tls.verifyUpstream", false, "Verify upstream TLS"),
                Ui.bold("Protocols and ciphers"), r[0], r[1], r[2],
                Ui.bold("TLS negotiation workarounds"),
                SettingsPage.check(s, "tls.unsafeRenegotiation", false, "Allow unsafe renegotiation (required for some client certificates)"),
                SettingsPage.check(s, "tls.noSessionResume", false, "Disable TLS session resume"));
        p.block("Client TLS certificates", SettingsPage.Kind.BOTH,
                "Certificates the tools present to servers that ask for client authentication, per host.",
                null,
                SettingsPage.overrideRow(s, "tls.client.overrideProject"),
                new KeyedTable(s, "tls.client.rows", new String[]{"Enabled", "Host", "Type", "Alias", "Subject", "Issuer", "Intermediate CA", "Key"}, 110, "Add", "Remove", "Up", "Down"));
        p.block("Server TLS certificates", SettingsPage.Kind.PROJECT,
                "The certificates servers have presented during this project. Double-click one to see its details.",
                null, new KeyedTable(s, "tls.server.rows", new String[]{"Host", "Name", "Issuer"}, 90, "Remove"));
        p.block("Custom CA certificates", SettingsPage.Kind.USER,
                "Additional certificate authorities to trust when verifying upstream TLS.",
                null, new KeyedTable(s, "tls.ca.rows", new String[]{"Name"}, 80, "Add", "Remove"));
        p.block("Java TLS settings", SettingsPage.Kind.USER,
                "Low-level settings of the Java runtime's TLS stack. Both need a restart to take effect.",
                () -> { s.setBool("tls.java.unblockAlgorithms", true); s.setBool("tls.java.disableSni", false); },
                SettingsPage.check(s, "tls.java.unblockAlgorithms", true, "Enable algorithms blocked by Java security policy (requires restart)"),
                SettingsPage.check(s, "tls.java.disableSni", false, "Disable Java SNI extension (requires restart)"));
        return p;
    }

    public static SettingsPage http(SuiteContext ctx) {
        Settings s = ctx.settings();
        SettingsPage p = new SettingsPage();
        p.block("Allowed redirect types", SettingsPage.Kind.PROJECT,
                "Which kinds of redirection the tools follow when a task is set to follow redirections.",
                () -> SettingsPage.clearPrefix(s, "http.redirect."),
                SettingsPage.check(s, "http.redirect.3xx", true, "3xx status code with Location header"),
                SettingsPage.check(s, "http.redirect.refreshHeader", true, "Refresh header"),
                SettingsPage.check(s, "http.redirect.metaRefresh", true, "Meta refresh tag"),
                SettingsPage.check(s, "http.redirect.javascript", false, "JavaScript-driven"),
                SettingsPage.check(s, "http.redirect.anyLocation", false, "Any status code with Location header"));
        p.block("Streaming responses", SettingsPage.Kind.PROJECT,
                "Some responses never end (server-sent events, long polling). Responses from these URLs are shown as they arrive "
              + "rather than waited for, and the tools do not try to process them whole.",
                () -> SettingsPage.clearPrefix(s, "http.streaming."),
                SettingsPage.check(s, "http.streaming.advanced", false, "Use advanced scope control"),
                new KeyedTable(s, "http.streaming.prefixes", new String[]{"Enabled", "Prefix", "Include subdomains"}, 90, "Add", "Edit", "Remove", "Paste URL", "Load ..."),
                SettingsPage.check(s, "http.streaming.store", true, "Store streaming responses (may result in large temp files)"),
                SettingsPage.check(s, "http.streaming.stripChunked", true, "Strip chunked encoding metadata in streaming responses"),
                SettingsPage.check(s, "http.streaming.eventStream", true, "Treat responses with the text/event-stream MIME type as streaming"));
        p.block("Status 100 response handling", SettingsPage.Kind.PROJECT,
                "How interim 100 Continue responses are treated.",
                () -> { s.setBool("http.100.understand", true); s.setBool("http.100.remove", false); },
                SettingsPage.check(s, "http.100.understand", true, "Understand 100 Continue responses"),
                SettingsPage.check(s, "http.100.remove", false, "Remove 100 Continue headers"));
        p.block("HTTP/1", SettingsPage.Kind.PROJECT, "Connection reuse for HTTP/1.",
                () -> s.setBool("http.keepAlive", false),
                SettingsPage.check(s, "http.keepAlive", false, "Use keep-alive for HTTP/1 if the server supports it"));
        p.block("HTTP/2", SettingsPage.Kind.PROJECT, "Whether the tools speak HTTP/2 to servers that offer it over TLS.",
                () -> s.setBool("http.http2", true),
                SettingsPage.check(s, "http.http2", true, "Default to HTTP/2 if the server supports it"));
        p.block("Response handling", SettingsPage.Kind.PROJECT, "What the tools do with response bodies and rendered pages.",
                () -> { s.setBool("http.unpackGzip", true); s.setBool("render.allowHttp", false); },
                SettingsPage.check(s, "http.unpackGzip", true, "Unpack gzip / deflate in responses"),
                SettingsPage.check(s, "render.allowHttp", false, "Allow the renderer to make HTTP requests (for images, etc.)"));
        return p;
    }
}
