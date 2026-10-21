package opentrap.target;

import opentrap.engine.ZapApi;
import opentrap.engine.ZapEngine;
import opentrap.spider.Cdp.JsonValue;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;

/**
 * The Target > Issues tab: a catalogue of every issue the scan engine can
 * detect, mirroring Burp's "Issue definitions". Populated from the engine's
 * active and passive scan rules, loaded lazily the first time it is shown.
 */
public class IssueDefinitionsPanel extends JPanel {

    private final Model model = new Model();
    private final JTextPane detail = new JTextPane();
    private JTable table;
    private boolean loaded;

    public IssueDefinitionsPanel() {
        super(new BorderLayout());

        JLabel title = Ui.heading("Issue definitions");
        title.setBorder(Ui.pad(8, 10, 2, 10));
        JTextArea note = Ui.note("This listing contains the definitions of all issues that can be "
                + "detected by the scan engine (OWASP ZAP).");
        note.setBorder(Ui.pad(0, 10, 6, 10));
        JPanel head = new JPanel(new BorderLayout());
        head.add(title, BorderLayout.NORTH);
        head.add(note, BorderLayout.CENTER);

        table = new JTable(model);
        Ui.styleTable(table);
        Ui.distributeWidths(table, 360, 120, 120);
        table.setRowSorter(new javax.swing.table.TableRowSorter<>(model));
        table.getSelectionModel().addListSelectionListener(e -> showDetail());

        detail.setContentType("text/html");
        detail.setEditable(false);
        detail.setFont(Ui.PLAIN);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Ui.scroll(table), Ui.scroll(detail));
        split.setDividerSize(5);
        Ui.balance(split, 0.5);

        add(head, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0
                    && isShowing() && !loaded) load();
        });
    }

    private void load() {
        loaded = true;
        ZapEngine ze = ZapEngine.shared();
        if (ze == null || !ze.available()) {
            detail.setText("<html><body style='font-family:sans-serif'><i>Scan engine (ZAP) not installed. "
                    + "Run ./fetch-zap.sh to add it.</i></body></html>");
            return;
        }
        new Thread(() -> {
            try {
                ze.start();
                ZapApi api = ze.api();
                List<Def> defs = new ArrayList<>();
                for (JsonValue s : api.activeScanners())
                    defs.add(new Def(nz(ZapApi.str(s, "name")), "active", nz(ZapApi.str(s, "id"))));
                for (JsonValue s : api.passiveScanners())
                    defs.add(new Def(nz(ZapApi.str(s, "name")), "passive", nz(ZapApi.str(s, "id"))));
                defs.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
                SwingUtilities.invokeLater(() -> model.set(defs));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> detail.setText(
                        "<html><body style='font-family:sans-serif'><i>Could not load issue definitions: "
                        + ex.getMessage() + "</i></body></html>"));
            }
        }, "issue-defs-load").start();
    }

    private void showDetail() {
        int r = table.getSelectedRow();
        if (r < 0) { detail.setText(""); return; }
        Def d = model.at(table.convertRowIndexToModel(r));
        if (d == null) return;
        detail.setText("<html><body style='font-family:sans-serif;margin:8px'>"
                + "<h2 style='color:#DB612F'>" + esc(d.name) + "</h2>"
                + "<p><b>Scan rule type:</b> " + d.type + "</p>"
                + "<p><b>Type index:</b> " + esc(d.id) + "</p>"
                + "<p>Detected by the " + d.type + " scanner. Run a scan to find instances in a target.</p>"
                + "</body></html>");
        detail.setCaretPosition(0);
    }

    private static String nz(String s) { return s == null ? "" : s; }
    private static String esc(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record Def(String name, String type, String id) {}

    private static final class Model extends AbstractTableModel {
        private final String[] cols = {"Name", "Scanner", "Type index"};
        private List<Def> rows = new ArrayList<>();
        void set(List<Def> d) { rows = d; fireTableDataChanged(); }
        Def at(int r) { return r >= 0 && r < rows.size() ? rows.get(r) : null; }
        @Override public int getRowCount() { return rows.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            Def d = rows.get(r);
            return switch (c) { case 0 -> d.name; case 1 -> d.type; default -> d.id; };
        }
    }
}
