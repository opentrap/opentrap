package opentrap.collaborator;

import opentrap.core.SuiteContext;
import opentrap.core.ToolModule;
import opentrap.oast.Collaborator;
import opentrap.oast.Interaction;
import opentrap.ui.OptionsPage;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The collaborator tab: an out-of-band interaction view, in the spirit of the
 * commercial tool's Collaborator and ZAP's OAST.
 *
 * <p>It drives one {@link Collaborator} session. The top bar mints a payload to
 * copy into a manual test and shows the registration status; the table lists
 * every DNS, HTTP or SMTP callback a target made to a payload, and the lower
 * pane shows the raw request of the selected one. The active scanner shares the
 * same session, so a callback its blind checks triggered appears here too.
 *
 * <p>Plain table and buttons, matching the rest of the suite.
 */
public class CollaboratorTool implements ToolModule {

    private SuiteContext ctx;
    private Collaborator collab;
    private InteractionTableModel model;
    private JTable table;
    private JLabel statusLabel;
    private JTextField payloadField;
    private JTabbedPane detailTabs;
    private javax.swing.table.TableRowSorter<InteractionTableModel> sorter;

    @Override public String name() { return "collaborator"; }

    @Override public boolean alwaysTabbed() { return true; }

    @Override public void init(SuiteContext ctx) {
        this.ctx = ctx;
        this.collab = ctx.collaborator();
        collab.addListener(new Collaborator.Listener() {
            @Override public void interactionsAdded(List<Interaction> added) {
                SwingUtilities.invokeLater(() -> { model.add(added); refreshStatus(); });
            }
            @Override public void statusChanged(String status) {
                SwingUtilities.invokeLater(CollaboratorTool.this::refreshStatus);
            }
        });
    }

    @Override public Tab[] tabs() {
        return new Tab[]{
            new Tab("interactions", buildMain()),
            new Tab("options", buildOptions())
        };
    }

    // ---------------------------------------------------------------- main tab

    private JComponent buildMain() {
        model = new InteractionTableModel();
        table = new JTable(model);
        Ui.styleTable(table);
        sorter = new javax.swing.table.TableRowSorter<>(model);
        table.setRowSorter(sorter);
        Ui.newestFirst(table, 0);
        Ui.distributeWidths(table, 40, 150, 70, 380, 160, 160);
        table.getSelectionModel().addListSelectionListener(e -> showSelected());

        // Detail area as tabs, rebuilt per interaction so the set matches the
        // protocol, as the commercial tool does: a DNS lookup shows the query, an
        // HTTP request shows both the request and the response the server sent.
        detailTabs = new JTabbedPane();
        detailTabs.setFont(Ui.PLAIN);

        // Empty state over the table until the first interaction arrives.
        final CardLayout tableCards = new CardLayout();
        final JPanel tableArea = new JPanel(tableCards);
        tableArea.setOpaque(false);
        JPanel emptyCol = new JPanel();
        emptyCol.setLayout(new BoxLayout(emptyCol, BoxLayout.Y_AXIS));
        emptyCol.setOpaque(false);
        JLabel emptyIcon = new JLabel(opentrap.ui.Glyphs.search(64, opentrap.ui.Theme.BORDER));
        JLabel emptyTitle = new JLabel("Your interactions will appear here");
        emptyTitle.setFont(new Font("Dialog", Font.BOLD, 18));
        JTextArea emptyText = Ui.note("Click \"Copy to clipboard\" to generate Collaborator payloads that you can use when testing");
        Ui.sizeNote(emptyText, 420);
        JButton learn = Ui.button("Learn more");
        learn.addActionListener(e -> opentrap.ui.Unbuilt.openProject(table));
        for (JComponent c : new JComponent[]{emptyIcon, emptyTitle, emptyText, learn}) { c.setAlignmentX(Component.CENTER_ALIGNMENT); emptyCol.add(c); emptyCol.add(Box.createVerticalStrut(10)); }
        JPanel empty = new JPanel(new GridBagLayout());
        empty.setOpaque(false);
        empty.add(emptyCol, new GridBagConstraints());
        tableArea.add(empty, "empty");
        tableArea.add(Ui.scroll(table), "table");
        tableCards.show(tableArea, "empty");
        model.addTableModelListener(e -> tableCards.show(tableArea, model.getRowCount() == 0 ? "empty" : "table"));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                tableArea, detailTabs);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        // Give the detail pane half the height once the tab is laid out, rather
        // than leaving it a thin strip under a mostly empty table.
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean set = false;
            @Override public void componentResized(java.awt.event.ComponentEvent e) {
                if (!set && split.getHeight() > 0) { set = true; split.setDividerLocation(0.5); }
            }
        });

        // Toolbar: one button mints a fresh payload and copies it, then the
        // polling controls and the session status.
        statusLabel = Ui.label("not started");
        JButton copy = Ui.primaryButton("Copy to clipboard",
                "generate a fresh payload hostname and copy it to the clipboard");
        JButton pollNow = Ui.button("Poll now", "check the server for new interactions now");
        JCheckBox auto = Ui.check("Polling automatically", false);
        auto.setToolTipText("register with the collaborator server and keep polling it");

        payloadField = new JTextField("", 52);
        payloadField.setEditable(false);
        payloadField.setFont(Ui.MONO);

        copy.addActionListener(e -> {
            if (!collab.isRunning()) {
                String err = collab.start();
                if (err != null) {
                    JOptionPane.showMessageDialog(ctx.frame(),
                            "Could not register with " + collab.serverUrl() + ":\n" + err,
                            "Collaborator", JOptionPane.WARNING_MESSAGE);
                    refreshStatus();
                    return;
                }
                auto.setSelected(true);
            }
            String h = collab.payloadHost();
            if (h == null) {
                JOptionPane.showMessageDialog(ctx.frame(), "Could not generate a payload.",
                        "Collaborator", JOptionPane.WARNING_MESSAGE);
                return;
            }
            payloadField.setText(h);
            payloadField.setCaretPosition(0);
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(h), null);
            ctx.alert("collaborator", "copied collaborator payload " + h);
            refreshStatus();
        });
        pollNow.addActionListener(e -> {
            if (collab.isRunning()) collab.pollNow();
            else JOptionPane.showMessageDialog(ctx.frame(),
                    "Turn on \"Polling automatically\" or copy a payload first.",
                    "Collaborator", JOptionPane.INFORMATION_MESSAGE);
        });
        auto.addActionListener(e -> {
            if (auto.isSelected()) {
                String err = collab.start();
                if (err != null) {
                    auto.setSelected(false);
                    JOptionPane.showMessageDialog(ctx.frame(),
                            "Could not register with " + collab.serverUrl() + ":\n" + err,
                            "Collaborator", JOptionPane.WARNING_MESSAGE);
                }
            } else {
                collab.stop();
            }
            refreshStatus();
        });

        JSpinner count = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
        count.setFont(Ui.PLAIN);
        count.setPreferredSize(new Dimension(60, count.getPreferredSize().height));
        JCheckBox includeLocation = Ui.check("Include Collaborator server location", true);
        includeLocation.setToolTipText("copy the full hostname rather than only the unique prefix");
        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        bar.setOpaque(false);
        bar.add(Ui.label("Payloads to generate:"));
        bar.add(count);
        bar.add(copy);
        bar.add(includeLocation);
        bar.add(new JSeparator(SwingConstants.VERTICAL) {{ setPreferredSize(new Dimension(1, 22)); }});
        bar.add(pollNow);
        bar.add(auto);
        bar.add(Box.createHorizontalStrut(12));
        bar.add(statusLabel);

        // The payload row stretches the field to the full width so the whole
        // hostname is always visible, never clipped.
        JPanel payloadRow = new JPanel(new BorderLayout(6, 0));
        payloadRow.setOpaque(false);
        payloadRow.setBorder(Ui.pad(0, 8, 4, 8));
        payloadRow.add(Ui.label("Payload:"), BorderLayout.WEST);
        payloadRow.add(payloadField, BorderLayout.CENTER);

        // Filter row, as in the reference: protocol toggles and a search box.
        JToggleButton fHttp = filterToggle("HTTP"), fDns = filterToggle("DNS"), fSmtp = filterToggle("SMTP");
        JTextField search = Ui.field("", 18);
        search.setToolTipText("show only interactions containing this text");
        Runnable applyFilter = () -> {
            final String q = search.getText().trim().toLowerCase();
            final boolean http = fHttp.isSelected(), dns = fDns.isSelected(), smtp = fSmtp.isSelected();
            sorter.setRowFilter(new RowFilter<InteractionTableModel, Integer>() {
                @Override public boolean include(Entry<? extends InteractionTableModel, ? extends Integer> en) {
                    String type = en.getStringValue(2);
                    boolean typeOk = ("HTTP".equals(type) && http) || ("DNS".equals(type) && dns)
                            || ("SMTP".equals(type) && smtp)
                            || !("HTTP".equals(type) || "DNS".equals(type) || "SMTP".equals(type));
                    if (!typeOk) return false;
                    if (q.isEmpty()) return true;
                    for (int c = 0; c < en.getValueCount(); c++) {
                        if (en.getStringValue(c).toLowerCase().contains(q)) return true;
                    }
                    return false;
                }
            });
        };
        for (JToggleButton t : new JToggleButton[]{fHttp, fDns, fSmtp}) t.addActionListener(e -> applyFilter.run());
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e)  { applyFilter.run(); }
            public void removeUpdate(javax.swing.event.DocumentEvent e)  { applyFilter.run(); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { applyFilter.run(); }
        });
        JButton clear = Ui.button("Clear", "remove the collected interactions");
        clear.addActionListener(e -> { collab.clear(); model.clearAll(); detailTabs.removeAll(); });

        JPanel filterLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        filterLeft.setOpaque(false);
        filterLeft.add(Ui.label("Filter"));
        filterLeft.add(fHttp); filterLeft.add(fDns); filterLeft.add(fSmtp);
        JPanel filterRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 2));
        filterRight.setOpaque(false);
        filterRight.add(Ui.label("Search"));
        filterRight.add(search);
        filterRight.add(clear);
        JPanel filterRow = new JPanel(new BorderLayout());
        filterRow.setOpaque(false);
        filterRow.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Ui.GRID), Ui.pad(2, 4, 2, 4)));
        filterRow.add(filterLeft, BorderLayout.WEST);
        filterRow.add(filterRight, BorderLayout.EAST);

        JPanel top = Ui.vbox(bar, payloadRow, filterRow);
        top.setBorder(Ui.pad(2, 2, 0, 2));

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        refreshStatus();
        return root;
    }

    /** A small on/off chip for the protocol filter, on by default. */
    private static JToggleButton filterToggle(String text) {
        JToggleButton t = new JToggleButton(text, true);
        t.setFont(Ui.BOLD);
        t.setFocusable(false);
        t.setMargin(new Insets(1, 8, 1, 8));
        return t;
    }

    private static JComponent textTab(String content, boolean wrap) {
        JTextArea a = new JTextArea(content);
        a.setEditable(false);
        a.setFont(Ui.MONO);
        a.setLineWrap(wrap);
        a.setWrapStyleWord(wrap);
        a.setCaretPosition(0);
        return Ui.scroll(a);
    }

    /** Mint a fresh payload, show it and copy it. Returns false if not running. */
    private boolean generatePayload() {
        String host = collab.payloadHost();
        if (host == null) return false;
        payloadField.setText(host);
        payloadField.setCaretPosition(0);
        Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(host), null);
        ctx.alert("collaborator", "generated collaborator payload " + host);
        return true;
    }

    private void showSelected() {
        int keep = detailTabs.getSelectedIndex();
        detailTabs.removeAll();
        int row = table.getSelectedRow();
        if (row < 0) return;
        Interaction i = model.at(table.convertRowIndexToModel(row));
        if (i == null) return;

        // Description, phrased like the commercial tool and naming the query type
        // for a DNS lookup.
        StringBuilder d = new StringBuilder();
        if (i.isDns()) {
            String qt = i.queryType().isBlank() ? "" : " of type " + i.queryType();
            d.append("The collaborator server received a DNS lookup").append(qt)
             .append(" for the domain name ").append(i.fullId()).append(".\n\n");
            d.append("The lookup was received from IP address ").append(i.remoteAddress());
        } else if (i.isHttp()) {
            d.append("The collaborator server received an HTTP request.\n\n");
            d.append("The request was received from IP address ").append(i.remoteAddress());
        } else if (i.isSmtp()) {
            d.append("The collaborator server received an SMTP connection.\n\n");
            d.append("The connection was received from IP address ").append(i.remoteAddress());
        } else {
            d.append("The collaborator server received a ").append(i.protocol().toUpperCase())
             .append(" interaction.\n\n");
            d.append("It was received from IP address ").append(i.remoteAddress());
        }
        d.append(" at ").append(i.timestamp()).append(".");
        detailTabs.addTab("description", textTab(d.toString(), true));

        // Per-protocol tabs, matching the commercial tool.
        if (i.isDns()) {
            detailTabs.addTab("DNS query", textTab(orNone(i.rawRequest(), "query"), false));
        } else if (i.isHttp()) {
            detailTabs.addTab("request to collaborator", textTab(orNone(i.rawRequest(), "request"), false));
            detailTabs.addTab("response from collaborator", textTab(orNone(i.rawResponse(), "response"), false));
        } else {
            detailTabs.addTab("interaction", textTab(orNone(i.rawRequest(), "data"), false));
            if (!i.rawResponse().isBlank())
                detailTabs.addTab("response", textTab(i.rawResponse(), false));
        }

        // Keep the same tab selected across rows where it still exists, so a
        // tester watching the request tab is not bounced back to description.
        if (keep >= 0 && keep < detailTabs.getTabCount()) detailTabs.setSelectedIndex(keep);
    }

    private static String orNone(String s, String what) {
        return s == null || s.isBlank() ? "(no raw " + what + " recorded for this interaction)" : s;
    }

    private void refreshStatus() {
        if (statusLabel == null) return;
        statusLabel.setText(collab.status());
        statusLabel.setForeground(collab.isRunning() ? opentrap.ui.Theme.OK : opentrap.ui.Theme.MUTED);
    }

    // ------------------------------------------------------------- options tab

    private JComponent buildOptions() {
        OptionsPage page = new OptionsPage();

        JTextArea intro = Ui.note(
            "The collaborator catches out-of-band interactions: a DNS, HTTP or SMTP callback that "
            + "a target makes to a hostname the scanner planted. It is how blind vulnerabilities "
            + "such as server-side request forgery, blind cross-site scripting and Log4Shell are "
            + "confirmed, since the proof is the target reaching out to a host only the scanner "
            + "knew about.");
        page.section("about", intro);

        JTextField server = Ui.field(collab.serverUrl(), 34);
        JTextField tokenField = Ui.field(
                ctx.settings().get(Collaborator.TOKEN_KEY, ""), 34);
        JButton apply = Ui.button("apply and restart",
                "save the server and restart the collaborator session");
        apply.addActionListener(e -> {
            ctx.settings().set(Collaborator.SERVER_KEY, server.getText().trim());
            ctx.settings().set(Collaborator.TOKEN_KEY, tokenField.getText().trim());
            String err = collab.start();
            if (err != null) {
                JOptionPane.showMessageDialog(ctx.frame(), err, "collaborator",
                        JOptionPane.WARNING_MESSAGE);
            }
            refreshStatus();
        });
        JTextArea serverNote = Ui.note(
            "Use the public default for convenience, or a self-hosted interactsh server when an "
            + "engagement forbids any target interaction from transiting a third party. The "
            + "interaction contents are encrypted to this client either way, but a self-hosted "
            + "server keeps everything, including the callbacks themselves, under your control. An "
            + "authentication token is only needed if your server requires one.");
        page.section("collaborator server",
                OptionsPage.fieldRow("server URL", server, ""),
                OptionsPage.fieldRow("auth token", tokenField, "(optional)"),
                apply, serverNote);

        return page;
    }

    // ------------------------------------------------------------ table model

    private final class InteractionTableModel extends AbstractTableModel {
        private final String[] cols = {"#", "Time", "Type", "Payload", "Source IP address", "Comment"};
        private final List<Interaction> rows = new ArrayList<>();
        private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm:ss");

        void add(List<Interaction> added) {
            if (added.isEmpty()) return;
            int from = rows.size();
            rows.addAll(added);
            fireTableRowsInserted(from, rows.size() - 1);
        }

        void clearAll() { rows.clear(); fireTableDataChanged(); }

        Interaction at(int row) { return (row >= 0 && row < rows.size()) ? rows.get(row) : null; }

        @Override public int getRowCount()    { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Integer.class : String.class; }

        @Override public Object getValueAt(int r, int c) {
            Interaction i = rows.get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> i.receivedAt() > 0 ? fmt.format(new Date(i.receivedAt())) : i.timestamp();
                case 2 -> i.protocol().toUpperCase();
                case 3 -> i.fullId();
                case 4 -> i.remoteAddress();
                default -> "";
            };
        }
    }
}
