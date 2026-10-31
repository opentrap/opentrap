package opentrap.dashboard;

import opentrap.spider.Cdp.JsonValue;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.dnd.DnDConstants;
import java.awt.dnd.DropTarget;
import java.awt.dnd.DropTargetDropEvent;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * API-only scan, step one: upload an API definition (OpenAPI / Swagger JSON,
 * or a SOAP WSDL) from a URL, with optional auth, or from a file (drag and
 * drop or a chooser). The definition is parsed into endpoints the API details
 * step lists and the scan seeds from.
 */
public final class ApiDefinitionPanel extends JPanel {

    public record Endpoint(String method, String path, String summary) {}
    public record Auth(String type, String name, String value) {}

    private final JTextField url = Ui.field("", 40);
    private final JLabel fileLabel = Ui.label("Drag and drop file");
    private final JLabel status = Ui.label("");
    private final List<Endpoint> endpoints = new ArrayList<>();
    private String source = "";
    private String baseUrl = "";
    private Auth auth;
    private Runnable onChange;

    public ApiDefinitionPanel() {
        super(new BorderLayout());
        setOpaque(false);
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);

        add(col, Ui.note("Upload an API definition (OpenAPI or SOAP WSDL) from a URL or file."));
        add(col, Box.createVerticalStrut(14));
        add(col, Ui.bold("Upload from URL"));
        JButton addAuth = Ui.button("Add Auth", "credentials used to fetch the definition");
        JButton upload = Ui.primaryButton("Upload", "fetch and parse the definition");
        addAuth.addActionListener(e -> {
            Auth a = AddAuthDialog.show(this, auth);
            if (a != null) { auth = a; addAuth.setText(a.type().equals("None") ? "Add Auth" : "Auth: " + a.type()); }
        });
        upload.addActionListener(e -> fetch());
        add(col, Ui.row(url, addAuth, upload));
        add(col, Box.createVerticalStrut(10));
        JLabel or = Ui.label("Or");
        or.setForeground(Theme.MUTED);
        add(col, or);
        add(col, Box.createVerticalStrut(10));
        add(col, Ui.bold("Upload from file"));

        JPanel drop = new JPanel(new GridBagLayout());
        drop.setBackground(Theme.SURFACE);
        drop.setBorder(BorderFactory.createDashedBorder(Theme.BORDER, 6, 4));
        drop.setPreferredSize(new Dimension(520, 120));
        drop.setMaximumSize(new Dimension(520, 120));
        fileLabel.setForeground(Theme.MUTED);
        drop.add(fileLabel, new GridBagConstraints());
        drop.setDropTarget(new DropTarget() {
            @Override public synchronized void drop(DropTargetDropEvent e) {
                try {
                    e.acceptDrop(DnDConstants.ACTION_COPY);
                    Object data = e.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    if (data instanceof List<?> files && !files.isEmpty() && files.get(0) instanceof File f) loadFile(f);
                } catch (Exception ex) { status.setText("Could not read the dropped file: " + ex.getMessage()); }
            }
        });
        add(col, drop);
        add(col, Box.createVerticalStrut(8));
        JButton pick = Ui.button("Upload file", "choose a definition file");
        pick.addActionListener(e -> {
            JFileChooser fc = new JFileChooser();
            if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) loadFile(fc.getSelectedFile());
        });
        add(col, pick);
        add(col, Box.createVerticalStrut(10));
        status.setForeground(Theme.MUTED);
        add(col, status);
        add(col, BorderLayout.NORTH);
    }

    private static void add(JPanel col, Component c) {
        if (c instanceof JComponent jc) {
            jc.setAlignmentX(Component.LEFT_ALIGNMENT);
            if (jc instanceof JTextArea ta) Ui.sizeNote(ta, 560);
        }
        col.add(c);
    }

    public void onChange(Runnable r) { this.onChange = r; }
    public List<Endpoint> endpoints() { return new ArrayList<>(endpoints); }
    public String source() { return source; }
    public String baseUrl() { return baseUrl; }
    public Auth auth() { return auth; }

    /** Seed URLs for the scan: one per endpoint, under the definition's base URL. */
    public List<String> seedUrls() {
        List<String> out = new ArrayList<>();
        String base = baseUrl.isEmpty() ? guessBase() : baseUrl;
        if (base.isEmpty()) return out;
        for (Endpoint e : endpoints) {
            String p = e.path().startsWith("/") ? e.path() : "/" + e.path();
            String u = base.endsWith("/") ? base.substring(0, base.length() - 1) + p : base + p;
            if (!out.contains(u)) out.add(u);
        }
        if (out.isEmpty()) out.add(base);
        return out;
    }

    private String guessBase() {
        try {
            URI u = URI.create(url.getText().strip());
            if (u.getHost() != null) return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
        } catch (Exception ignore) { }
        return "";
    }

    private void fetch() {
        String u = url.getText().strip();
        if (u.isEmpty()) { status.setText("Enter the URL of the definition."); return; }
        status.setText("Fetching " + u + " ...");
        new Thread(() -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(u)).GET();
                if (auth != null) switch (auth.type()) {
                    case "Basic" -> b.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString((auth.name() + ":" + auth.value()).getBytes()));
                    case "Bearer" -> b.header("Authorization", "Bearer " + auth.value());
                    case "API key header" -> b.header(auth.name(), auth.value());
                    default -> { }
                }
                HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
                String body = r.body();
                SwingUtilities.invokeLater(() -> parse(body, u));
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> status.setText("Could not fetch the definition: " + e.getMessage()));
            }
        }, "api-definition-fetch").start();
    }

    private void loadFile(File f) {
        try {
            fileLabel.setText(f.getName());
            parse(Files.readString(f.toPath()), f.getName());
        } catch (Exception e) { status.setText("Could not read " + f.getName() + ": " + e.getMessage()); }
    }

    /** OpenAPI 3 / Swagger 2 JSON, or a WSDL: pull out the operations. */
    private void parse(String text, String from) {
        endpoints.clear();
        baseUrl = "";
        String t = text.strip();
        try {
            if (t.startsWith("{")) {
                JsonValue root = JsonValue.parse(t);
                JsonValue servers = root.get("servers");
                if (servers != null && servers.isArray() && !servers.asArray().isEmpty()) {
                    JsonValue s0 = servers.at(0).get("url");
                    if (s0 != null && s0.isString()) baseUrl = s0.asString();
                }
                JsonValue host = root.get("host");
                if (baseUrl.isEmpty() && host != null && host.isString()) {
                    JsonValue schemes = root.get("schemes");
                    String scheme = schemes != null && schemes.isArray() && !schemes.asArray().isEmpty() ? schemes.at(0).asString() : "https";
                    JsonValue bp = root.get("basePath");
                    baseUrl = scheme + "://" + host.asString() + (bp != null && bp.isString() ? bp.asString() : "");
                }
                JsonValue paths = root.get("paths");
                if (paths != null && paths.isObject()) {
                    for (String p : paths.keys()) {
                        JsonValue item = paths.get(p);
                        if (item == null || !item.isObject()) continue;
                        for (String m : item.keys()) {
                            String mu = m.toUpperCase();
                            if (!List.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE").contains(mu)) continue;
                            JsonValue op = item.get(m);
                            JsonValue sum = op == null ? null : op.get("summary");
                            endpoints.add(new Endpoint(mu, p, sum != null && sum.isString() ? sum.asString() : ""));
                        }
                    }
                }
                if (baseUrl.isEmpty() && from.startsWith("http")) baseUrl = guessBase();
            } else if (t.contains("<definitions") || t.contains("wsdl:definitions") || t.contains("<description")) {
                Matcher loc = Pattern.compile("location=\"([^\"]+)\"").matcher(t);
                if (loc.find()) baseUrl = loc.group(1);
                Matcher op = Pattern.compile("<(?:wsdl:)?operation\\s+name=\"([^\"]+)\"").matcher(t);
                java.util.Set<String> seen = new java.util.LinkedHashSet<>();
                while (op.find()) seen.add(op.group(1));
                for (String n : seen) endpoints.add(new Endpoint("POST", "", n));
            } else {
                status.setText("Unrecognised definition: expected OpenAPI/Swagger JSON or a WSDL.");
                return;
            }
        } catch (Exception e) {
            status.setText("Could not parse the definition: " + e.getMessage());
            return;
        }
        source = from;
        status.setText(endpoints.size() + " endpoint(s) found in " + from + (baseUrl.isEmpty() ? "" : "  (base " + baseUrl + ")"));
        if (onChange != null) onChange.run();
    }

    /** The "Add Auth" dialog: how to authenticate when fetching the definition. */
    static final class AddAuthDialog {
        static Auth show(Component parent, Auth current) {
            JComboBox<String> type = Ui.combo(new String[]{"None", "Basic", "Bearer", "API key header"});
            JTextField name = Ui.field(current == null ? "" : current.name(), 24);
            JPasswordField value = new JPasswordField(current == null ? "" : current.value(), 24);
            value.setFont(Ui.PLAIN);
            JLabel nameLabel = Ui.label("Username:");
            JLabel valueLabel = Ui.label("Password:");
            if (current != null) type.setSelectedItem(current.type());
            Runnable sync = () -> {
                String ty = String.valueOf(type.getSelectedItem());
                nameLabel.setText(switch (ty) { case "API key header" -> "Header name:"; case "Basic" -> "Username:"; default -> ""; });
                valueLabel.setText(switch (ty) { case "Bearer" -> "Token:"; case "API key header" -> "Key:"; default -> "Password:"; });
                name.setEnabled(ty.equals("Basic") || ty.equals("API key header"));
                value.setEnabled(!ty.equals("None"));
            };
            type.addActionListener(e -> sync.run());
            sync.run();
            JPanel form = new JPanel(new GridBagLayout());
            form.setOpaque(false);
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(4, 4, 4, 4); c.anchor = GridBagConstraints.LINE_START;
            c.gridx = 0; c.gridy = 0; form.add(Ui.label("Auth type:"), c); c.gridx = 1; form.add(type, c);
            c.gridx = 0; c.gridy = 1; form.add(nameLabel, c); c.gridx = 1; form.add(name, c);
            c.gridx = 0; c.gridy = 2; form.add(valueLabel, c); c.gridx = 1; form.add(value, c);
            int ok = JOptionPane.showConfirmDialog(parent, form, "Add Auth", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (ok != JOptionPane.OK_OPTION) return null;
            return new Auth(String.valueOf(type.getSelectedItem()), name.getText().strip(), new String(value.getPassword()));
        }
    }
}
