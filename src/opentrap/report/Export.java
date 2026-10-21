package opentrap.report;

import opentrap.core.Issue;
import opentrap.core.LogEntry;
import opentrap.ui.Advisory;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import java.awt.Component;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

/**
 * Writes findings and traffic to disk: an HTML or XML issue report, and logged
 * traffic as CSV or HAR 1.2. Output is files only (no PDF), as agreed.
 */
public final class Export {

    private Export() {}

    // ---------------- issue report ----------------

    public static String issuesHtml(List<Issue> issues) {
        int high = 0, med = 0, low = 0, info = 0;
        for (Issue i : issues) switch (i.severity()) {
            case HIGH -> high++; case MEDIUM -> med++; case LOW -> low++; default -> info++;
        }
        StringBuilder b = new StringBuilder();
        b.append("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">\n");
        b.append("<title>OpenTRAP report</title>\n<style>\n");
        b.append("body{font-family:system-ui,Segoe UI,Helvetica,Arial,sans-serif;margin:32px;color:#1c2733;}\n");
        b.append("h1{color:#DB612F;} h2{margin:0 0 4px;} .meta{color:#667;} hr{border:0;border-top:1px solid #dde3ea;margin:24px 0;}\n");
        b.append(".sev{display:inline-block;padding:1px 8px;border-radius:10px;color:#fff;font-size:12px;font-weight:600;}\n");
        b.append(".High{background:#E0474C;}.Medium{background:#7E57C2;}.Low{background:#4477CC;}.Information{background:#888;}\n");
        b.append("pre{background:#f5f7fa;border:1px solid #dde3ea;padding:10px;overflow:auto;white-space:pre-wrap;word-break:break-all;}\n");
        b.append("</style></head><body>\n");
        b.append("<h1>OpenTRAP findings</h1>\n");
        b.append("<p class=\"meta\">Generated ").append(Advisory.esc(Instant.now().toString()))
                .append(" &middot; ").append(issues.size()).append(" findings &middot; ")
                .append("High ").append(high).append(", Medium ").append(med)
                .append(", Low ").append(low).append(", Information ").append(info).append("</p>\n");
        for (Issue i : issues) {
            b.append("<hr>\n<h2>").append(Advisory.esc(i.title())).append("</h2>\n");
            b.append("<p><span class=\"sev ").append(i.severity().label()).append("\">")
                    .append(i.severity().label()).append("</span> &middot; confidence ")
                    .append(Advisory.esc(i.confidence().label())).append("</p>\n");
            b.append("<p class=\"meta\">").append(Advisory.esc(i.host())).append(Advisory.esc(i.path()));
            if (i.param() != null && !i.param().isEmpty()) b.append(" &middot; parameter ").append(Advisory.esc(i.param()));
            b.append("</p>\n");
            section(b, "Issue detail", i.detail());
            section(b, "Background", i.background());
            section(b, "Remediation", i.remediation());
            section(b, "Evidence", i.evidence());
            if (i.cwe() > 0) b.append("<p class=\"meta\">CWE-").append(i.cwe()).append("</p>\n");
            if (i.request() != null) pre(b, "Request", i.request().raw());
            if (i.response() != null) pre(b, "Response", i.response().raw());
        }
        b.append("</body></html>\n");
        return b.toString();
    }

    private static void section(StringBuilder b, String title, String body) {
        if (body == null || body.isBlank()) return;
        b.append("<p><b>").append(title).append(":</b> ").append(Advisory.esc(body)).append("</p>\n");
    }

    private static void pre(StringBuilder b, String title, byte[] raw) {
        String s = new String(raw, StandardCharsets.ISO_8859_1);
        if (s.length() > 4000) s = s.substring(0, 4000) + "\n... (truncated)";
        b.append("<p><b>").append(title).append("</b></p>\n<pre>").append(Advisory.esc(s)).append("</pre>\n");
    }

    public static String issuesXml(List<Issue> issues) {
        StringBuilder b = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<issues>\n");
        for (Issue i : issues) {
            b.append("  <issue>\n");
            el(b, "title", i.title());
            el(b, "severity", i.severity().label());
            el(b, "confidence", i.confidence().label());
            el(b, "host", i.host());
            el(b, "path", i.path());
            el(b, "parameter", i.param());
            el(b, "cwe", i.cwe() > 0 ? String.valueOf(i.cwe()) : "");
            el(b, "detail", i.detail());
            el(b, "background", i.background());
            el(b, "remediation", i.remediation());
            el(b, "evidence", i.evidence());
            b.append("  </issue>\n");
        }
        return b.append("</issues>\n").toString();
    }

    private static void el(StringBuilder b, String tag, String value) {
        b.append("    <").append(tag).append('>').append(xml(value)).append("</").append(tag).append(">\n");
    }

    private static String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    // ---------------- traffic ----------------

    public static String trafficCsv(List<LogEntry> entries) {
        StringBuilder b = new StringBuilder("number,time,tool,method,url,status,length,mime,duration_ms,remote_ip\n");
        for (LogEntry e : entries) {
            b.append(e.number()).append(',')
             .append(csv(String.valueOf(e.time()))).append(',')
             .append(csv(e.tool())).append(',')
             .append(csv(e.request() == null ? "" : e.request().method())).append(',')
             .append(csv(e.url())).append(',')
             .append(e.status()).append(',')
             .append(e.responseLen()).append(',')
             .append(csv(e.mimeType())).append(',')
             .append(e.durationMillis()).append(',')
             .append(csv(e.remoteIp())).append('\n');
        }
        return b.toString();
    }

    private static String csv(String s) {
        if (s == null) s = "";
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) return '"' + s.replace("\"", "\"\"") + '"';
        return s;
    }

    public static String trafficHar(List<LogEntry> entries) {
        StringBuilder b = new StringBuilder();
        b.append("{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"OpenTRAP\",\"version\":\"1\"},\"entries\":[");
        boolean first = true;
        for (LogEntry e : entries) {
            if (!first) b.append(',');
            first = false;
            b.append("\n{\"startedDateTime\":\"").append(js(Instant.ofEpochMilli(e.time().getTime()).toString()))
             .append("\",\"time\":").append(e.durationMillis()).append(',');
            harMessage(b, "request", e.request(), e.url());
            b.append(',');
            harMessage(b, "response", e.response(), null);
            b.append(",\"cache\":{},\"timings\":{\"send\":0,\"wait\":").append(e.durationMillis()).append(",\"receive\":0}}");
        }
        b.append("\n]}}\n");
        return b.toString();
    }

    private static void harMessage(StringBuilder b, String key, opentrap.core.HttpMessage m, String url) {
        b.append('"').append(key).append("\":{");
        if (m == null) {
            if ("request".equals(key)) b.append("\"method\":\"\",\"url\":\"").append(js(url == null ? "" : url))
                    .append("\",\"httpVersion\":\"HTTP/1.1\",\"headers\":[],\"queryString\":[],\"cookies\":[],\"headersSize\":-1,\"bodySize\":0");
            else b.append("\"status\":0,\"statusText\":\"\",\"httpVersion\":\"HTTP/1.1\",\"headers\":[],\"cookies\":[],"
                    + "\"content\":{\"size\":0,\"mimeType\":\"\",\"text\":\"\"},\"redirectURL\":\"\",\"headersSize\":-1,\"bodySize\":0");
            b.append('}');
            return;
        }
        // headers
        StringBuilder hdr = new StringBuilder("[");
        boolean f = true;
        for (String line : m.headerLines()) {
            int c = line.indexOf(':');
            if (c < 0) continue;
            if (!f) hdr.append(',');
            f = false;
            hdr.append("{\"name\":\"").append(js(line.substring(0, c).trim()))
               .append("\",\"value\":\"").append(js(line.substring(c + 1).trim())).append("\"}");
        }
        hdr.append(']');
        String body = m.bodyText();
        if ("request".equals(key)) {
            b.append("\"method\":\"").append(js(m.method())).append("\",\"url\":\"").append(js(url == null ? "" : url))
             .append("\",\"httpVersion\":\"HTTP/1.1\",\"headers\":").append(hdr)
             .append(",\"queryString\":[],\"cookies\":[],\"headersSize\":-1,\"bodySize\":").append(body.length());
            if (!body.isEmpty()) {
                b.append(",\"postData\":{\"mimeType\":\"").append(js(m.contentType()))
                 .append("\",\"text\":\"").append(js(body)).append("\"}");
            }
        } else {
            b.append("\"status\":").append(m.statusCode()).append(",\"statusText\":\"").append(js(m.statusText()))
             .append("\",\"httpVersion\":\"HTTP/1.1\",\"headers\":").append(hdr).append(",\"cookies\":[],")
             .append("\"content\":{\"size\":").append(body.length()).append(",\"mimeType\":\"").append(js(m.contentType()))
             .append("\",\"text\":\"").append(js(body)).append("\"},\"redirectURL\":\"\",\"headersSize\":-1,\"bodySize\":")
             .append(body.length());
        }
        b.append('}');
    }

    private static String js(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> { if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); }
            }
        }
        return b.toString();
    }

    // ---------------- save helper ----------------

    public static void save(Component parent, String suggestedName, String content) {
        JFileChooser fc = new JFileChooser();
        fc.setDialogTitle("export");
        fc.setSelectedFile(new java.io.File(suggestedName));
        if (fc.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;
        try (PrintWriter w = new PrintWriter(fc.getSelectedFile(), StandardCharsets.UTF_8)) {
            w.print(content);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(parent, "export failed: " + e.getMessage(),
                    "export", JOptionPane.ERROR_MESSAGE);
        }
    }
}
