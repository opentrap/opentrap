package opentrap.session;

import opentrap.core.HttpMessage;

import java.io.Serializable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A rule that pulls one named value out of a macro's response. */
public final class Extraction implements Serializable {
    private static final long serialVersionUID = 1L;

    public enum From { COOKIE, REGEX, HEADER }

    public String name = "";        // the name this value is remembered under
    public From from = From.REGEX;
    public String pattern = "";     // COOKIE: cookie name; HEADER: header name; REGEX: group 1 (or whole match)

    public Extraction() {}

    public Extraction(String name, From from, String pattern) {
        this.name = name; this.from = from; this.pattern = pattern;
    }

    public String extract(HttpMessage resp, CookieJar jar) {
        if (resp == null || pattern == null || pattern.isEmpty()) return null;
        try {
            switch (from) {
                case HEADER:
                    return resp.header(pattern);
                case COOKIE:
                    for (Cookie c : jar.all()) if (c.name.equals(pattern)) return c.value;
                    return null;
                case REGEX:
                default:
                    Matcher m = Pattern.compile(pattern).matcher(resp.bodyText());
                    if (!m.find()) return null;
                    return m.groupCount() >= 1 ? m.group(1) : m.group(0);
            }
        } catch (Exception e) {
            return null;
        }
    }

    @Override public String toString() { return name + " (" + from + ": " + pattern + ")"; }
}
