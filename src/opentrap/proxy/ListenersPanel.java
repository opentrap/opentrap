package opentrap.proxy;

import opentrap.core.SuiteContext;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;

/**
 * Add, edit, start and stop proxy listeners, laid out as the reference does:
 * an Add / Edit / Remove column at the left of the table, the listeners
 * themselves across it, and the CA certificate actions beneath.
 */
public class ListenersPanel extends JPanel {

    private final ProxyServer server;
    private final Model model = new Model();
    private final JTable table = new JTable(model);

    public ListenersPanel(SuiteContext ctx, ProxyServer server) {
        super();
        this.server = server;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setOpaque(false);

        JTextArea blurb = Ui.note("OpenTRAP uses listeners to receive incoming HTTP requests from "
                + "your browser. You will need to configure your browser to use one of the "
                + "listeners as its proxy server.");
        Ui.sizeNote(blurb, 820);
        blurb.setAlignmentX(LEFT_ALIGNMENT);
        add(blurb);
        add(Box.createVerticalStrut(10));

        Ui.styleTable(table);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        // running, interface, invisible, redirect, certificate, TLS, HTTP/2
        Ui.setWidths(table, 70, 150, 70, 110, 100, 110, 110);
        table.getColumnModel().getColumn(2).setCellRenderer(new Ui.TickRenderer());
        JScrollPane sp = Ui.scroll(table);
        sp.setPreferredSize(new Dimension(820, 120));
        sp.setMaximumSize(new Dimension(1100, 120));

        JButton add = Ui.button("Add", "add a proxy listener");
        JButton edit = Ui.button("Edit", "edit the selected listener");
        JButton remove = Ui.button("Remove", "remove the selected listener");
        add.addActionListener(e -> addListener());
        edit.addActionListener(e -> editSelected());
        remove.addActionListener(e -> removeSelected());
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                // A double click anywhere but the Running box opens the editor.
                if (e.getClickCount() == 2 && table.columnAtPoint(e.getPoint()) != 0) editSelected();
            }
        });

        JPanel row = new JPanel(new BorderLayout(10, 0));
        row.setOpaque(false);
        row.setAlignmentX(LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(1100, 120));
        row.add(Ui.buttonColumn(add, edit, remove), BorderLayout.WEST);
        row.add(sp, BorderLayout.CENTER);
        add(row);
        add(Box.createVerticalStrut(12));

        JTextArea caNote = Ui.note("Each installation of OpenTRAP generates its own CA certificate "
                + "that proxy listeners can use when negotiating TLS connections. You can import or "
                + "export this certificate for use in other tools or another installation.");
        Ui.sizeNote(caNote, 820);
        caNote.setAlignmentX(LEFT_ALIGNMENT);
        add(caNote);
        add(Box.createVerticalStrut(8));

        JButton importExport = Ui.button("Import / export CA certificate",
                "save this installation's CA certificate, or adopt one");
        JButton regenerate = Ui.button("Regenerate CA certificate",
                "replace the CA certificate; every client that trusted the old one must trust the new one");
        importExport.addActionListener(e -> CertificateActions.importExport(this, ctx, server));
        regenerate.addActionListener(e -> CertificateActions.regenerate(this, ctx, server));
        JPanel caRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        caRow.setOpaque(false);
        caRow.setAlignmentX(LEFT_ALIGNMENT);
        caRow.add(importExport);
        caRow.add(regenerate);
        add(caRow);

        server.onListenersChanged(() -> model.fireTableDataChanged());
        if (model.getRowCount() > 0) table.setRowSelectionInterval(0, 0);
        setMaximumSize(new Dimension(1100, 340));
    }

    private void addListener() {
        ProxyListener l = ListenerDialog.show(this, null, "Add proxy listener");
        if (l == null) return;
        for (ProxyListener existing : server.listeners()) {
            if (existing.port == l.port) {
                JOptionPane.showMessageDialog(this, "There is already a listener on port " + l.port + ".",
                        "Proxy listeners", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }
        server.listeners().add(l);
        server.start(l);
        model.fireTableDataChanged();
        table.setRowSelectionInterval(model.getRowCount() - 1, model.getRowCount() - 1);
    }

    private void editSelected() {
        int i = table.getSelectedRow();
        if (i < 0 || i >= server.listeners().size()) return;
        ProxyListener current = server.listeners().get(i);
        ProxyListener edited = ListenerDialog.show(this, current, "Edit proxy listener");
        if (edited == null) return;
        // Rebind: the port or the address may have changed under it.
        boolean wasRunning = current.running;
        server.stop(current);
        server.listeners().set(i, edited);
        if (wasRunning) server.start(edited);
        model.fireTableDataChanged();
        table.setRowSelectionInterval(i, i);
    }

    private void removeSelected() {
        int i = table.getSelectedRow();
        if (i < 0 || i >= server.listeners().size()) return;
        server.stop(server.listeners().get(i));
        server.listeners().remove(i);
        model.fireTableDataChanged();
        int n = model.getRowCount();
        if (n > 0) table.setRowSelectionInterval(Math.min(i, n - 1), Math.min(i, n - 1));
    }

    private class Model extends AbstractTableModel {
        private final String[] cols = {"Running", "Interface", "Invisible", "Redirect",
                                       "Certificate", "TLS Protocols", "Support HTTP/2"};
        @Override public int getRowCount() { return server.listeners().size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Class<?> getColumnClass(int c) {
            return (c == 0 || c == 2 || c == 6) ? Boolean.class : String.class;
        }
        @Override public boolean isCellEditable(int r, int c) { return c == 0 || c == 6; }

        @Override public Object getValueAt(int r, int c) {
            ProxyListener l = server.listeners().get(r);
            return switch (c) {
                case 0 -> l.running;
                case 1 -> l.interfaceLabel();
                case 2 -> l.invisible;
                case 3 -> l.redirectLabel();
                case 4 -> l.certLabel();
                case 5 -> l.tlsLabel();
                default -> l.http2;
            };
        }

        @Override public void setValueAt(Object v, int r, int c) {
            ProxyListener l = server.listeners().get(r);
            if (c == 0) {
                if (Boolean.TRUE.equals(v)) server.start(l); else server.stop(l);
            } else if (c == 6) {
                l.http2 = Boolean.TRUE.equals(v);
            }
            fireTableRowsUpdated(r, r);
        }
    }
}
