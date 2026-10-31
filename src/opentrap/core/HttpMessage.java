package opentrap.core;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A parsed HTTP request or response that always retains the original bytes.
 *
 * Nothing here normalises the message. A testing tool must be able to send
 * deliberately malformed traffic, so parsing is advisory and the raw byte
 * array is the source of truth.
 */
public class HttpMessage implements Serializable {
    private static final long serialVersionUID = 1L;

    private byte[] raw;
    private transient Integer bodyOffsetCache;

    public HttpMessage(byte[] raw) { this.raw = raw == null ? new byte[0] : raw; }

    public byte[] raw() { return raw; }

    public void setRaw(byte[] b) {
        this.raw = b == null ? new byte[0] : b;
        this.bodyOffsetCache = null;
    }

    public int length() { return raw.length; }

    /** Offset of the first body byte. Equals length() when there is no body. */
    public int bodyOffset() {
        if (bodyOffsetCache != null) return bodyOffsetCache;
        int i = Bytes.indexOf(raw, Bytes.CRLF2);
        if (i >= 0) { bodyOffsetCache = i + 4; return bodyOffsetCache; }
        // Tolerate bare-LF message framing.
        for (int k = 0; k + 1 < raw.length; k++) {
            if (raw[k] == 10 && raw[k + 1] == 10) { bodyOffsetCache = k + 2; return bodyOffsetCache; }
        }
        bodyOffsetCache = raw.length;
        return bodyOffsetCache;
    }

    public byte[] body() { return Bytes.slice(raw, bodyOffset(), raw.length); }

    public String headerText() { return Bytes.str(Bytes.slice(raw, 0, bodyOffset())); }

    public String bodyText() { return Bytes.str(body()); }

    /** Header lines excluding the start line. */
    public List<String> headerLines() {
        List<String> all = Bytes.lines(headerText());
        List<String> out = new ArrayList<>();
        for (int i = 1; i < all.size(); i++) {
            String l = all.get(i);
            if (l.isEmpty()) break;
            out.add(l);
        }
        return out;
    }

    public String startLine() {
        List<String> l = Bytes.lines(headerText());
        return l.isEmpty() ? "" : l.get(0);
    }

    public String header(String name) {
        String want = name.toLowerCase(Locale.ROOT) + ":";
        for (String l : headerLines()) {
            if (l.toLowerCase(Locale.ROOT).startsWith(want)) {
                return l.substring(want.length()).trim();
            }
        }
        return null;
    }

    public boolean hasHeader(String name) { return header(name) != null; }

    /** Replace a header's value, or append the header when absent. */
    public void setHeader(String name, String value) {
        List<String> lines = Bytes.lines(headerText());
        String want = name.toLowerCase(Locale.ROOT) + ":";
        boolean found = false;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isEmpty()) {
                if (!found) { sb.append(name).append(": ").append(value).append("\r\n"); found = true; }
                sb.append("\r\n");
                // Everything after the blank line is body; stop rewriting headers.
                byte[] rest = Bytes.slice(raw, bodyOffset(), raw.length);
                setRaw(Bytes.concat(Bytes.bytes(sb.toString()), rest));
                return;
            }
            if (i > 0 && l.toLowerCase(Locale.ROOT).startsWith(want)) {
                if (!found) { sb.append(name).append(": ").append(value).append("\r\n"); found = true; }
            } else {
                sb.append(l).append("\r\n");
            }
        }
        if (!found) sb.append(name).append(": ").append(value).append("\r\n");
        sb.append("\r\n");
        setRaw(Bytes.concat(Bytes.bytes(sb.toString()), body()));
    }

    public void removeHeader(String name) {
        List<String> lines = Bytes.lines(headerText());
        String want = name.toLowerCase(Locale.ROOT) + ":";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isEmpty()) break;
            if (i > 0 && l.toLowerCase(Locale.ROOT).startsWith(want)) continue;
            sb.append(l).append("\r\n");
        }
        sb.append("\r\n");
        setRaw(Bytes.concat(Bytes.bytes(sb.toString()), body()));
    }

    public void setBody(byte[] newBody) {
        setRaw(Bytes.concat(Bytes.slice(raw, 0, bodyOffset()), newBody));
    }

    /** Recompute Content-Length to match the actual body. */
    public void updateContentLength() {
        if (bodyOffset() >= raw.length && header("Content-Length") == null) return;
        setHeader("Content-Length", String.valueOf(body().length));
    }

    public boolean isRequest() {
        String s = startLine();
        return !s.toUpperCase(Locale.ROOT).startsWith("HTTP/");
    }

    // ---------------- request accessors ----------------

    public String method() {
        String s = startLine();
        int i = s.indexOf(' ');
        return i < 0 ? "" : s.substring(0, i);
    }

    /** Request target as it appears on the wire, query string included. */
    public String requestTarget() {
        String s = startLine();
        int a = s.indexOf(' ');
        if (a < 0) return "";
        int b = s.indexOf(' ', a + 1);
        return b < 0 ? s.substring(a + 1) : s.substring(a + 1, b);
    }

    public String path() {
        String t = requestTarget();
        if (t.startsWith("http://") || t.startsWith("https://")) {
            int i = t.indexOf('/', t.indexOf("//") + 2);
            t = i < 0 ? "/" : t.substring(i);
        }
        int q = t.indexOf('?');
        return q < 0 ? t : t.substring(0, q);
    }

    public String query() {
        String t = requestTarget();
        int q = t.indexOf('?');
        return q < 0 ? "" : t.substring(q + 1);
    }

    public void setRequestTarget(String target) {
        String s = startLine();
        int a = s.indexOf(' ');
        if (a < 0) return;
        int b = s.indexOf(' ', a + 1);
        String tail = b < 0 ? "" : s.substring(b);
        String newLine = s.substring(0, a + 1) + target + tail;
        byte[] rest = Bytes.slice(raw, lineEnd(0), raw.length);
        setRaw(Bytes.concat(Bytes.bytes(newLine), rest));
    }

    public void setMethod(String m) {
        String s = startLine();
        int a = s.indexOf(' ');
        if (a < 0) return;
        String newLine = m + s.substring(a);
        byte[] rest = Bytes.slice(raw, lineEnd(0), raw.length);
        setRaw(Bytes.concat(Bytes.bytes(newLine), rest));
    }

    private int lineEnd(int lineIndex) {
        int pos = 0, seen = 0;
        while (pos < raw.length) {
            if (raw[pos] == 10) {
                if (seen == lineIndex) return pos;
                seen++;
            }
            pos++;
        }
        return raw.length;
    }

    public String fileExtension() {
        String p = path();
        int slash = p.lastIndexOf('/');
        String last = slash < 0 ? p : p.substring(slash + 1);
        int dot = last.lastIndexOf('.');
        if (dot < 0 || dot == last.length() - 1) return "";
        String ext = last.substring(dot + 1);
        return ext.matches("[A-Za-z0-9]{1,8}") ? ext.toLowerCase(Locale.ROOT) : "";
    }

    // ---------------- response accessors ----------------

    public int statusCode() {
        String s = startLine();
        Matcher m = Pattern.compile("^HTTP/[0-9.]+\\s+(\\d{3})").matcher(s);
        return m.find() ? Integer.parseInt(m.group(1)) : 0;
    }

    public String statusText() {
        String s = startLine();
        Matcher m = Pattern.compile("^HTTP/[0-9.]+\\s+\\d{3}\\s*(.*)$").matcher(s);
        return m.find() ? m.group(1) : "";
    }

    public String contentType() {
        String ct = header("Content-Type");
        if (ct == null) return "";
        int i = ct.indexOf(';');
        return (i < 0 ? ct : ct.substring(0, i)).trim().toLowerCase(Locale.ROOT);
    }

    /**
     * The charset named in the Content-Type header, or "" when none is given.
     * Only the name is returned; whether the bytes are actually valid in it is
     * the caller's business.
     */
    public String charsetName() {
        String ct = header("Content-Type");
        if (ct == null) return "";
        for (String part : ct.split(";")) {
            String p = part.trim();
            if (p.regionMatches(true, 0, "charset=", 0, 8)) {
                String v = p.substring(8).trim();
                if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) v = v.substring(1, v.length() - 1);
                return v;
            }
        }
        return "";
    }

    /** Coarse MIME grouping used by the history and site map filters. */
    public String mimeType() {
        String ct = contentType();
        if (ct.isEmpty()) {
            byte[] b = body();
            if (b.length == 0) return "";
            return Bytes.isPrintableText(b, 256) ? "other text" : "other binary";
        }
        if (ct.contains("html")) return "HTML";
        if (ct.contains("javascript") || ct.contains("ecmascript")) return "script";
        if (ct.contains("json")) return "JSON";
        if (ct.contains("xml")) return "XML";
        if (ct.startsWith("image/")) return "images";
        if (ct.startsWith("audio/") || ct.startsWith("video/")) return "sound";
        if (ct.contains("css")) return "CSS";
        if (ct.startsWith("text/")) return "other text";
        return "other binary";
    }

    private static final String TITLE_OPEN = "<title";
    private static final String TITLE_CLOSE = "</title";

    public String htmlTitle() {
        if (!"HTML".equals(mimeType())) return "";
        // A linear scan, not a regex: "<title[^>]*>(.*?)</title>" recursed one
        // Pattern frame per position while hunting the closing tag across a
        // large body - the same event-queue stack overflow the JSON colouring
        // had. Only the title text itself is materialised.
        byte[] r = raw();
        int open = Bytes.indexOfIgnoreCase(r, Bytes.bytes(TITLE_OPEN), bodyOffset());
        if (open < 0) return "";
        int gt = open;
        while (gt < r.length && r[gt] != '>') gt++;
        if (gt >= r.length) return "";
        int close = Bytes.indexOfIgnoreCase(r, Bytes.bytes(TITLE_CLOSE), gt + 1);
        if (close < 0 || close <= gt + 1) return "";
        return Bytes.str(Bytes.slice(r, gt + 1, close)).replaceAll("\\s+", " ").trim();
    }

    // ---------------- parameters ----------------

    public List<Param> parameters() {
        List<Param> out = new ArrayList<>();
        if (!isRequest()) return out;

        // Query string parameters, offsets relative to the whole message.
        String target = requestTarget();
        int targetStart = startLine().indexOf(' ') + 1;
        int q = target.indexOf('?');
        if (q >= 0) {
            parsePairs(target.substring(q + 1), targetStart + q + 1, Param.Type.URL, out, '&');
        }

        // Cookies.
        String cookie = header("Cookie");
        if (cookie != null) {
            int base = Bytes.indexOfIgnoreCase(raw, Bytes.bytes("Cookie:"), 0);
            if (base >= 0) {
                int vstart = base + "Cookie:".length();
                while (vstart < raw.length && (raw[vstart] == ' ' || raw[vstart] == '\t')) vstart++;
                parsePairs(cookie, vstart, Param.Type.COOKIE, out, ';');
            }
        }

        // Structured bodies: form-urlencoded, JSON, XML or multipart. Body char
        // offsets map one-to-one to byte offsets because Bytes.str is ISO-8859-1.
        String ct = contentType();
        if (ct.contains("x-www-form-urlencoded")) {
            parsePairs(bodyText(), bodyOffset(), Param.Type.BODY, out, '&');
        } else if (ct.contains("json") || (ct.isEmpty() && looksLikeJson())) {
            parseJsonParams(bodyText(), bodyOffset(), out);
        } else if (ct.contains("xml")) {
            parseXmlParams(bodyText(), bodyOffset(), out);
        } else if (ct.contains("multipart/")) {
            parseMultipartParams(out);
        } else if (ct.isEmpty() && body().length > 0 && bodyText().contains("=")) {
            parsePairs(bodyText(), bodyOffset(), Param.Type.BODY, out, '&');
        }
        return out;
    }

    private boolean looksLikeJson() {
        String t = bodyText().trim();
        return !t.isEmpty() && (t.charAt(0) == '{' || t.charAt(0) == '[');
    }

    // ---- JSON ----
    // A tolerant scalar scanner: it records one parameter per string, number or
    // boolean/null leaf, named by its enclosing object key (array elements share
    // the array's key). Malformed input stops the scan with whatever was found.

    private void parseJsonParams(String s, int base, List<Param> out) {
        try {
            int[] p = {0};
            jsonValue(s, p, base, "", out, 0);
        } catch (Exception | StackOverflowError ignored) {
            // Advisory parsing: a body that is not JSON yields no parameters.
        }
    }

    /**
     * JSON nesting is bounded before the recursive walk so a hostile body of
     * deeply nested brackets cannot overflow the stack. The parameters panel
     * runs this walk on the Swing event thread, where an overflow would kill
     * the interface mid-paint; the belt-and-braces catch above contains
     * anything the bound somehow misses. Real request bodies sit far below
     * this depth.
     */
    private static final int MAX_JSON_DEPTH = 200;

    private static boolean jsonWs(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }
    private static void jsonSkipWs(String s, int[] p) { while (p[0] < s.length() && jsonWs(s.charAt(p[0]))) p[0]++; }

    private void jsonValue(String s, int[] p, int base, String name, List<Param> out, int depth) {
        if (depth > MAX_JSON_DEPTH) return;
        jsonSkipWs(s, p);
        if (p[0] >= s.length()) return;
        char c = s.charAt(p[0]);
        if (c == '{') { jsonObject(s, p, base, out, depth); }
        else if (c == '[') { jsonArray(s, p, base, name, out, depth); }
        else if (c == '"') {
            int[] span = new int[2];
            String v = jsonString(s, p, span);
            out.add(new Param(name, v, Param.Type.JSON, base + span[0], base + span[0], base + span[0], base + span[1]));
        } else {
            int start = p[0];
            while (p[0] < s.length() && "-+.eE0123456789truefalsn".indexOf(s.charAt(p[0])) >= 0) p[0]++;
            int end = p[0];
            if (end > start) {
                out.add(new Param(name, s.substring(start, end), Param.Type.JSON, base + start, base + start, base + start, base + end));
            } else {
                p[0]++; // unknown byte: advance so the scan always terminates
            }
        }
    }

    private void jsonObject(String s, int[] p, int base, List<Param> out, int depth) {
        p[0]++; // consume '{'
        while (p[0] < s.length()) {
            jsonSkipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == '}') { p[0]++; return; }
            if (p[0] >= s.length() || s.charAt(p[0]) != '"') return;
            int[] span = new int[2];
            String key = jsonString(s, p, span);
            jsonSkipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ':') p[0]++;
            jsonValue(s, p, base, key, out, depth + 1);
            jsonSkipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
            if (p[0] < s.length() && s.charAt(p[0]) == '}') { p[0]++; return; }
            return;
        }
    }

    private void jsonArray(String s, int[] p, int base, String name, List<Param> out, int depth) {
        p[0]++; // consume '['
        while (p[0] < s.length()) {
            jsonSkipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ']') { p[0]++; return; }
            jsonValue(s, p, base, name, out, depth + 1);
            jsonSkipWs(s, p);
            if (p[0] < s.length() && s.charAt(p[0]) == ',') { p[0]++; continue; }
            if (p[0] < s.length() && s.charAt(p[0]) == ']') { p[0]++; return; }
            return;
        }
    }

    /** Reads a JSON string at p[0]=='"'. span holds the inner content offsets. */
    private String jsonString(String s, int[] p, int[] span) {
        p[0]++; // opening quote
        int start = p[0];
        StringBuilder sb = new StringBuilder();
        while (p[0] < s.length()) {
            char c = s.charAt(p[0]);
            if (c == '\\' && p[0] + 1 < s.length()) { sb.append(c).append(s.charAt(p[0] + 1)); p[0] += 2; continue; }
            if (c == '"') break;
            sb.append(c);
            p[0]++;
        }
        span[0] = start;
        span[1] = p[0];
        if (p[0] < s.length()) p[0]++; // closing quote
        return sb.toString();
    }

    // ---- XML ----
    // Records element text and attribute values. Comments, CDATA, processing
    // instructions and declarations are skipped.

    private void parseXmlParams(String s, int base, List<Param> out) {
        try {
            int i = 0, n = s.length();
            while (i < n) {
                if (s.charAt(i) != '<') { i++; continue; }
                if (s.startsWith("<!--", i)) { int e = s.indexOf("-->", i); i = e < 0 ? n : e + 3; continue; }
                if (s.startsWith("<![CDATA[", i)) { int e = s.indexOf("]]>", i); i = e < 0 ? n : e + 3; continue; }
                if (s.startsWith("<?", i)) { int e = s.indexOf("?>", i); i = e < 0 ? n : e + 2; continue; }
                if (s.startsWith("<!", i)) { int e = s.indexOf('>', i); i = e < 0 ? n : e + 1; continue; }
                boolean closing = s.startsWith("</", i);
                int gt = s.indexOf('>', i);
                if (gt < 0) break;
                String inner = s.substring(closing ? i + 2 : i + 1, gt).trim();
                boolean selfClose = inner.endsWith("/");
                if (selfClose) inner = inner.substring(0, inner.length() - 1).trim();
                int sp = xmlWs(inner);
                String ename = sp < 0 ? inner : inner.substring(0, sp);
                if (!closing) parseXmlAttrs(s, i, gt, base, out);
                i = gt + 1;
                if (!closing && !selfClose) {
                    int lt = s.indexOf('<', i);
                    int te = lt < 0 ? n : lt;
                    int ts = i;
                    while (ts < te && Character.isWhitespace(s.charAt(ts))) ts++;
                    int tend = te;
                    while (tend > ts && Character.isWhitespace(s.charAt(tend - 1))) tend--;
                    if (tend > ts) {
                        out.add(new Param(ename, s.substring(ts, tend), Param.Type.XML, base + ts, base + ts, base + ts, base + tend));
                    }
                    i = te;
                }
            }
        } catch (Exception ignored) {}
    }

    private static int xmlWs(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return i;
        return -1;
    }

    private void parseXmlAttrs(String s, int from, int to, int base, List<Param> out) {
        int i = from;
        while (i < to && !Character.isWhitespace(s.charAt(i))) i++; // skip the element name
        while (i < to) {
            int eq = s.indexOf('=', i);
            if (eq < 0 || eq >= to) break;
            int ne = eq;
            while (ne > from && Character.isWhitespace(s.charAt(ne - 1))) ne--;
            int ns = ne;
            while (ns > from) {
                char c = s.charAt(ns - 1);
                if (Character.isWhitespace(c) || c == '<' || c == '/' || c == '"' || c == '\'') break;
                ns--;
            }
            String aname = s.substring(ns, ne);
            int q = eq + 1;
            while (q < to && Character.isWhitespace(s.charAt(q))) q++;
            if (q >= to) break;
            char quote = s.charAt(q);
            if (quote != '"' && quote != '\'') { i = eq + 1; continue; }
            int vs = q + 1;
            int ve = s.indexOf(quote, vs);
            if (ve < 0 || ve > to) break;
            if (!aname.isEmpty()) {
                out.add(new Param(aname, s.substring(vs, ve), Param.Type.XML, base + vs, base + vs, base + vs, base + ve));
            }
            i = ve + 1;
        }
    }

    // ---- multipart/form-data ----

    private void parseMultipartParams(List<Param> out) {
        String ctFull = header("Content-Type");
        if (ctFull == null) return;
        String boundary = null;
        for (String part : ctFull.split(";")) {
            String t = part.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                boundary = t.substring("boundary=".length()).trim();
                if (boundary.length() >= 2 && boundary.startsWith("\"") && boundary.endsWith("\"")) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
            }
        }
        if (boundary == null || boundary.isEmpty()) return;
        String body = bodyText();
        int base = bodyOffset();
        String delim = "--" + boundary;
        int i = body.indexOf(delim);
        while (i >= 0) {
            int partStart = i + delim.length();
            int next = body.indexOf(delim, partStart);
            int partEnd = next < 0 ? body.length() : next;
            int sep = 4;
            int hdrEnd = body.indexOf("\r\n\r\n", partStart);
            if (hdrEnd < 0 || hdrEnd >= partEnd) { hdrEnd = body.indexOf("\n\n", partStart); sep = 2; }
            if (hdrEnd >= 0 && hdrEnd < partEnd) {
                String headers = body.substring(partStart, hdrEnd);
                String name = multipartName(headers);
                int vs = hdrEnd + sep;
                int ve = partEnd;
                while (ve > vs && (body.charAt(ve - 1) == '\n' || body.charAt(ve - 1) == '\r')) ve--;
                if (!name.isEmpty() && ve >= vs) {
                    out.add(new Param(name, body.substring(vs, ve), Param.Type.MULTIPART, base + vs, base + vs, base + vs, base + ve));
                }
            }
            if (next < 0) break;
            i = next;
        }
    }

    private static final Pattern MULTIPART_NAME = Pattern.compile("name=\"([^\"]*)\"");

    private static String multipartName(String headers) {
        for (String line : headers.split("\r\n|\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-disposition:")) {
                Matcher m = MULTIPART_NAME.matcher(line);
                if (m.find()) return m.group(1);
            }
        }
        return "";
    }

    private void parsePairs(String s, int base, Param.Type type, List<Param> out, char sep) {
        int i = 0;
        while (i <= s.length()) {
            int end = s.indexOf(sep, i);
            if (end < 0) end = s.length();
            String pair = s.substring(i, end);
            if (!pair.isBlank()) {
                int lead = 0;
                while (lead < pair.length() && pair.charAt(lead) == ' ') lead++;
                int eq = pair.indexOf('=');
                int pairStart = base + i + lead;
                if (eq < 0) {
                    out.add(new Param(pair.trim(), "", type,
                            pairStart, pairStart + pair.trim().length(),
                            pairStart + pair.trim().length(), pairStart + pair.trim().length()));
                } else {
                    String n = pair.substring(lead, eq);
                    String v = pair.substring(eq + 1);
                    int ns = pairStart;
                    int ne = ns + n.length();
                    int vs = ne + 1;
                    out.add(new Param(n, v, type, ns, ne, vs, vs + v.length()));
                }
            }
            if (end >= s.length()) break;
            i = end + 1;
        }
    }

    public boolean hasParameters() { return !parameters().isEmpty(); }

    public HttpMessage copy() { return new HttpMessage(raw.clone()); }

    @Override public String toString() {
        return isRequest() ? method() + " " + requestTarget() : startLine();
    }
}
