package opentrap.proxy;

import opentrap.core.Bytes;
import opentrap.core.LogEntry;
import opentrap.core.Scope;

import java.io.Serializable;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The display filter applied to a history or site-map table.
 *
 * <p>It holds two independent searches, matching how the commercial tools split
 * the job:
 *
 * <ul>
 *   <li>{@link #urlSearch} is the fast one. It matches the URL only, so typing in
 *       the quick box beside the table narrows thousands of rows instantly.
 *   <li>{@link #searchTerm} is the thorough one, set in the filter popup. It
 *       searches the full text of both the request and the response, so it finds
 *       a value buried in a header or a body, at the cost of being slower.
 * </ul>
 *
 * <p>Everything else is a structural filter: scope, response presence, MIME
 * type, status band, file extension and annotation. {@link #hideEmptyFolders}
 * is read by the site map tree alone.
 */
public class HistoryFilter implements Serializable {
    private static final long serialVersionUID = 3L;

    // Structural filters.
    public boolean inScopeOnly = false;
    public boolean hideWithoutResponses = false;
    public boolean hideNotFound = false;
    public boolean parameterisedOnly = false;
    public boolean showHtml = true, showScript = true, showXml = true, showJson = true;
    public boolean showCss = true, showImages = true, showOtherText = true, showOtherBinary = true;
    public boolean show2xx = true, show3xx = true, show4xx = true, show5xx = true;
    public boolean extShowOnlyOn = false;
    public boolean extHideOn = false;
    public String extShowOnly = "asp,aspx,jsp,php";   // comma-separated
    public String extHide = "js,gif,jpg,png,ico,css,woff,woff2,ttf,svg";
    public boolean notesOnly = false;
    public boolean highlightedOnly = false;
    public boolean hideEmptyFolders = false;

    // Fast URL search (the quick box).
    public String urlSearch = "";

    // Thorough full-text search (the filter popup).
    public String searchTerm = "";
    public boolean searchRegex = false;
    public boolean searchCaseSensitive = false;
    public boolean searchNegative = false;

    public transient Scope scope;

    // Cached compiled regex, rebuilt when the term or its flags change.
    private transient Pattern compiled;
    private transient String compiledFor;
    private transient boolean compiledCase;

    /** The site map's starting filter: not-found items, stylesheets, images, binaries, 4xx and empty folders hidden. */
    public static HistoryFilter siteMapDefaults() {
        HistoryFilter f = new HistoryFilter();
        f.hideNotFound = true;
        f.showCss = false; f.showImages = false; f.showOtherBinary = false;
        f.show4xx = false;
        f.hideEmptyFolders = true;
        return f;
    }

    /** The proxy history's starting filter: stylesheets, images and static-asset extensions hidden. */
    public static HistoryFilter proxyDefaults() {
        HistoryFilter f = new HistoryFilter();
        f.showCss = false; f.showImages = false;
        f.extHideOn = true;
        return f;
    }

    public boolean test(LogEntry e) {
        // Fast URL search first: it rejects the most rows for the least work.
        if (urlSearch != null && !urlSearch.isBlank()) {
            String url = e.url() == null ? "" : e.url();
            if (!url.toLowerCase(Locale.ROOT).contains(urlSearch.toLowerCase(Locale.ROOT))) return false;
        }

        if (inScopeOnly && (scope == null || !scope.isInScope(e))) return false;
        if (hideWithoutResponses && !e.hasResponse()) return false;
        if (hideNotFound && e.status() == 404) return false;
        if (parameterisedOnly && !e.hasParameters()) return false;
        if (notesOnly && (e.notes() == null || e.notes().isBlank())) return false;
        if (highlightedOnly && (e.highlight() == null || e.highlight().isBlank())) return false;

        int sc = e.status();
        if (sc >= 200 && sc < 300 && !show2xx) return false;
        if (sc >= 300 && sc < 400 && !show3xx) return false;
        if (sc >= 400 && sc < 500 && !show4xx) return false;
        if (sc >= 500 && sc < 600 && !show5xx) return false;

        if (e.hasResponse()) {
            String m = e.mimeType();
            if (m.equals("HTML") && !showHtml) return false;
            if (m.equals("script") && !showScript) return false;
            if (m.equals("XML") && !showXml) return false;
            if (m.equals("JSON") && !showJson) return false;
            if (m.equals("CSS") && !showCss) return false;
            if (m.equals("images") && !showImages) return false;
            if (m.equals("other text") && !showOtherText) return false;
            if (m.equals("other binary") && !showOtherBinary) return false;
        }

        String ext = e.fileExtension();
        if (extShowOnlyOn && !extShowOnly.isBlank() && !extListContains(extShowOnly, ext)) return false;
        if (extHideOn && !extHide.isBlank() && extListContains(extHide, ext)) return false;

        // Thorough full-text search last: it is the most expensive, and the
        // cheaper filters above have already discarded most rows.
        if (searchTerm != null && !searchTerm.isBlank()) {
            boolean hit = fullTextMatch(e);
            if (searchNegative) hit = !hit;
            if (!hit) return false;
        }
        return true;
    }

    private boolean fullTextMatch(LogEntry e) {
        String hay = (e.request() == null ? "" : Bytes.str(e.request().raw()))
                   + "\n" + (e.response() == null ? "" : Bytes.str(e.response().raw()));
        if (searchRegex) {
            Pattern p = pattern();
            return p != null && p.matcher(hay).find();
        }
        if (searchCaseSensitive) return hay.contains(searchTerm);
        return hay.toLowerCase(Locale.ROOT).contains(searchTerm.toLowerCase(Locale.ROOT));
    }

    private Pattern pattern() {
        if (compiled == null || !searchTerm.equals(compiledFor) || compiledCase != searchCaseSensitive) {
            try {
                compiled = Pattern.compile(searchTerm,
                        searchCaseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (Exception ex) {
                compiled = null;
            }
            compiledFor = searchTerm;
            compiledCase = searchCaseSensitive;
        }
        return compiled;
    }

    private static boolean extListContains(String list, String ext) {
        if (ext == null) ext = "";
        for (String part : list.split("[,\\s]+")) {
            String p = part.trim().toLowerCase(Locale.ROOT);
            if (p.startsWith(".")) p = p.substring(1);
            if (!p.isEmpty() && p.equals(ext.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    /** True when no structural filter and no search is active. */
    public boolean isEverything() {
        return !inScopeOnly && !hideWithoutResponses && !hideNotFound && !parameterisedOnly
                && !notesOnly && !highlightedOnly
                && allMimeShown() && allStatusShown()
                && !(extShowOnlyOn && !extShowOnly.isBlank()) && !(extHideOn && !extHide.isBlank())
                && (searchTerm == null || searchTerm.isBlank());
    }

    /**
     * A one-line description of the active structural filter, for the bar,
     * phrased the way the reference does: "Hiding out of scope and not found
     * items; hiding CSS, image and general binary content; hiding 4xx responses".
     */
    public String summary() {
        java.util.List<String> parts = new java.util.ArrayList<>();

        java.util.List<String> items = new java.util.ArrayList<>();
        if (inScopeOnly) items.add("out of scope");
        if (hideNotFound) items.add("not found");
        if (hideWithoutResponses) items.add("unanswered");
        if (parameterisedOnly) items.add("unparameterised");
        if (!items.isEmpty()) parts.add("hiding " + join(items) + " items");

        java.util.List<String> content = new java.util.ArrayList<>();
        if (!showHtml) content.add("HTML");
        if (!showScript) content.add("script");
        if (!showXml) content.add("XML");
        if (!showJson) content.add("JSON");
        if (!showCss) content.add("CSS");
        if (!showImages) content.add("image");
        if (!showOtherText) content.add("other text");
        if (!showOtherBinary) content.add("general binary");
        if (content.size() == 8) parts.add("hiding all content types");
        else if (!content.isEmpty()) parts.add("hiding " + join(content) + " content");

        java.util.List<String> codes = new java.util.ArrayList<>();
        if (!show2xx) codes.add("2xx");
        if (!show3xx) codes.add("3xx");
        if (!show4xx) codes.add("4xx");
        if (!show5xx) codes.add("5xx");
        if (!codes.isEmpty()) parts.add("hiding " + join(codes) + " responses");

        if (extShowOnlyOn && !extShowOnly.isBlank()) parts.add("showing only " + extShowOnly.replace(",", ", ") + " extensions");
        if (extHideOn && !extHide.isBlank()) parts.add("hiding specific extensions");
        if (notesOnly) parts.add("showing only items with notes");
        if (highlightedOnly) parts.add("showing only highlighted items");
        if (searchTerm != null && !searchTerm.isBlank())
            parts.add((searchNegative ? "hiding items matching \"" : "showing only items matching \"") + searchTerm + "\"");
        if (hideEmptyFolders) parts.add("hiding empty folders");

        if (parts.isEmpty()) return "Showing all items";
        String s = String.join("; ", parts);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String join(java.util.List<String> words) {
        if (words.size() == 1) return words.get(0);
        return String.join(", ", words.subList(0, words.size() - 1)) + " and " + words.get(words.size() - 1);
    }

    private boolean allMimeShown() {
        return showHtml && showScript && showXml && showJson && showCss
                && showImages && showOtherText && showOtherBinary;
    }

    private boolean allStatusShown() {
        return show2xx && show3xx && show4xx && show5xx;
    }

    public void showAll() {
        inScopeOnly = hideWithoutResponses = hideNotFound = parameterisedOnly = false;
        notesOnly = highlightedOnly = false;
        showHtml = showScript = showXml = showJson = showCss = showImages = showOtherText = showOtherBinary = true;
        show2xx = show3xx = show4xx = show5xx = true;
        extShowOnlyOn = extHideOn = false;
        searchTerm = "";
        hideEmptyFolders = false;
    }

    public HistoryFilter copy() {
        HistoryFilter c = new HistoryFilter();
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(HistoryFilter o) {
        inScopeOnly = o.inScopeOnly; hideWithoutResponses = o.hideWithoutResponses;
        hideNotFound = o.hideNotFound; parameterisedOnly = o.parameterisedOnly;
        showHtml = o.showHtml; showScript = o.showScript; showXml = o.showXml; showJson = o.showJson;
        showCss = o.showCss; showImages = o.showImages; showOtherText = o.showOtherText; showOtherBinary = o.showOtherBinary;
        show2xx = o.show2xx; show3xx = o.show3xx; show4xx = o.show4xx; show5xx = o.show5xx;
        extShowOnlyOn = o.extShowOnlyOn; extHideOn = o.extHideOn;
        extShowOnly = o.extShowOnly; extHide = o.extHide;
        notesOnly = o.notesOnly; highlightedOnly = o.highlightedOnly; hideEmptyFolders = o.hideEmptyFolders;
        urlSearch = o.urlSearch;
        searchTerm = o.searchTerm; searchRegex = o.searchRegex;
        searchCaseSensitive = o.searchCaseSensitive; searchNegative = o.searchNegative;
        scope = o.scope;
    }
}
