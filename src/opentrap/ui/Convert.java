package opentrap.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * The "Convert selection" transforms: encode and decode a selected string in
 * place, the way the reference editor does. Each is a pure String to String map
 * over the selection. Bytes are handled as ISO-8859-1 so a round trip is exact.
 */
public final class Convert {
    private Convert() {}

    private static final java.nio.charset.Charset L1 = StandardCharsets.ISO_8859_1;

    // ---------------- URL ----------------

    // The characters that are significant in a URL and so are encoded by the
    // "key characters" options; everything else is left as typed.
    private static final String URL_KEY = " \"<>#%{}|\\^~[]`&=+?;/:@$,'";

    public static String urlDecode(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%' && i + 5 < s.length() && (s.charAt(i + 1) == 'u' || s.charAt(i + 1) == 'U')) {
                try { b.append((char) Integer.parseInt(s.substring(i + 2, i + 6), 16)); i += 5; continue; }
                catch (Exception ignore) {}
            }
            if (c == '%' && i + 2 < s.length()) {
                try { b.append((char) Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; continue; }
                catch (Exception ignore) {}
            }
            b.append(c);
        }
        return b.toString();
    }

    public static String urlEncodeKey(String s)  { return urlEncode(s, true, false); }
    public static String urlEncodeAll(String s)  { return urlEncode(s, false, false); }
    public static String urlEncodeKeyU(String s) { return urlEncode(s, true, true); }
    public static String urlEncodeAllU(String s) { return urlEncode(s, false, true); }

    private static String urlEncode(String s, boolean keyOnly, boolean unicode) {
        StringBuilder b = new StringBuilder();
        if (unicode) {
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (keyOnly && URL_KEY.indexOf(c) < 0 && c >= 0x20 && c < 0x7f) b.append(c);
                else b.append(String.format("%%u%04x", (int) c));
            }
        } else {
            for (byte by : s.getBytes(StandardCharsets.UTF_8)) {
                char c = (char) (by & 0xFF);
                if (keyOnly && URL_KEY.indexOf(c) < 0 && c >= 0x20 && c < 0x7f) b.append(c);
                else b.append('%').append(hex(by));
            }
        }
        return b.toString();
    }

    // ---------------- HTML ----------------

    public static String htmlEncodeKey(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }

    public static String htmlEncodeAll(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) b.append("&#").append((int) s.charAt(i)).append(';');
        return b.toString();
    }

    public static String htmlEncodeHex(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) b.append("&#x").append(Integer.toHexString(s.charAt(i))).append(';');
        return b.toString();
    }

    public static String htmlDecode(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') { b.append(c); continue; }
            int semi = s.indexOf(';', i);
            if (semi < 0 || semi - i > 12) { b.append(c); continue; }
            String ent = s.substring(i + 1, semi);
            String dec = decodeEntity(ent);
            if (dec == null) { b.append(c); continue; }
            b.append(dec); i = semi;
        }
        return b.toString();
    }

    private static String decodeEntity(String ent) {
        try {
            if (ent.startsWith("#x") || ent.startsWith("#X")) return String.valueOf((char) Integer.parseInt(ent.substring(2), 16));
            if (ent.startsWith("#")) return String.valueOf((char) Integer.parseInt(ent.substring(1)));
        } catch (Exception e) { return null; }
        return switch (ent) {
            case "lt" -> "<"; case "gt" -> ">"; case "amp" -> "&"; case "quot" -> "\"";
            case "apos" -> "'"; case "nbsp" -> " "; case "copy" -> "©"; case "reg" -> "®";
            default -> null;
        };
    }

    // ---------------- Base64 ----------------

    public static String base64Encode(String s)    { return Base64.getEncoder().encodeToString(s.getBytes(L1)); }
    public static String base64Decode(String s)    { return new String(Base64.getMimeDecoder().decode(s.trim()), L1); }
    public static String base64UrlEncode(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(L1)); }
    public static String base64UrlDecode(String s) {
        String t = s.trim();
        int pad = (4 - t.length() % 4) % 4;
        return new String(Base64.getUrlDecoder().decode(t + "=".repeat(pad)), L1);
    }

    // ---------------- Construct string ----------------

    public static String constructJs(String s) {
        return "String.fromCharCode(" + codesCsv(s) + ")";
    }
    public static String constructMysql(String s) {
        return "CHAR(" + codesCsv(s) + ")";
    }
    public static String constructMssql(String s) {
        return joinCodes(s, "CHAR(", ")", "+");
    }
    public static String constructOracle(String s) {
        return joinCodes(s, "CHR(", ")", "||");
    }

    private static String codesCsv(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { if (i > 0) b.append(','); b.append((int) s.charAt(i)); }
        return b.toString();
    }
    private static String joinCodes(String s, String pre, String post, String sep) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) { if (i > 0) b.append(sep); b.append(pre).append((int) s.charAt(i)).append(post); }
        return b.toString();
    }

    private static String hex(byte by) { return String.format("%02x", by & 0xFF); }
}
