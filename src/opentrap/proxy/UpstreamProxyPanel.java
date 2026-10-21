package opentrap.proxy;

import opentrap.core.SuiteContext;
import opentrap.core.UpstreamProxy;
import opentrap.core.UpstreamProxyManager;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;

/**
 * Configure upstream proxy chaining: a table of rules routing matching
 * destinations through an HTTP (CONNECT) or SOCKS proxy. Rules are saved to
 * settings as they change, so they persist and travel with the project.
 */
public class UpstreamProxyPanel extends JPanel {

    private final SuiteContext ctx;
    private final UpstreamProxyManager mgr;
    private final Model model = new Model();

    public UpstreamProxyPanel(SuiteContext ctx) {
        super(new BorderLayout());
        this.ctx = ctx;
        this.mgr = ctx.upstreamProxies();

        JTable table = new JTable(model);
        Ui.styleTable(table);
        Ui.distributeWidths(table, 55, 70, 170, 60, 110, 160);

        JButton add = Ui.primaryButton("add", "add an upstream proxy rule");
        JButton edit = Ui.button("edit");
        JButton del = Ui.button("delete");
        add.addActionListener(e -> { UpstreamProxy p = new UpstreamProxy(); if (editRule(p)) { mgr.rules().add(p); persist(); } });
        edit.addActionListener(e -> { int i = table.getSelectedRow(); if (i >= 0 && editRule(mgr.rules().get(i))) persist(); });
        del.addActionListener(e -> { int i = table.getSelectedRow(); if (i >= 0) { mgr.rules().remove(i); persist(); } });

        JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        bar.add(add); bar.add(edit); bar.add(del);
        bar.add(Ui.note("Matching destinations are routed through the proxy; the first matching rule wins. "
                + "HTTP CONNECT supports Basic auth."));

        add(bar, BorderLayout.NORTH);
        add(Ui.scroll(table), BorderLayout.CENTER);
    }

    private void persist() { mgr.save(); model.fireTableDataChanged(); }

    private boolean editRule(UpstreamProxy p) {
        JComboBox<UpstreamProxy.Type> type = Ui.combo(UpstreamProxy.Type.values());
        type.setSelectedItem(p.type);
        JTextField host = Ui.field(p.host, 18);
        JTextField port = Ui.field(String.valueOf(p.port), 6);
        JTextField user = Ui.field(p.user, 14);
        JPasswordField pass = new JPasswordField(p.pass, 14);
        pass.setFont(Ui.PLAIN);
        JTextField pattern = Ui.field(p.hostPattern, 18);
        JCheckBox enabled = Ui.check("enabled", p.enabled);

        JPanel form = Ui.form(
                "type", type,
                "proxy host", host,
                "proxy port", port,
                "username (HTTP only)", user,
                "password", pass,
                "destination host (blank = all, *.example.com ok)", pattern,
                "", enabled);
        int ok = JOptionPane.showConfirmDialog(ctx.frame(), form, "upstream proxy",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ok != JOptionPane.OK_OPTION) return false;
        p.type = (UpstreamProxy.Type) type.getSelectedItem();
        p.host = host.getText().trim();
        try { p.port = Integer.parseInt(port.getText().trim()); } catch (Exception ignored) {}
        p.user = user.getText().trim();
        p.pass = new String(pass.getPassword());
        p.hostPattern = pattern.getText().trim();
        p.enabled = enabled.isSelected();
        return true;
    }

    private final class Model extends AbstractTableModel {
        private final String[] cols = {"enabled", "type", "proxy host", "port", "auth", "destination"};
        @Override public int getRowCount() { return mgr.rules().size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) { return c == 0 ? Boolean.class : String.class; }
        @Override public boolean isCellEditable(int r, int c) { return c == 0; }
        @Override public void setValueAt(Object v, int r, int c) {
            if (c == 0) { mgr.rules().get(r).enabled = Boolean.TRUE.equals(v); mgr.save(); fireTableRowsUpdated(r, r); }
        }
        @Override public Object getValueAt(int r, int c) {
            UpstreamProxy p = mgr.rules().get(r);
            return switch (c) {
                case 0 -> p.enabled;
                case 1 -> p.type.toString();
                case 2 -> p.host;
                case 3 -> p.port;
                case 4 -> p.user.isEmpty() ? "-" : p.user;
                default -> p.hostPattern.isBlank() ? "(all)" : p.hostPattern;
            };
        }
    }
}
