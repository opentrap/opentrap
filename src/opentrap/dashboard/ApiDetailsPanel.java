package opentrap.dashboard;

import opentrap.ui.Glyphs;
import opentrap.ui.Theme;
import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.util.List;

/**
 * API-only scan, step two: the endpoints the uploaded definition describes.
 * Empty until a definition is uploaded, with a button that jumps back to the
 * definition step.
 */
public final class ApiDetailsPanel extends JPanel {

    private final CardLayout cards = new CardLayout();
    private final DefaultTableModel model = new DefaultTableModel(new Object[]{"Method", "Path", "Summary"}, 0) {
        @Override public boolean isCellEditable(int r, int c) { return false; }
    };
    private final JLabel summary = Ui.label("");

    public ApiDetailsPanel(Runnable goToDefinition) {
        super();
        setLayout(cards);
        setOpaque(false);

        JPanel empty = new JPanel(new GridBagLayout());
        empty.setOpaque(false);
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setOpaque(false);
        JLabel icon = new JLabel(Glyphs.book(48, Theme.MUTED));
        JLabel h = Ui.heading("API details not populated");
        JTextArea t = Ui.note("To populate the API details, upload an API definition.");
        Ui.sizeNote(t, 380);
        JButton upload = Ui.primaryButton("Upload API definition", "go to the API definition step");
        upload.addActionListener(e -> goToDefinition.run());
        for (JComponent c : new JComponent[]{icon, h, t, upload}) { c.setAlignmentX(Component.CENTER_ALIGNMENT); col.add(c); col.add(Box.createVerticalStrut(10)); }
        empty.add(col, new GridBagConstraints());
        add(empty, "empty");

        JPanel full = new JPanel(new BorderLayout(0, 8));
        full.setOpaque(false);
        summary.setForeground(Theme.MUTED);
        full.add(summary, BorderLayout.NORTH);
        JTable table = new JTable(model);
        Ui.styleTable(table);
        Ui.distributeWidths(table, 80, 300, 300);
        full.add(Ui.scroll(table), BorderLayout.CENTER);
        add(full, "full");
        cards.show(this, "empty");
    }

    public void show(List<ApiDefinitionPanel.Endpoint> endpoints, String source, String base) {
        model.setRowCount(0);
        for (ApiDefinitionPanel.Endpoint e : endpoints) model.addRow(new Object[]{e.method(), e.path(), e.summary()});
        summary.setText(endpoints.size() + " endpoint(s) from " + source + (base.isEmpty() ? "" : ", base URL " + base));
        cards.show(this, endpoints.isEmpty() ? "empty" : "full");
    }
}
