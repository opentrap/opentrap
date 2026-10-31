package opentrap.ui;

import opentrap.core.*;

import javax.swing.*;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Suite-wide search across recorded traffic. */
public class SearchWindow extends JFrame {

    private final SuiteContext ctx;
    private final JTextField term = Ui.field("");
    private final JCheckBox caseSensitive = Ui.check("case sensitive", false);
    private final JCheckBox regex = Ui.check("regex", false);
    private final JCheckBox reqHeaders = Ui.check("request headers", true);
    private final JCheckBox respHeaders = Ui.check("response headers", true);
    private final JCheckBox reqBody = Ui.check("request body", true);
    private final JCheckBox respBody = Ui.check("response body", true);
    private final JLabel count = Ui.label("0 results");
    private final List<LogEntry> results = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final MessageEditor reqView;
    private final MessageEditor respView;

    public SearchWindow(SuiteContext ctx) {
        super("opentrap search");
        this.ctx = ctx;
        this.reqView = new MessageEditor(ctx, MessageEditor.REQUEST, false);
        this.respView = new MessageEditor(ctx, MessageEditor.RESPONSE, false);

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        JPanel root = new JPanel(new BorderLayout(0, 6));
        root.setBorder(Ui.pad(10, 10, 10, 10));

        JPanel north = new JPanel(new GridLayout(1, 2, 12, 0));
        JPanel s = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 4));
        s.setBorder(BorderFactory.createTitledBorder("search"));
        JButton go = Ui.button("go");
        s.add(term); s.add(caseSensitive); s.add(regex); s.add(go);
        JPanel loc = new JPanel(new GridLayout(2, 2));
        loc.setBorder(BorderFactory.createTitledBorder("locations"));
        loc.add(reqHeaders); loc.add(respHeaders); loc.add(reqBody); loc.add(respBody);
        north.add(s); north.add(loc);
        root.add(north, BorderLayout.NORTH);

        Ui.styleTable(table);
        Ui.distributeWidths(table, 70, 200, 420, 60, 70);
        Ui.centerCells(table);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Ui.scroll(table), detailPane());
        split.setDividerSize(5);
        Ui.balance(split, 0.45);
        root.add(split, BorderLayout.CENTER);
        root.add(count, BorderLayout.SOUTH);

        go.addActionListener(e -> run());
        term.addActionListener(e -> run());
        table.getSelectionModel().addListSelectionListener(e -> showSelected());

        setContentPane(root);
        setSize(1000, 700);
        setLocationRelativeTo(ctx.frame());
    }

    private JComponent detailPane() {
        JSplitPane sp = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, reqView, respView);
        sp.setDividerSize(5);
        Ui.balance(sp, 0.5);
        return sp;
    }

    private void run() {
        results.clear();
        String t = term.getText();
        if (!t.isEmpty()) {
            Pattern p = null;
            if (regex.isSelected()) {
                try { p = Pattern.compile(t, caseSensitive.isSelected() ? 0 : Pattern.CASE_INSENSITIVE); }
                catch (Exception e) {
                    JOptionPane.showMessageDialog(this, "invalid regex: " + t, "error", JOptionPane.ERROR_MESSAGE);
                    return;
                }
            }
            for (LogEntry e : ctx.store().all()) {
                if (matches(e, t, p)) results.add(e);
            }
        }
        model.fireTableDataChanged();
        count.setText(results.size() + (results.size() == 1 ? " result" : " results"));
    }

    private boolean matches(LogEntry e, String t, Pattern p) {
        return hit(e.request() == null ? null : e.request().headerText(), reqHeaders.isSelected(), t, p)
            || hit(e.request() == null ? null : e.request().bodyText(), reqBody.isSelected(), t, p)
            || hit(e.response() == null ? null : e.response().headerText(), respHeaders.isSelected(), t, p)
            || hit(e.response() == null ? null : e.response().bodyText(), respBody.isSelected(), t, p);
    }

    private boolean hit(String hay, boolean enabled, String t, Pattern p) {
        if (!enabled || hay == null) return false;
        if (p != null) return p.matcher(hay).find();
        return caseSensitive.isSelected() ? hay.contains(t)
                : hay.toLowerCase().contains(t.toLowerCase());
    }

    private void showSelected() {
        int i = table.getSelectedRow();
        if (i < 0 || i >= results.size()) return;
        LogEntry e = results.get(i);
        reqView.setMessage(e.service(), e.request() == null ? new HttpMessage(new byte[0]) : e.request().copy());
        respView.setMessage(e.service(), e.response() == null ? new HttpMessage(new byte[0]) : e.response().copy(),
                e.request());
    }

    private class Model extends AbstractTableModel {
        private final String[] cols = {"source", "host", "URL", "status", "length"};
        @Override public int getRowCount() { return results.size(); }
        @Override public int getColumnCount() { return cols.length; }
        @Override public String getColumnName(int c) { return cols[c]; }
        @Override public Object getValueAt(int r, int c) {
            LogEntry e = results.get(r);
            return switch (c) {
                case 0 -> e.tool();
                case 1 -> e.service().url();
                case 2 -> e.pathAndQuery();
                case 3 -> e.status() == 0 ? "" : String.valueOf(e.status());
                default -> e.responseLen() == 0 ? "" : String.valueOf(e.responseLen());
            };
        }
    }
}
