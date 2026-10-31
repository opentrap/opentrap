package opentrap.ui;

import opentrap.core.Issue;
import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Pins the mark that stands in front of an issue.
 *
 * <p>The audit log drew a coloured disc carrying a glyph while the issue lists
 * drew a plain circle, because the disc lived as a private method on the
 * dashboard and the lists reached for a different helper. These cases hold
 * every list to the one badge.
 */
public final class IssueBadgeTest {

    private static BufferedImage paint(Icon icon) {
        BufferedImage img = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        icon.paintIcon(new JLabel(), g, 0, 0);
        g.dispose();
        return img;
    }

    /** How many pixels of the painted mark are the given colour. */
    private static int countOf(BufferedImage img, Color c) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xFFFFFF) == (c.getRGB() & 0xFFFFFF)) n++;
            }
        }
        return n;
    }

    public void run() {
        Harness.suite("issue badge");

        Harness.test("the disc takes its colour from the severity", () -> {
            for (Issue.Severity sev : Issue.Severity.values()) {
                BufferedImage img = paint(Ui.issueBadge(sev, Issue.Confidence.CERTAIN));
                Expect.isTrue(countOf(img, Ui.severityColor(sev)) > 40,
                        sev + " badge is filled with its severity colour");
            }
        });

        Harness.test("the glyph says what kind of finding it is", () -> {
            // A tentative finding carries "?", an informational one "i" and the
            // rest "!", so the three must not paint identically.
            BufferedImage tentative = paint(Ui.issueBadge(Issue.Severity.LOW, Issue.Confidence.TENTATIVE));
            BufferedImage certain = paint(Ui.issueBadge(Issue.Severity.LOW, Issue.Confidence.CERTAIN));
            Expect.isFalse(countOf(tentative, Color.WHITE) == countOf(certain, Color.WHITE),
                    "a tentative badge paints the same as a certain one");

            BufferedImage info = paint(Ui.issueBadge(Issue.Severity.INFORMATION, Issue.Confidence.CERTAIN));
            Expect.isTrue(countOf(info, Color.WHITE) > 0, "the informational badge carries a glyph");
        });

        Harness.test("an issue list shows the badge, not a bare dot", () -> {
            DefaultTableModel model = new DefaultTableModel(
                    new Object[]{"Time", "Source", "Issue type", "Severity", "Confidence"}, 0);
            model.addRow(new Object[]{"now", "Live audit", "Strict transport security", "Low", "Certain"});
            JTable table = new JTable(model);
            Ui.severityIconColumn(table, 2, 3, 4);

            java.awt.Component cell = table.getCellRenderer(0, 2)
                    .getTableCellRendererComponent(table, "Strict transport security", false, false, 0, 2);
            Icon icon = ((JLabel) cell).getIcon();
            Expect.notNull(icon, "the issue-type cell carries a mark");
            Expect.equal(icon.getIconWidth(), 15, "the mark is the badge, whose disc is 15px");
            Expect.isTrue(countOf(paint(icon), Color.WHITE) > 0,
                    "the mark carries a glyph rather than being a plain filled circle");
        });
    }
}
