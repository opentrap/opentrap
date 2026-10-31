package opentrap.proxy;

import opentrap.ui.Ui;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;

/**
 * The filter popup for a history or site-map table.
 *
 * <p>This is the thorough half of the search. It gathers every structural filter
 * and the full-text search term into one dialog, the way the commercial tool's
 * filter settings do, so the bar beside the table stays a one-line summary and
 * the quick box beside it stays a fast URL search.
 *
 * <p>The dialog edits the live {@link HistoryFilter} and calls back on apply, so
 * the table re-filters immediately. Revert puts the controls back to the
 * filter as it was when the dialog opened.
 */
public final class FilterDialog extends JDialog {

    private final HistoryFilter f;
    private final HistoryFilter opened;   // the filter as it was on opening, for Revert
    private final Runnable onApply;
    private final boolean siteMap;

    private final JCheckBox inScope, hasResp, notFound, params;
    private final JCheckBox html, script, xml, json, css, images, otherText, otherBinary;
    private final JCheckBox s2, s3, s4, s5;
    private final JCheckBox extOnlyOn, extHideOn;
    private final JTextField extOnly, extHide;
    private final JCheckBox notes, highlighted, emptyFolders;
    private final JTextField term;
    private final JCheckBox regex, caseSensitive, negative;

    /**
     * @param siteMap true for the site map, which offers "hide not-found items"
     *                and "hide empty folders"; false for a history table, which
     *                offers "hide items without responses" instead.
     */
    public FilterDialog(Window owner, String title, HistoryFilter filter, boolean siteMap, Runnable onApply) {
        super(owner, title, ModalityType.APPLICATION_MODAL);
        // Dispose on the window-manager close too, not only the buttons.
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        this.f = filter;
        this.opened = filter.copy();
        this.onApply = onApply;
        this.siteMap = siteMap;

        inScope = Ui.check("Show only in-scope items", f.inScopeOnly);
        hasResp = Ui.check("Hide items without responses", f.hideWithoutResponses);
        notFound = Ui.check("Hide not-found items", f.hideNotFound);
        params  = Ui.check("Show only parameterised requests", f.parameterisedOnly);

        html = Ui.check("HTML", f.showHtml);
        script = Ui.check("Script", f.showScript);
        xml = Ui.check("XML", f.showXml);
        json = Ui.check("JSON", f.showJson);
        css = Ui.check("CSS", f.showCss);
        images = Ui.check("Images", f.showImages);
        otherText = Ui.check("Other text", f.showOtherText);
        otherBinary = Ui.check("Other binary", f.showOtherBinary);

        s2 = Ui.check("2xx  [success]", f.show2xx);
        s3 = Ui.check("3xx  [redirection]", f.show3xx);
        s4 = Ui.check("4xx  [request error]", f.show4xx);
        s5 = Ui.check("5xx  [server error]", f.show5xx);

        extOnlyOn = Ui.check("Show only:", f.extShowOnlyOn);
        extHideOn = Ui.check("Hide:", f.extHideOn);
        extOnly = Ui.field(f.extShowOnly, 16);
        extHide = Ui.field(f.extHide, 16);

        notes = Ui.check("Show only items with notes", f.notesOnly);
        highlighted = Ui.check("Show only highlighted items", f.highlightedOnly);
        emptyFolders = Ui.check("Hide empty folders", f.hideEmptyFolders);

        term = Ui.field(f.searchTerm, 22);
        regex = Ui.check("Regex", f.searchRegex);
        caseSensitive = Ui.check("Case sensitive", f.searchCaseSensitive);
        negative = Ui.check("Negative search", f.searchNegative);

        setContentPane(build());
        pack();
        setLocationRelativeTo(owner);
    }

    private JComponent build() {
        JPanel grid = new JPanel(new GridBagLayout());
        grid.setBorder(Ui.pad(12, 12, 6, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(0, 0, 0, 12);
        c.anchor = GridBagConstraints.NORTHWEST;
        c.fill = GridBagConstraints.BOTH;
        c.gridy = 0;

        c.gridx = 0; grid.add(section("Filter by request type",
                siteMap ? stack(inScope, params, notFound) : stack(inScope, hasResp, params)), c);
        c.gridx = 1; grid.add(section("Filter by MIME type",
                two(html, otherText, script, images, xml, css, json, otherBinary)), c);
        c.gridx = 2; grid.add(section("Filter by status code", stack(s2, s3, s4, s5)), c);
        if (siteMap) { c.gridx = 3; grid.add(section("Folders", stack(emptyFolders)), c); }

        c.gridy = 1; c.insets = new Insets(10, 0, 0, 12);
        c.gridx = 0; grid.add(section("Filter by search term",
                stack(term, row(regex), row(caseSensitive, negative))), c);
        c.gridx = 1; grid.add(section("Filter by file extension",
                Ui.form(extOnlyOn, extOnly, extHideOn, extHide)), c);
        c.gridx = 2; c.gridwidth = siteMap ? 2 : 1;
        grid.add(section("Filter by annotation", stack(notes, highlighted)), c);
        c.gridwidth = 1;

        JButton showAll = Ui.button("Show all", "show every item: clear every filter");
        JButton hideAll = Ui.button("Hide all", "hide every content type");
        JButton revert = Ui.button("Revert changes", "put the controls back as they were when this opened");
        JButton apply = Ui.button("Apply");
        JButton applyClose = Ui.primaryButton("Apply & close", "apply the filter and close");
        JButton cancel = Ui.button("Cancel");
        showAll.addActionListener(e -> load(cleared()));
        hideAll.addActionListener(e -> setAllMime(false));
        revert.addActionListener(e -> load(opened));
        apply.addActionListener(e -> commit());
        applyClose.addActionListener(e -> { commit(); dispose(); });
        cancel.addActionListener(e -> dispose());

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        buttons.add(showAll); buttons.add(hideAll); buttons.add(revert);
        buttons.add(Box.createHorizontalStrut(16));
        buttons.add(cancel); buttons.add(apply); buttons.add(applyClose);

        JPanel root = new JPanel(new BorderLayout());
        root.add(grid, BorderLayout.CENTER);
        root.add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(applyClose);
        return root;
    }

    private HistoryFilter cleared() {
        HistoryFilter c = f.copy();
        c.showAll();
        return c;
    }

    private void setAllMime(boolean on) {
        for (JCheckBox b : new JCheckBox[]{html, script, xml, json, css, images, otherText, otherBinary})
            b.setSelected(on);
    }

    /** Put a filter's values into the controls. */
    private void load(HistoryFilter o) {
        inScope.setSelected(o.inScopeOnly); hasResp.setSelected(o.hideWithoutResponses);
        notFound.setSelected(o.hideNotFound); params.setSelected(o.parameterisedOnly);
        html.setSelected(o.showHtml); script.setSelected(o.showScript);
        xml.setSelected(o.showXml); json.setSelected(o.showJson);
        css.setSelected(o.showCss); images.setSelected(o.showImages);
        otherText.setSelected(o.showOtherText); otherBinary.setSelected(o.showOtherBinary);
        s2.setSelected(o.show2xx); s3.setSelected(o.show3xx); s4.setSelected(o.show4xx); s5.setSelected(o.show5xx);
        extOnlyOn.setSelected(o.extShowOnlyOn); extHideOn.setSelected(o.extHideOn);
        extOnly.setText(o.extShowOnly); extHide.setText(o.extHide);
        notes.setSelected(o.notesOnly); highlighted.setSelected(o.highlightedOnly);
        emptyFolders.setSelected(o.hideEmptyFolders);
        term.setText(o.searchTerm); regex.setSelected(o.searchRegex);
        caseSensitive.setSelected(o.searchCaseSensitive); negative.setSelected(o.searchNegative);
    }

    private void commit() {
        f.inScopeOnly = inScope.isSelected();
        f.hideWithoutResponses = hasResp.isSelected();
        f.hideNotFound = notFound.isSelected();
        f.parameterisedOnly = params.isSelected();
        f.showHtml = html.isSelected(); f.showScript = script.isSelected();
        f.showXml = xml.isSelected(); f.showJson = json.isSelected();
        f.showCss = css.isSelected(); f.showImages = images.isSelected();
        f.showOtherText = otherText.isSelected(); f.showOtherBinary = otherBinary.isSelected();
        f.show2xx = s2.isSelected(); f.show3xx = s3.isSelected();
        f.show4xx = s4.isSelected(); f.show5xx = s5.isSelected();
        f.extShowOnlyOn = extOnlyOn.isSelected();
        f.extHideOn = extHideOn.isSelected();
        f.extShowOnly = extOnly.getText().trim();
        f.extHide = extHide.getText().trim();
        f.notesOnly = notes.isSelected();
        f.highlightedOnly = highlighted.isSelected();
        f.hideEmptyFolders = emptyFolders.isSelected();
        f.searchTerm = term.getText();
        f.searchRegex = regex.isSelected();
        f.searchCaseSensitive = caseSensitive.isSelected();
        f.searchNegative = negative.isSelected();
        if (onApply != null) onApply.run();
    }

    // ---- small layout helpers ----------------------------------------------

    private static JComponent section(String title, JComponent body) {
        JPanel p = new JPanel(new BorderLayout());
        TitledBorder tb = new TitledBorder(title);
        tb.setTitleFont(Ui.PLAIN);
        p.setBorder(tb);
        body.setBorder(Ui.pad(4, 6, 6, 6));
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private static JComponent stack(JComponent... parts) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        for (JComponent c : parts) { c.setAlignmentX(Component.LEFT_ALIGNMENT); p.add(c); }
        return p;
    }

    private static JComponent two(JComponent... parts) {
        JPanel p = new JPanel(new GridLayout(0, 2, 10, 0));
        for (JComponent c : parts) p.add(c);
        return p;
    }

    private static JComponent row(JComponent... parts) {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        for (JComponent c : parts) p.add(c);
        return p;
    }
}
