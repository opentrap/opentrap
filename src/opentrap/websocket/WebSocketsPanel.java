package opentrap.websocket;

import opentrap.core.*;
import opentrap.proxy.WebSocketConnection;
import opentrap.proxy.WsInterceptor;
import opentrap.ui.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.locks.ReentrantLock;

/**
 * WebSocket traffic, shown as a sub-tab of the Proxy tool (where Burp puts it):
 * a history of every frame relayed, a viewer, a replay box to inject frames into
 * a live connection, and a frame interceptor that pauses text/binary frames so
 * they can be edited or dropped in flight.
 */
public class WebSocketsPanel extends JPanel implements WsInterceptor {

    private final SuiteContext ctx;
    private final HistoryModel model = new HistoryModel();
    private JTable table;
    private JTextArea viewer;

    private JComboBox<WebSocketConnection> connCombo;
    private JTextField sendField;

    private JCheckBox interceptToggle;
    private JTextArea editArea;
    private JButton forward, drop;
    private JLabel heldLabel;
    private final ReentrantLock interceptLock = new ReentrantLock();
    private final SynchronousQueue<WsInterceptor.Decision> decision = new SynchronousQueue<>();
    private volatile WebSocketMessage held;

    public WebSocketsPanel(SuiteContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        ctx.wsStore().addListener(m -> SwingUtilities.invokeLater(() -> model.add(m)));
        WebSocketConnection.setInterceptor(this);
        WebSocketConnection.addLiveListener(() -> SwingUtilities.invokeLater(this::refreshConnections));

        JTabbedPane inner = new JTabbedPane();
        inner.setFont(Ui.PLAIN);
        inner.addTab("history", buildHistory());
        inner.addTab("intercept", buildIntercept());
        add(inner, BorderLayout.CENTER);
    }

    // ---------------- history + replay ----------------

    private JComponent buildHistory() {
        table = new JTable(model);
        Ui.styleTable(table);
        Ui.distributeWidths(table, 50, 60, 70, 70, 70, 360);
        table.getSelectionModel().addListSelectionListener(e -> showSelected());

        viewer = new JTextArea();
        viewer.setFont(Ui.MONO);
        viewer.setEditable(false);

        JButton clear = Ui.button("clear");
        clear.addActionListener(e -> { ctx.wsStore().clear(); model.clear(); });

        connCombo = Ui.combo(new WebSocketConnection[0]);
        sendField = Ui.field("", 30);
        JButton send = Ui.primaryButton("send frame", "inject a text frame into the selected live connection");
        send.addActionListener(e -> replay());
        refreshConnections();

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(clear);
        bar.add(new JSeparator(SwingConstants.VERTICAL));
        bar.add(Ui.bold("replay:"));
        bar.add(Ui.label("connection"));
        bar.add(connCombo);
        bar.add(sendField);
        bar.add(send);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(table), Ui.scroll(viewer));
        split.setDividerSize(5);
        Ui.balance(split, 0.6);

        JPanel root = new JPanel(new BorderLayout());
        root.add(bar, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        return root;
    }

    private void showSelected() {
        int r = table.getSelectedRow();
        if (r < 0) { viewer.setText(""); return; }
        WebSocketMessage m = model.at(table.convertRowIndexToModel(r));
        viewer.setText(m == null ? "" : m.text());
        viewer.setCaretPosition(0);
    }

    private void refreshConnections() {
        if (connCombo == null) return;
        Object selected = connCombo.getSelectedItem();
        connCombo.removeAllItems();
        for (WebSocketConnection c : WebSocketConnection.live()) connCombo.addItem(c);
        if (selected != null) connCombo.setSelectedItem(selected);
    }

    private void replay() {
        WebSocketConnection c = (WebSocketConnection) connCombo.getSelectedItem();
        String text = sendField.getText();
        if (c == null) { ctx.alert("websockets", "no live connection selected"); return; }
        Thread t = new Thread(() -> {
            try { c.sendTextToServer(text); }
            catch (Exception ex) { ctx.alert("websockets", "send failed: " + ex.getMessage()); }
        }, "ws-replay");
        t.setDaemon(true);
        t.start();
    }

    // ---------------- interception ----------------

    private JComponent buildIntercept() {
        interceptToggle = Ui.check("intercept WebSocket frames", false);
        interceptToggle.addActionListener(e -> WebSocketConnection.intercepting = interceptToggle.isSelected());
        heldLabel = Ui.label("no frame held");
        editArea = new JTextArea();
        editArea.setFont(Ui.MONO);
        editArea.setEditable(false);
        forward = Ui.primaryButton("forward", "send this frame, with any edits");
        drop = Ui.button("drop", "discard this frame");
        forward.setEnabled(false);
        drop.setEnabled(false);
        forward.addActionListener(e -> resolveForward());
        drop.addActionListener(e -> resolve(WsInterceptor.Decision.drop()));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        top.add(interceptToggle);
        top.add(new JSeparator(SwingConstants.VERTICAL));
        top.add(forward);
        top.add(drop);
        top.add(heldLabel);

        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(Ui.scroll(editArea), BorderLayout.CENTER);
        root.add(Ui.note(" Text frames can be edited before forwarding; binary frames are forwarded as captured."),
                BorderLayout.SOUTH);
        return root;
    }

    @Override public WsInterceptor.Decision intercept(WebSocketMessage f) {
        if (!WebSocketConnection.intercepting) return WsInterceptor.Decision.forward();
        interceptLock.lock();
        try {
            held = f;
            SwingUtilities.invokeLater(() -> showHeld(f));
            return decision.take();
        } catch (InterruptedException e) {
            return WsInterceptor.Decision.forward();
        } finally {
            held = null;
            SwingUtilities.invokeLater(this::clearHeld);
            interceptLock.unlock();
        }
    }

    private void showHeld(WebSocketMessage f) {
        heldLabel.setText("#" + f.connectionId + "  " + f.direction + "  " + f.op + "  " + f.length() + " bytes");
        boolean textFrame = f.op == WebSocketMessage.Op.TEXT;
        editArea.setText(f.text());
        editArea.setEditable(textFrame);
        editArea.setCaretPosition(0);
        forward.setEnabled(true);
        drop.setEnabled(true);
    }

    private void clearHeld() {
        heldLabel.setText("no frame held");
        editArea.setText("");
        editArea.setEditable(false);
        forward.setEnabled(false);
        drop.setEnabled(false);
    }

    private void resolveForward() {
        WebSocketMessage f = held;
        if (f != null && f.op == WebSocketMessage.Op.TEXT) {
            resolve(WsInterceptor.Decision.edit(editArea.getText().getBytes(StandardCharsets.UTF_8)));
        } else {
            resolve(WsInterceptor.Decision.forward());
        }
    }

    private void resolve(WsInterceptor.Decision d) { decision.offer(d); }

    private final class HistoryModel extends AbstractTableModel {
        private final String[] cols = {"#", "conn", "direction", "opcode", "length", "preview"};
        private final List<WebSocketMessage> rows = new ArrayList<>();
        void add(WebSocketMessage m) { rows.add(m); fireTableRowsInserted(rows.size() - 1, rows.size() - 1); }
        void clear() { rows.clear(); fireTableDataChanged(); }
        WebSocketMessage at(int r) { return r >= 0 && r < rows.size() ? rows.get(r) : null; }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return (c == 0 || c == 4) ? Integer.class : String.class; }
        @Override public Object getValueAt(int r, int c) {
            WebSocketMessage m = rows.get(r);
            return switch (c) {
                case 0 -> r + 1;
                case 1 -> "#" + m.connectionId;
                case 2 -> m.direction == WebSocketMessage.Direction.C2S ? "client->server" : "server->client";
                case 3 -> m.op + (m.edited ? " (edited)" : "");
                case 4 -> m.length();
                default -> m.preview();
            };
        }
    }
}
