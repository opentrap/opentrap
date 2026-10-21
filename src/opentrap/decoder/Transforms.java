package opentrap.decoder;

import opentrap.core.Bytes;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.Security;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * The decode, encode and hash operations offered by the decoder. Everything
 * works on bytes: text is only ever a 1:1 ISO-8859-1 view of those bytes, so a
 * decode followed by the matching encode gives back what you started with.
 */
final class Transforms {
    private Transforms() {}

    static final String DECODE = "decode";
    static final String ENCODE = "encode";
    static final String HASH   = "hash";

    /** Raised for input a decoder cannot make sense of. */
    static final class Failure extends Exception {
        Failure(String message) { super(message); }
    }

    /** Transform names in menu order. The leading space is the house style. */
    private static final String[] NAMES = {
        " plain", " url", " html", " base64", " ascii hex", " hex", " octal", " binary",
        " gzip", " deflate", " brotli"
    };

    private static final Pattern PERCENT = Pattern.compile("%[0-9a-fA-F]{2}");
    private static final Pattern ENTITY  = Pattern.compile("&(#[0-9]+|#[xX][0-9a-fA-F]+|[a-zA-Z]{2,10});");

    private static final Map<String, Integer> NAMED_ENTITIES = new HashMap<>();
    static {
        NAMED_ENTITIES.put("amp", 38);
        NAMED_ENTITIES.put("lt", 60);
        NAMED_ENTITIES.put("gt", 62);
        NAMED_ENTITIES.put("quot", 34);
        NAMED_ENTITIES.put("apos", 39);
        NAMED_ENTITIES.put("nbsp", 160);
        // A fuller set of common named entities beyond the original five.
        NAMED_ENTITIES.put("copy", 169);  NAMED_ENTITIES.put("reg", 174);  NAMED_ENTITIES.put("trade", 8482);
        NAMED_ENTITIES.put("hellip", 8230); NAMED_ENTITIES.put("mdash", 8212); NAMED_ENTITIES.put("ndash", 8211);
        NAMED_ENTITIES.put("lsquo", 8216); NAMED_ENTITIES.put("rsquo", 8217); NAMED_ENTITIES.put("ldquo", 8220);
        NAMED_ENTITIES.put("rdquo", 8221); NAMED_ENTITIES.put("laquo", 171); NAMED_ENTITIES.put("raquo", 187);
        NAMED_ENTITIES.put("euro", 8364); NAMED_ENTITIES.put("pound", 163); NAMED_ENTITIES.put("cent", 162);
        NAMED_ENTITIES.put("yen", 165);   NAMED_ENTITIES.put("sect", 167);  NAMED_ENTITIES.put("para", 182);
        NAMED_ENTITIES.put("middot", 183); NAMED_ENTITIES.put("bull", 8226); NAMED_ENTITIES.put("dagger", 8224);
        NAMED_ENTITIES.put("times", 215); NAMED_ENTITIES.put("divide", 247); NAMED_ENTITIES.put("deg", 176);
        NAMED_ENTITIES.put("plusmn", 177); NAMED_ENTITIES.put("micro", 181); NAMED_ENTITIES.put("frac12", 189);
        NAMED_ENTITIES.put("frac14", 188); NAMED_ENTITIES.put("frac34", 190); NAMED_ENTITIES.put("sup2", 178);
        NAMED_ENTITIES.put("sup3", 179);  NAMED_ENTITIES.put("szlig", 223); NAMED_ENTITIES.put("agrave", 224);
        NAMED_ENTITIES.put("aacute", 225); NAMED_ENTITIES.put("eacute", 233); NAMED_ENTITIES.put("egrave", 232);
        NAMED_ENTITIES.put("ccedil", 231); NAMED_ENTITIES.put("ntilde", 241); NAMED_ENTITIES.put("uuml", 252);
        NAMED_ENTITIES.put("ouml", 246);  NAMED_ENTITIES.put("auml", 228);  NAMED_ENTITIES.put("euml", 235);
    }

    static String[] decodeItems() { return items(" decode as ..."); }

    static String[] encodeItems() { return items(" encode as ..."); }

    private static String[] items(String first) {
        String[] out = new String[NAMES.length + 1];
        out[0] = first;
        System.arraycopy(NAMES, 0, out, 1, NAMES.length);
        return out;
    }

    /** Whatever message digests this JVM actually offers, sorted. */
    static String[] hashItems() {
        List<String> names = new ArrayList<>();
        for (String a : Security.getAlgorithms("MessageDigest")) names.add(" " + a);
        names.sort(null);
        names.add(0, " hash ...");
        return names.toArray(new String[0]);
    }

    static byte[] apply(String kind, String name, byte[] data) throws Exception {
        String n = name.trim();
        return switch (kind) {
            case DECODE -> decode(n, data);
            case ENCODE -> encode(n, data);
            case HASH   -> MessageDigest.getInstance(n).digest(data);
            default     -> data;
        };
    }

    static byte[] decode(String name, byte[] data) throws Exception {
        return switch (name) {
            case "url"       -> urlDecode(data);
            case "html"      -> htmlDecode(data);
            case "base64"    -> Base64.getMimeDecoder().decode(Bytes.str(data));
            case "ascii hex" -> fromBase(Bytes.str(data), 16, 2);
            case "hex"       -> fromBase(Bytes.str(data), 16, 2);
            case "octal"     -> fromBase(Bytes.str(data), 8, 3);
            case "binary"    -> fromBase(Bytes.str(data), 2, 8);
            case "gzip"      -> gunzip(data);
            case "deflate"   -> inflate(data);
            case "brotli"    -> unbrotli(data);
            default          -> data;
        };
    }

    static byte[] encode(String name, byte[] data) throws Exception {
        return switch (name) {
            case "url"       -> urlEncode(data);
            case "html"      -> htmlEncode(data);
            case "base64"    -> Bytes.bytes(Base64.getEncoder().encodeToString(data));
            case "ascii hex" -> toBase(data, 16, 2, "");
            case "hex"       -> toBase(data, 16, 2, " ");
            case "octal"     -> toBase(data, 8, 3, " ");
            case "binary"    -> toBase(data, 2, 8, " ");
            case "gzip"      -> gzip(data);
            case "deflate"   -> deflate(data);
            case "brotli"    -> throw new Failure("brotli encoding is not supported (decode only)");
            default          -> data;
        };
    }

    // ---- smart decode ----

    /**
     * Guess one decode worth trying, as a decode menu item, or null when
     * nothing recognisable would change the data.
     */
    static String guess(byte[] data) {
        if (data == null || data.length == 0) return null;
        // gzip has an unambiguous magic number, so it is safe to recognise.
        if (data.length >= 2 && (data[0] & 0xff) == 0x1f && (data[1] & 0xff) == 0x8b) {
            String hit = ifChanged("gzip", data);
            if (hit != null) return hit;
        }
        String s = Bytes.str(data);
        if (PERCENT.matcher(s).find()) {
            String hit = ifChanged("url", data);
            if (hit != null) return hit;
        }
        if (ENTITY.matcher(s).find()) {
            String hit = ifChanged("html", data);
            if (hit != null) return hit;
        }
        if (looksBase64(s)) {
            String hit = ifChanged("base64", data);
            if (hit != null) return hit;
        }
        if (looksAsciiHex(s)) {
            String hit = ifChanged("ascii hex", data);
            if (hit != null) return hit;
        }
        return null;
    }

    private static String ifChanged(String name, byte[] data) {
        try {
            byte[] out = decode(name, data);
            return Arrays.equals(out, data) ? null : " " + name;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean looksBase64(String s) {
        String t = stripWhitespace(s);
        if (t.isEmpty() || t.length() % 4 != 0) return false;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                      || c == '+' || c == '/' || c == '=';
            if (!ok) return false;
        }
        return true;
    }

    private static boolean looksAsciiHex(String s) {
        if (s.isEmpty() || s.length() % 2 != 0) return false;
        for (int i = 0; i < s.length(); i++) if (nibble(s.charAt(i)) < 0) return false;
        return true;
    }

    // ---- url ----

    private static byte[] urlEncode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 3);
        for (byte b : data) {
            int v = b & 0xff;
            if (unreserved(v)) {
                sb.append((char) v);
            } else {
                sb.append('%').append(upperNibble(v >> 4)).append(upperNibble(v & 0x0f));
            }
        }
        return Bytes.bytes(sb.toString());
    }

    private static byte[] urlDecode(byte[] data) throws Failure {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        for (int i = 0; i < data.length; i++) {
            int v = data[i] & 0xff;
            if (v == '+') {
                out.write(' ');
            } else if (v == '%') {
                if (i + 2 >= data.length) throw new Failure("truncated %-escape at offset " + i);
                int hi = nibble((char) (data[i + 1] & 0xff));
                int lo = nibble((char) (data[i + 2] & 0xff));
                if (hi < 0 || lo < 0) throw new Failure("bad %-escape at offset " + i);
                out.write((hi << 4) | lo);
                i += 2;
            } else {
                out.write(v);
            }
        }
        return out.toByteArray();
    }

    private static boolean unreserved(int v) {
        return (v >= 'A' && v <= 'Z') || (v >= 'a' && v <= 'z') || (v >= '0' && v <= '9')
            || v == '-' || v == '_' || v == '.' || v == '~';
    }

    // ---- html ----

    private static byte[] htmlEncode(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            int v = b & 0xff;
            switch (v) {
                case '&'  -> sb.append("&amp;");
                case '<'  -> sb.append("&lt;");
                case '>'  -> sb.append("&gt;");
                case '"'  -> sb.append("&quot;");
                case '\'' -> sb.append("&#39;");
                default   -> {
                    if (v == 9 || v == 10 || v == 13 || (v >= 32 && v <= 126)) sb.append((char) v);
                    else sb.append("&#").append(v).append(';');
                }
            }
        }
        return Bytes.bytes(sb.toString());
    }

    private static byte[] htmlDecode(byte[] data) {
        String s = Bytes.str(data);
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length);
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c != '&') {
                out.write(c);
                i++;
                continue;
            }
            int end = s.indexOf(';', i + 1);
            int cp = (end < 0 || end - i > 12) ? -1 : codePoint(s.substring(i + 1, end));
            if (cp < 0) {
                // not an entity we know: an unrecognised "&" stays as it was
                out.write(c);
                i++;
            } else {
                writeCodePoint(out, cp);
                i = end + 1;
            }
        }
        return out.toByteArray();
    }

    private static int codePoint(String body) {
        try {
            if (body.startsWith("#x") || body.startsWith("#X")) return Integer.parseInt(body.substring(2), 16);
            if (body.startsWith("#")) return Integer.parseInt(body.substring(1), 10);
        } catch (NumberFormatException e) {
            return -1;
        }
        Integer named = NAMED_ENTITIES.get(body.toLowerCase(Locale.ROOT));
        return named == null ? -1 : named;
    }

    private static void writeCodePoint(ByteArrayOutputStream out, int cp) {
        if (cp <= 0xff) {
            out.write(cp);
        } else if (Character.isValidCodePoint(cp)) {
            // outside Latin-1 there is no single byte to write, so use UTF-8
            byte[] utf8 = new String(Character.toChars(cp)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.write(utf8, 0, utf8.length);
        }
    }

    // ---- numeric bases ----

    private static byte[] toBase(byte[] data, int base, int width, String separator) {
        StringBuilder sb = new StringBuilder(data.length * (width + separator.length()));
        for (byte b : data) {
            if (sb.length() > 0) sb.append(separator);
            String digits = Integer.toString(b & 0xff, base);
            for (int pad = digits.length(); pad < width; pad++) sb.append('0');
            sb.append(digits);
        }
        return Bytes.bytes(sb.toString());
    }

    /**
     * Whitespace-separated groups when there is whitespace, otherwise fixed
     * width groups, so both "41 42" and "4142" read back as "AB".
     */
    private static byte[] fromBase(String s, int base, int width) throws Failure {
        String t = s.trim();
        if (t.isEmpty()) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(t.length() / width + 1);
        if (hasWhitespace(t)) {
            for (String group : t.split("\\s+")) {
                if (!group.isEmpty()) out.write(group(group, base, width));
            }
        } else {
            if (t.length() % width != 0) {
                throw new Failure("needs a multiple of " + width + " digits, got " + t.length());
            }
            for (int i = 0; i < t.length(); i += width) out.write(group(t.substring(i, i + width), base, width));
        }
        return out.toByteArray();
    }

    private static int group(String group, int base, int width) throws Failure {
        if (group.length() > width) throw new Failure("'" + group + "' is more than " + width + " digits");
        int v;
        try {
            v = Integer.parseInt(group, base);
        } catch (NumberFormatException e) {
            throw new Failure("'" + group + "' is not a base " + base + " number");
        }
        if (v < 0 || v > 255) throw new Failure("'" + group + "' is outside byte range");
        return v;
    }

    // ---- gzip ----

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(out)) {
            g.write(data);
        }
        return out.toByteArray();
    }

    private static byte[] gunzip(byte[] data) throws IOException {
        try (GZIPInputStream g = new GZIPInputStream(new ByteArrayInputStream(data))) {
            return g.readAllBytes();
        }
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(out, new Deflater(Deflater.DEFAULT_COMPRESSION, false))) {
            d.write(data);
        }
        return out.toByteArray();
    }

    /** Inflate zlib-wrapped data, falling back to raw (headerless) deflate. */
    private static byte[] inflate(byte[] data) throws Failure {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data))) {
            return in.readAllBytes();
        } catch (IOException zlib) {
            try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(data), new Inflater(true))) {
                return in.readAllBytes();
            } catch (IOException raw) {
                throw new Failure("not valid deflate data");
            }
        }
    }

    private static byte[] unbrotli(byte[] data) throws Failure {
        try (org.brotli.dec.BrotliInputStream b = new org.brotli.dec.BrotliInputStream(new ByteArrayInputStream(data))) {
            return b.readAllBytes();
        } catch (IOException e) {
            throw new Failure("not valid brotli data");
        }
    }

    // ---- views used by the editors ----

    /** Lowercase hex pairs with spaces, the hex view of a stage. */
    static String spacedHex(byte[] data) {
        return Bytes.str(toBase(data, 16, 2, " "));
    }

    /** Read back a hex view, ignoring anything that is not a hex digit. */
    static byte[] looseHex(String text) {
        StringBuilder digits = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            if (nibble(text.charAt(i)) >= 0) digits.append(text.charAt(i));
        }
        int n = digits.length() / 2;
        byte[] out = new byte[n];
        for (int i = 0; i < n; i++) {
            out[i] = (byte) ((nibble(digits.charAt(i * 2)) << 4) | nibble(digits.charAt(i * 2 + 1)));
        }
        return out;
    }

    // ---- small helpers ----

    private static boolean hasWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) if (Character.isWhitespace(s.charAt(i))) return true;
        return false;
    }

    private static String stripWhitespace(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isWhitespace(s.charAt(i))) sb.append(s.charAt(i));
        }
        return sb.toString();
    }

    private static char upperNibble(int v) { return "0123456789ABCDEF".charAt(v & 0x0f); }

    private static int nibble(char c) {
        if (c >= '0' && c <= '9') return c - '0';
        if (c >= 'a' && c <= 'f') return c - 'a' + 10;
        if (c >= 'A' && c <= 'F') return c - 'A' + 10;
        return -1;
    }
}
