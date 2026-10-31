package opentrap.extensions;

import opentrap.ui.Glyphs;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeSelectionModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Extensions > APIs: the extension contract a tool module implements, as a
 * browsable tree on the left and the chosen type's source-style reference on
 * the right. The text here is the live contract from {@code opentrap.core};
 * CONTRACT.md in the repository says the same thing in prose.
 */
public final class ApisPanel extends JPanel {

    private final JTextArea view = new JTextArea();
    private final JLabel count = Ui.label("");

    public ApisPanel() {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        setBorder(Ui.pad(10, 12, 10, 12));

        // Header: heading left, starter project + help right.
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        JPanel titleCol = Ui.vbox(Ui.heading("Extender APIs"),
                Ui.note("You can use the Extender APIs to create your own extensions to customize OpenTRAP's behavior."));
        head.add(titleCol, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.setOpaque(false);
        JButton starter = Ui.primaryButton("Download starter project", "open the project page with the module contract and a skeleton");
        starter.addActionListener(e -> opentrap.ui.Unbuilt.openProject(this));
        right.add(starter);
        right.add(Glyphs.button(Glyphs.help(), "About the extension APIs", () -> JOptionPane.showMessageDialog(this,
                "Implement opentrap.core.ToolModule in a jar and load it from Extensions > Installed.\n"
              + "The tree lists every type an extension talks to; select one to read its contract.",
                "Extender APIs", JOptionPane.INFORMATION_MESSAGE)));
        head.add(right, BorderLayout.EAST);
        add(head, BorderLayout.NORTH);

        // Left: toolbar + tree.
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("api");
        Map<String, String> docs = docs();
        Map<String, DefaultMutableTreeNode> packages = new LinkedHashMap<>();
        for (String key : docs.keySet()) {
            int dot = key.lastIndexOf('.');
            String pkg = dot < 0 ? "" : key.substring(0, dot);
            String type = dot < 0 ? key : key.substring(dot + 1);
            if (pkg.isEmpty()) { root.add(new DefaultMutableTreeNode(new Node(key, type))); continue; }
            DefaultMutableTreeNode p = packages.computeIfAbsent(pkg, k -> {
                DefaultMutableTreeNode n = new DefaultMutableTreeNode(new Node(k, k));
                root.add(n);
                return n;
            });
            p.add(new DefaultMutableTreeNode(new Node(key, type)));
        }
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Ui.PLAIN);
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> {
            Object o = tree.getLastSelectedPathComponent();
            if (o instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof Node node) {
                String text = docs.getOrDefault(node.key, "package " + node.key + ";\n");
                view.setText(text);
                view.setCaretPosition(0);
            }
        });
        for (int i = 0; i < tree.getRowCount(); i++) tree.expandRow(i);

        JTextField search = Ui.field("", 14);
        JPanel bar = new JPanel(new BorderLayout(4, 0));
        bar.setOpaque(false);
        JPanel icons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        icons.setOpaque(false);
        icons.add(Glyphs.button(opentrap.ui.Glyphs.help(14, Theme.MUTED), "Types an extension can use", null));
        icons.add(Glyphs.button(Glyphs.copy(14, Theme.MUTED), "Copy the shown reference to the clipboard", () ->
                Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(view.getText()), null)));
        bar.add(icons, BorderLayout.WEST);
        bar.add(search, BorderLayout.CENTER);
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void go() {
                String q = search.getText().strip().toLowerCase();
                if (q.isEmpty()) return;
                for (int i = 0; i < tree.getRowCount(); i++) {
                    if (tree.getPathForRow(i).getLastPathComponent().toString().toLowerCase().contains(q)) {
                        tree.setSelectionRow(i); tree.scrollRowToVisible(i); return;
                    }
                }
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setOpaque(false);
        left.add(bar, BorderLayout.NORTH);
        JScrollPane ts = Ui.scroll(tree);
        ts.setPreferredSize(new Dimension(300, 10));
        left.add(ts, BorderLayout.CENTER);

        // Right: the reference text with a line gutter and the editor-style footer.
        view.setEditable(false);
        view.setFont(Ui.MONO);
        JScrollPane vs = Ui.scroll(view);
        vs.setRowHeaderView(new LineGutter(view));
        JPanel footer = new JPanel(new BorderLayout(6, 0));
        footer.setOpaque(false);
        footer.setBorder(Ui.pad(4, 0, 0, 0));
        JPanel fl = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        fl.setOpaque(false);
        fl.add(Glyphs.button(Glyphs.help(), "Reference text for the selected type", null));
        fl.add(Glyphs.button(Glyphs.gear(), "Viewer settings", null));
        JTextField find = Ui.field("", 24);
        find.putClientProperty("JTextField.placeholderText", "Search");
        find.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void go() {
                String q = find.getText();
                int n = 0;
                if (!q.isEmpty()) {
                    String t = view.getText();
                    int i = 0;
                    while ((i = t.indexOf(q, i)) >= 0) { n++; if (n == 1) { view.setCaretPosition(i); view.moveCaretPosition(i + q.length()); } i += q.length(); }
                }
                count.setText(n + " highlights");
            }
            public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        count.setText("0 highlights");
        footer.add(fl, BorderLayout.WEST);
        footer.add(find, BorderLayout.CENTER);
        footer.add(count, BorderLayout.EAST);
        JPanel rightPane = new JPanel(new BorderLayout());
        rightPane.setOpaque(false);
        rightPane.add(vs, BorderLayout.CENTER);
        rightPane.add(footer, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, rightPane);
        split.setDividerSize(5);
        split.setBorder(null);
        split.setDividerLocation(320);
        add(split, BorderLayout.CENTER);
        if (tree.getRowCount() > 0) tree.setSelectionRow(0);
    }

    private record Node(String key, String label) {
        @Override public String toString() { return label; }
    }

    /** A line-number gutter for a text area. */
    private static final class LineGutter extends JComponent {
        private final JTextArea area;
        LineGutter(JTextArea a) {
            this.area = a;
            setFont(Ui.MONO);
            a.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                public void insertUpdate(javax.swing.event.DocumentEvent e) { revalidate(); repaint(); }
                public void removeUpdate(javax.swing.event.DocumentEvent e) { revalidate(); repaint(); }
                public void changedUpdate(javax.swing.event.DocumentEvent e) { revalidate(); repaint(); }
            });
        }
        @Override public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int digits = String.valueOf(Math.max(1, area.getLineCount())).length();
            return new Dimension(fm.charWidth('0') * (digits + 1) + 8, area.getPreferredSize().height);
        }
        @Override protected void paintComponent(Graphics g) {
            g.setColor(Theme.SURFACE);
            g.fillRect(0, 0, getWidth(), getHeight());
            g.setColor(Theme.MUTED);
            g.setFont(getFont());
            FontMetrics fm = g.getFontMetrics();
            int lh = fm.getHeight();
            Rectangle clip = g.getClipBounds();
            int first = Math.max(0, clip.y / lh), last = Math.min(area.getLineCount(), (clip.y + clip.height) / lh + 1);
            for (int i = first; i < last; i++) {
                String s = String.valueOf(i + 1);
                g.drawString(s, getWidth() - 6 - fm.stringWidth(s), (i + 1) * lh - fm.getDescent());
            }
        }
    }

    /** The contract, type by type. Keep this in step with opentrap.core. */
    private static Map<String, String> docs() {
        Map<String, String> d = new LinkedHashMap<>();
        d.put("opentrap.core.ToolModule", """
            package opentrap.core;

            import javax.swing.JMenu;

            /**
             * The contract every tool implements. The shell knows nothing else about a
             * tool: it asks for a name, some tabs, and optionally some menus, then renders
             * whatever it is given. Adding a tool costs one registration call.
             */
            public interface ToolModule {

                /** Lower-case display name used on the tab and in "send to" menus. */
                String name();

                /** Tabs shown inside this tool. One entry means no inner tab bar. */
                Tab[] tabs();

                /** Menus contributed to the suite menu bar, or null for none. */
                default JMenu[] menus() { return null; }

                /** Force an inner tab bar even with a single tab. */
                default boolean alwaysTabbed() { return false; }

                /** Called once after every tool is registered and the context is live. */
                default void init(SuiteContext ctx) {}

                /** Called when the suite is shutting down. */
                default void shutdown() {}

                /** A serialisable snapshot of this tool's working state, saved with the project. */
                default java.io.Serializable saveState() { return null; }

                /** Restore a snapshot previously produced by saveState(). */
                default void restoreState(java.io.Serializable state) {}

                /** A tab inside a tool: a title and the component to show. */
                record Tab(String title, javax.swing.JComponent component) {}
            }
            """);
        d.put("opentrap.core.SuiteContext", """
            package opentrap.core;

            /** Passed to ToolModule.init: everything a tool needs from the suite. */
            public class SuiteContext {
                MessageStore store();            // every request/response the suite has seen
                WebSocketStore wsStore();
                Scope scope();                   // the target scope
                Settings settings();             // key/value settings, persisted
                java.awt.Frame frame();          // the main window
                java.io.File projectFile();      // null for a temporary project

                // "Send to <tool>": register a sink to receive items from other tools.
                void registerSink(String toolName, MessageSink sink);
                boolean hasSink(String toolName);
                java.util.List<String> sinkNames();
                void sendTo(String tool, HttpService svc, HttpMessage req, HttpMessage resp, String caption);

                // History and site map.
                LogEntry record(String tool, HttpService svc, HttpMessage req);
                void siteMapChanged(LogEntry e);
                void addSiteMapListener(SiteMapListener l);

                // Issues.
                boolean addIssue(Issue i);
                java.util.List<Issue> issues();
                void addIssueListener(IssueListener l);

                // Tasks (dashboard).
                void addTask(ScanTask t);
                void removeTask(ScanTask t);
                java.util.List<ScanTask> tasks();
                void addTaskListener(TaskListener l);

                // Event log.
                void alert(String tool, String message);
                java.util.List<Alert> alerts();
                void addAlertListener(AlertListener l);

                // Scope changes.
                void scopeChanged();
                void addScopeListener(ScopeListener l);

                interface MessageSink { void receive(HttpService svc, HttpMessage req, HttpMessage resp, String caption); }
                interface SiteMapListener { void siteMapChanged(LogEntry e); }
                interface ScopeListener { void scopeChanged(); }
                interface IssueListener { void issueAdded(Issue i); }
                interface TaskListener { void taskChanged(ScanTask t); }
                interface AlertListener { void alertRaised(Alert a); }
            }
            """);
        d.put("opentrap.core.HttpMessage", """
            package opentrap.core;

            /** One HTTP request or response, as raw bytes with parsed accessors. */
            public class HttpMessage {
                byte[] raw();                 // the exact bytes on the wire
                String firstLine();           // request line or status line
                java.util.List<String> headers();
                String header(String name);   // first header with that name, or null
                byte[] body();
                int statusCode();             // responses only
                String method();              // requests only
                String path();                // requests only
            }
            """);
        d.put("opentrap.core.HttpService", """
            package opentrap.core;

            /** Where a message goes: host, port and whether TLS is used. */
            public record HttpService(String host, int port, boolean https) {
                String baseUrl();             // https://host:port
            }
            """);
        d.put("opentrap.core.LogEntry", """
            package opentrap.core;

            /** A request and (when it has arrived) its response, as recorded in the history. */
            public class LogEntry {
                int number();
                String tool();                // which tool made the request
                HttpService service();
                HttpMessage request();
                HttpMessage response();       // null until it arrives
                long time();
                String comment();
                void setComment(String c);
                java.awt.Color highlight();
                void setHighlight(java.awt.Color c);
            }
            """);
        d.put("opentrap.core.Issue", """
            package opentrap.core;

            /** A finding, as the dashboard and the site map show it. */
            public class Issue {
                enum Severity { HIGH, MEDIUM, LOW, INFORMATION }
                enum Confidence { CERTAIN, FIRM, TENTATIVE }
                String name();
                Severity severity();
                Confidence confidence();
                HttpService service();
                String path();
                String detail();              // HTML
                String background();          // HTML
                String remediation();         // HTML
                java.util.List<LogEntry> evidence();
            }
            """);
        d.put("opentrap.core.Scope", """
            package opentrap.core;

            /** The target scope: include and exclude rules, and a test for a URL. */
            public class Scope {
                boolean inScope(HttpService svc, String path);
                java.util.List<Rule> includes();
                java.util.List<Rule> excludes();
                void add(Rule r, boolean include);
                void remove(Rule r, boolean include);
                record Rule(boolean enabled, String prefix, boolean subdomains) {}
            }
            """);
        d.put("opentrap.core.Settings", """
            package opentrap.core;

            /** Key/value settings, persisted with the user profile or the project. */
            public class Settings {
                String get(String key, String def);
                void set(String key, String value);
                boolean getBool(String key, boolean def);
                void setBool(String key, boolean v);
                int getInt(String key, int def);
                void setInt(String key, int v);
                void addListener(java.util.function.Consumer<String> keyChanged);
            }
            """);
        d.put("opentrap.core.ScanTask", """
            package opentrap.core;

            /** A dashboard task: a live task or an audit, with its progress and issue counts. */
            public class ScanTask {
                enum Type { LIVE_PASSIVE, LIVE_ACTIVE, AUDIT }
                enum State { RUNNING, PAUSED, FINISHED }
                int number;
                Type type();   String title();   String configuration();   String scope();
                State state(); void setState(State s);
                int high(); int medium(); int low(); int info();
                int requests(); int itemsTotal(); int itemsDone();
                double progress();
            }
            """);
        d.put("opentrap.core.MessageStore", """
            package opentrap.core;

            /** Every request the suite has recorded, by number, with change notification. */
            public class MessageStore {
                java.util.List<LogEntry> all();
                LogEntry get(int number);
                void addListener(Listener l);
                interface Listener { void entryAdded(LogEntry e); void entryUpdated(LogEntry e); }
            }
            """);
        d.put("opentrap.ui.MessageEditor", """
            package opentrap.ui;

            /** The request/response editor: Pretty, Raw, Hex (and Render) views with an Inspector. */
            public class MessageEditor extends javax.swing.JPanel {
                static final int REQUEST = 1, RESPONSE = 2;
                MessageEditor(opentrap.core.SuiteContext ctx, int flags, boolean editable);
                void setMessage(opentrap.core.HttpService svc, opentrap.core.HttpMessage m);
                opentrap.core.HttpMessage message();
                byte[] bytes();
                void highlight(String term);
                void addActionProvider(ActionProvider p);   // add items to the right-click menu
                interface ActionProvider { void contribute(javax.swing.JPopupMenu menu); }
            }
            """);
        d.put("opentrap.ui.Ui", """
            package opentrap.ui;

            /** Widget factories every tool uses, so the whole suite looks the same. */
            public final class Ui {
                static javax.swing.JLabel heading(String text);
                static javax.swing.JLabel label(String text);
                static javax.swing.JLabel bold(String text);
                static javax.swing.JTextArea note(String text);
                static javax.swing.JCheckBox check(String text, boolean selected);
                static javax.swing.JRadioButton radio(String text, boolean selected);
                static javax.swing.JButton button(String text);
                static javax.swing.JButton primaryButton(String text, String tooltip);
                static javax.swing.JTextField field(String text, int columns);
                static javax.swing.JPanel form(Object... labelThenComponent);
                static javax.swing.JPanel row(java.awt.Component... parts);
                static javax.swing.JPanel vbox(java.awt.Component... parts);
                static javax.swing.JScrollPane scroll(java.awt.Component c);
                static void styleTable(javax.swing.JTable t);
            }
            """);
        return d;
    }
}
