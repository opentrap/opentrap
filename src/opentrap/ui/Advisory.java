package opentrap.ui;

import opentrap.core.Issue;

import java.util.List;

/**
 * Renders an issue as the advisory shown on the target and dashboard tabs.
 *
 * <p>Both tabs used to build this markup themselves and had drifted apart, so
 * the same finding read differently depending on where you clicked. One
 * renderer keeps them identical and means a new issue field has one place to be
 * added.
 *
 * <p>Everything interpolated is escaped, and reference URLs are rendered as
 * plain text rather than anchors. The advisory pane is a Swing HTML view, and a
 * live anchor in a pane that also displays attacker-influenced strings is an
 * invitation; a tester can copy the URL.
 */
public final class Advisory {

    private Advisory() {}

    private static String colourFor(Issue.Severity s) {
        return switch (s) {
            case HIGH        -> "#E0474C";
            case MEDIUM      -> "#7E57C2";
            case LOW         -> "#4477CC";
            case INFORMATION -> "#888888";
        };
    }

    /** The full advisory, for the target tab's wide panel. */
    public static String render(Issue i) {
        return render(i, true);
    }

    /** A colour as CSS hex, so the advisory follows the light or dark theme. */
    private static String hex(java.awt.Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** The advisory, optionally without the location table for a narrow panel. */
    public static String render(Issue i, boolean withLocation) {
        if (i == null) return "<html><body></body></html>";
        StringBuilder b = new StringBuilder(1024);
        b.append("<html><body style='font-family:sans-serif;font-size:11px;padding:6px;color:")
         .append(hex(Theme.TEXT)).append("'>");
        b.append("<h2 style='margin:0 0 6px 0'>").append(esc(i.title())).append("</h2>");

        b.append("<table cellpadding=2>");
        row(b, "Severity", "<span style='color:" + colourFor(i.severity()) + "'>"
                + esc(i.severity().label()) + "</span>");
        row(b, "Confidence", esc(i.confidence().label()));
        if (withLocation) {
            row(b, "Host", esc(i.service() == null ? "" : i.service().url()));
            if (i.locationCount() > 1) row(b, "Path", esc(i.path()) + "  <i>(+" + (i.locationCount() - 1) + " more below)</i>");
            else row(b, "Path", esc(i.path()));
        }
        if (i.param() != null && !i.param().isBlank()) row(b, "Parameter", esc(i.param()));

        // Classification, only the parts the raising module supplied.
        if (i.cwe() > 0)  row(b, "CWE", "CWE-" + i.cwe());
        if (i.wasc() > 0) row(b, "WASC", "WASC-" + i.wasc());
        List<String> tags = i.tags();
        if (tags != null && !tags.isEmpty()) row(b, "Classification", esc(String.join(", ", tags)));
        if (i.moduleId() > 0) row(b, "Module", String.valueOf(i.moduleId()));
        b.append("</table>");

        if (withLocation && i.locationCount() > 1) {
            b.append("<h3>Affected locations (").append(i.locationCount()).append(")</h3><ul style='margin:0 0 0 14px;padding:0'>");
            String host = i.service() == null ? "" : i.service().url();
            for (String loc : i.locations()) b.append("<li>").append(esc(host)).append(esc(loc)).append("</li>");
            b.append("</ul>");
        }

        if (!i.evidence().isBlank()) {
            b.append("<h3>Evidence</h3><pre style='font-family:monospace;font-size:11px;")
             .append("background:").append(hex(Theme.SURFACE_2)).append(";padding:4px;white-space:pre-wrap'>")
             .append(esc(trim(i.evidence(), 2000)))
             .append("</pre>");
        }
        section(b, "Issue detail", i.detail());
        section(b, "Issue background", i.background());
        section(b, "Remediation", i.remediation());

        if (!i.references().isBlank()) {
            b.append("<h3>References</h3><div style='color:").append(hex(Theme.MUTED)).append("'>");
            for (String line : i.references().split("\\R")) {
                if (!line.isBlank()) b.append(esc(line.trim())).append("<br>");
            }
            b.append("</div>");
        }
        b.append("</body></html>");
        return b.toString();
    }

    /** A compact single-finding advisory for the dashboard's narrower pane. */
    public static String renderCompact(Issue i) {
        return render(i, false);
    }

    private static void row(StringBuilder b, String label, String value) {
        b.append("<tr><td valign='top'><b>").append(label).append(":</b></td><td>")
         .append(value).append("</td></tr>");
    }

    private static void section(StringBuilder b, String heading, String body) {
        if (body == null || body.isBlank()) return;
        b.append("<h3>").append(heading).append("</h3><p>")
         .append(esc(body).replace("\n", "<br>")).append("</p>");
    }

    private static String trim(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    /** Escape for HTML. Must be applied to every interpolated value. */
    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
