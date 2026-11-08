package opentrap.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

/** One request/response exchange, as recorded by any tool. */
public class LogEntry implements Serializable {
    private static final long serialVersionUID = 1L;
    private static final AtomicInteger SEQ = new AtomicInteger(0);

    private final int number;
    private final String tool;
    private final HttpService service;
    // request and response are volatile because the offload writer thread nulls
    // them once the bodies are on disk while the event thread reads them to paint
    // the history. The volatile write on nulling publishes the metadata snapshot
    // taken just before it, so a reader that sees a null field is guaranteed to
    // see the snapshot that replaces it, never a half-offloaded entry.
    private volatile HttpMessage request;
    private volatile HttpMessage response;
    // The pre-modification copies, kept when the proxy rewrote the exchange. They
    // are offloaded to disk like the request and response (volatile for the same
    // publication reason), so a heavy match-and-replace session does not re-bloat
    // the in-memory index.
    private volatile HttpMessage originalRequest;
    private volatile HttpMessage originalResponse;
    private final Date time;
    private String remoteIp = "";
    private boolean edited;
    private String notes = "";
    private String highlight = "";
    private long durationMillis = -1;
    private String error = "";

    // Set on an entry written to a project with its response body left out or
    // cut short: the headers are kept, the full length is remembered, and the
    // viewer says so instead of showing an empty body as if that was the reply.
    private boolean bodyDropped;
    private boolean bodyTruncated;
    private int fullResponseLength;

    // Issue #37: when a disk ProjectStore is attached, the heavy request and
    // response bytes are written to it and the in-heap fields are nulled, so a
    // long session holds an index of exchanges rather than every body. The
    // request()/response() getters reload the bytes on demand; the metadata
    // getters below read the snapshot fields instead, so the history table, site
    // map, counters and filters never touch the disk. The store reference is
    // transient; the project format phase persists the offsets.
    private transient ProjectStore bodyStore;
    private long reqOff = -1;  private int reqLen;
    private long respOff = -1; private int respLen;
    private long origReqOff = -1;  private int origReqLen;
    private long origRespOff = -1; private int origRespLen;

    // Metadata snapshot, taken from the live request and response the moment
    // they are offloaded, so every column and filter the UI reads is served from
    // the heap without a disk load. Only the message detail view pulls the full
    // bytes back. These stay null/zero until an entry is offloaded.
    private volatile boolean offloaded;
    private String mMethod = "";
    private String mTarget = "";
    private String mPath = "";
    private String mQuery = "";
    private boolean mHasParams;
    private String mExtension = "";
    private int mReqHeaderLen;
    private int mStatus;
    private int mRespLen;
    private int mRespHeaderLen;
    private String mMime = "";
    private String mTitle = "";
    private String mCookies = "";

    public LogEntry(String tool, HttpService service, HttpMessage request) {
        this.number = SEQ.incrementAndGet();
        this.tool = tool;
        this.service = service;
        this.request = request;
        this.time = new Date();
    }

    /** A copy of {@code src} that keeps its number and time but carries a different response. */
    private LogEntry(LogEntry src, HttpMessage response) {
        this.number = src.number;
        this.tool = src.tool;
        this.service = src.service;
        this.request = src.request;
        this.response = response;
        this.originalRequest = src.originalRequest;
        this.originalResponse = src.originalResponse;
        this.time = src.time;
        this.remoteIp = src.remoteIp;
        this.edited = src.edited;
        this.notes = src.notes;
        this.highlight = src.highlight;
        this.durationMillis = src.durationMillis;
        this.error = src.error;
        this.bodyDropped = src.bodyDropped;
        this.bodyTruncated = src.bodyTruncated;
        this.fullResponseLength = src.fullResponseLength;
    }

    public static void resetCounter() { SEQ.set(0); }
    public static int peekCounter()   { return SEQ.get(); }
    public static void setCounter(int v) { SEQ.set(v); }

    public int number()              { return number; }
    public String tool()             { return tool; }
    public HttpService service()     { return service; }
    public HttpMessage request()     { return request != null ? request : load(reqOff, reqLen); }
    public HttpMessage response()    { return response != null ? response : load(respOff, respLen); }

    /** Reload an offloaded body from the disk store, or null if not offloaded. */
    private HttpMessage load(long off, int len) {
        if (bodyStore == null || off < 0) return null;
        try { return new HttpMessage(bodyStore.readBody(off, len)); }
        catch (Exception e) { return null; }
    }

    /** Reconnect a restored entry to the store its body offsets point into. */
    void rebind(ProjectStore store) { this.bodyStore = store; }

    /** Whether this entry's bodies live on disk rather than in the heap. */
    public boolean offloaded() { return offloaded; }

    /**
     * A copy of this entry with its bodies resident, loaded back from the disk
     * store if they were offloaded, so it can be serialised in full. A
     * non-offloaded entry (or one already resident) is returned unchanged. Used
     * by the project save path, which must never write an entry with its bodies
     * left on disk and so empty on the page.
     */
    public LogEntry materialise() {
        if (!offloaded) return this;
        if (request != null && response != null) return this;
        LogEntry c = new LogEntry(this, response());
        c.request = request();
        c.originalRequest = originalRequest();
        c.originalResponse = originalResponse();
        return c;
    }

    /** Whether either body is currently held in the heap rather than on disk. */
    boolean bodiesResident() { return request != null || response != null; }

    /**
     * This entry's index record: the entry serialised with its bodies left on
     * disk, so only the metadata and the body offsets are written. The caller
     * offloads first, so the bodies are null and nothing heavy is inlined.
     */
    public byte[] indexBytes() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream oos = new ObjectOutputStream(baos)) { oos.writeObject(this); }
        return baos.toByteArray();
    }

    /** Rebuild an entry from an index record and reconnect it to {@code store}. */
    public static LogEntry fromIndex(byte[] b, ProjectStore store) throws IOException, ClassNotFoundException {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(b))) {
            LogEntry e = (LogEntry) ois.readObject();
            e.rebind(store);
            return e;
        }
    }

    /**
     * An independent copy of this entry, slimmed per the save options, with its
     * bodies written into {@code target} and dropped from the copy's heap. The
     * live entry is untouched, so writing a project copy never disturbs the
     * running session. Returns the copy, ready for {@link #indexBytes}.
     */
    public LogEntry writtenTo(ProjectStore target, boolean dropBody, int capBytes) throws IOException {
        LogEntry c = new LogEntry(this, response());   // response() loads from disk if offloaded
        c.request = request();                          // request() loads from disk if offloaded
        c.originalRequest = originalRequest();          // load the pre-modification copies too
        c.originalResponse = originalResponse();
        LogEntry s = c.slim(dropBody, capBytes);        // operates on the resident copy
        s.offload(target);
        return s;
    }

    /**
     * Snapshot the metadata every column and filter needs, then write this
     * entry's request and response bytes to {@code store} and drop the in-heap
     * copies, so the entry no longer retains the bodies. {@link #request()} and
     * {@link #response()} reload them on demand; the metadata getters read the
     * snapshot. The pre-modification originals are offloaded too; the metadata
     * (number, time, tool, service) stays in place.
     */
    void offload(ProjectStore store) throws java.io.IOException {
        HttpMessage req = request, resp = response;
        if (req != null) {
            mMethod = req.method();
            mTarget = req.requestTarget();
            mPath = req.path();
            mQuery = req.query();
            mHasParams = req.hasParameters();
            mExtension = req.fileExtension();
            mReqHeaderLen = req.bodyOffset();
        }
        if (resp != null) {
            mStatus = resp.statusCode();
            mRespLen = resp.length();
            mRespHeaderLen = resp.bodyOffset();
            mMime = resp.mimeType();
            mTitle = resp.htmlTitle();
            mCookies = cookiesOf(resp);
        }
        // Set the store, offsets and offloaded flag first, then null the bodies.
        // The null writes are the volatile releases that publish everything above
        // to the reading thread, so a reader never sees a null body without the
        // snapshot and offsets that replace it.
        this.bodyStore = store;
        if (req != null)  { byte[] b = req.raw();  reqOff  = store.append(ProjectStore.BODY, b); reqLen  = b.length; }
        if (resp != null) { byte[] b = resp.raw(); respOff = store.append(ProjectStore.BODY, b); respLen = b.length; }
        HttpMessage oreq = originalRequest, oresp = originalResponse;
        if (oreq != null)  { byte[] b = oreq.raw();  origReqOff  = store.append(ProjectStore.BODY, b); origReqLen  = b.length; }
        if (oresp != null) { byte[] b = oresp.raw(); origRespOff = store.append(ProjectStore.BODY, b); origRespLen = b.length; }
        offloaded = true;
        originalResponse = null;
        originalRequest = null;
        response = null;
        request = null;
    }
    public HttpMessage originalRequest() {
        HttpMessage o = originalRequest;
        return o != null ? o : load(origReqOff, origReqLen);
    }
    /**
     * The response as the server sent it, before any proxy response
     * modification. Null when nothing was modified. The scanner audits this in
     * preference to {@link #response()}, so that stripping a header to build a
     * proof of concept never turns into a false "header missing" finding.
     */
    public HttpMessage originalResponse() {
        HttpMessage o = originalResponse;
        return o != null ? o : load(origRespOff, origRespLen);
    }
    /** The response to scan: the server's original when present, else the current. */
    public HttpMessage responseForScanning() {
        HttpMessage o = originalResponse();
        return o != null ? o : response();
    }
    public Date time()               { return time; }
    public String remoteIp()         { return remoteIp; }
    public boolean edited()          { return edited; }
    public String notes()            { return notes; }
    public String highlight()        { return highlight; }
    public long durationMillis()     { return durationMillis; }
    public String error()            { return error; }

    public void setRequest(HttpMessage m)  { this.request = m; }
    public void setResponse(HttpMessage m) { this.response = m; }
    public void setOriginalRequest(HttpMessage m) { this.originalRequest = m; }
    public void setOriginalResponse(HttpMessage m) { this.originalResponse = m; }
    public void setRemoteIp(String s)      { this.remoteIp = s == null ? "" : s; }
    public void setEdited(boolean b)       { this.edited = b; }
    public void setNotes(String s)         { this.notes = s == null ? "" : s; }
    public void setHighlight(String s)     { this.highlight = s == null ? "" : s; }
    public void setDurationMillis(long v)  { this.durationMillis = v; }
    public void setError(String s)         { this.error = s == null ? "" : s; }

    /** True when the stored response is headers only. */
    public boolean bodyDropped()   { return bodyDropped; }
    /** True when the stored response body was cut to the project's cap. */
    public boolean bodyTruncated() { return bodyTruncated; }

    /**
     * The one-line note a viewer shows above a response whose body is not all
     * here, or null when the response is complete.
     */
    public String bodyNote() {
        if (bodyDropped) {
            return "Response body not kept in the project (" + fullResponseLength
                    + " bytes with headers). The findings that point at this request hold the full response.";
        }
        if (bodyTruncated) {
            return "Response body cut to the project's cap; the full response was "
                    + fullResponseLength + " bytes with headers.";
        }
        return null;
    }

    /**
     * The entry as it is written to a project: the same entry when nothing is
     * taken out, otherwise a copy whose response is headers only
     * ({@code dropBody}) or whose body is cut at {@code capBytes} (when the cap
     * is above zero). The live entry is never changed, so the session keeps
     * showing the whole response.
     */
    public LogEntry slim(boolean dropBody, int capBytes) {
        if (response == null || bodyDropped) return this;
        int bodyLen = response.length() - response.bodyOffset();
        if (dropBody && bodyLen > 0) {
            LogEntry c = new LogEntry(this, headersOnly(response));
            c.bodyDropped = true;
            c.fullResponseLength = response.length();
            // The server's original copy would carry the body straight back in.
            c.originalResponse = null;
            return c;
        }
        if (capBytes > 0 && bodyLen > capBytes && !bodyTruncated) {
            HttpMessage cut = new HttpMessage(Bytes.slice(response.raw(), 0, response.bodyOffset() + capBytes));
            LogEntry c = new LogEntry(this, cut);
            c.bodyTruncated = true;
            c.fullResponseLength = response.length();
            c.originalResponse = null;
            return c;
        }
        return this;
    }

    private static HttpMessage headersOnly(HttpMessage m) {
        return new HttpMessage(Bytes.slice(m.raw(), 0, m.bodyOffset()));
    }

    /** Request and response bytes held for this entry, including any original copies. */
    public long bytes() {
        long n = 0;
        n += request != null ? request.length() : Math.max(0, reqLen);
        n += response != null ? response.length() : Math.max(0, respLen);
        n += originalRequest != null ? originalRequest.length() : Math.max(0, origReqLen);
        n += originalResponse != null ? originalResponse.length() : Math.max(0, origRespLen);
        return n;
    }

    // The metadata getters below read the live message when the bytes are in the
    // heap, and the snapshot taken at offload once they are on disk. Neither
    // path loads bodies back from disk, so scrolling the history table, redrawing
    // the site map, re-running a filter and summing the counters all stay in RAM.

    // Each getter reads the live message when the bytes are resident, and the
    // snapshot once they are offloaded. The snapshot defaults (empty, zero,
    // false) are exactly what a never-offloaded entry with a missing message
    // should report, so no separate offloaded check is needed; reading the live
    // field (volatile) first is what makes a null observation see the snapshot.

    /** Request method, from the live request or the offload snapshot. */
    public String method() {
        HttpMessage r = request;
        return r != null ? r.method() : mMethod;
    }

    /** Whether the request carries any parameters (query, cookie or body). */
    public boolean hasParameters() {
        HttpMessage r = request;
        return r != null ? r.hasParameters() : mHasParams;
    }

    /** Request path file extension, or empty. */
    public String fileExtension() {
        HttpMessage r = request;
        return r != null ? r.fileExtension() : mExtension;
    }

    /** Whether a request/response exists, without loading its bytes back from disk. */
    public boolean hasRequest()  { return request != null  || reqOff >= 0; }
    public boolean hasResponse() { return response != null || respOff >= 0; }

    /** Request path (no query), from the live request or the offload snapshot. */
    public String requestPath() {
        HttpMessage r = request;
        return r != null ? r.path() : mPath;
    }

    /** Request query string (no leading '?'), from the live request or the snapshot. */
    public String requestQuery() {
        HttpMessage r = request;
        return r != null ? r.query() : mQuery;
    }

    /** Header and body byte counts, served from the index so a size scan never loads bodies. */
    public int requestHeaderBytes() {
        HttpMessage r = request;
        return r != null ? r.bodyOffset() : mReqHeaderLen;
    }
    public int requestBodyBytes() {
        HttpMessage r = request;
        return r != null ? r.length() - r.bodyOffset() : Math.max(0, reqLen - mReqHeaderLen);
    }
    public int responseHeaderBytes() {
        HttpMessage r = response;
        return r != null ? r.bodyOffset() : mRespHeaderLen;
    }
    public int responseBodyBytes() {
        HttpMessage r = response;
        return r != null ? r.length() - r.bodyOffset() : Math.max(0, respLen - mRespHeaderLen);
    }

    /** Absolute URL of the request. */
    public String url() {
        String t = pathAndQuery();
        if (t.startsWith("http://") || t.startsWith("https://")) return t;
        if (t.isEmpty()) return service.url();
        return service.url() + (t.startsWith("/") ? t : "/" + t);
    }

    public String pathAndQuery() {
        HttpMessage r = request;
        if (r != null) return r.requestTarget();
        return reqOff >= 0 ? mTarget : "/";
    }

    public int status() {
        HttpMessage r = response;
        return r != null ? r.statusCode() : mStatus;
    }

    /** The response's length as received, even when the project holds less of it. */
    public int responseLen() {
        if (bodyDropped || bodyTruncated) return fullResponseLength;
        HttpMessage r = response;
        return r != null ? r.length() : mRespLen;
    }

    public String mimeType() {
        HttpMessage r = response;
        return r != null ? r.mimeType() : mMime;
    }

    public String title() {
        HttpMessage r = response;
        return r != null ? r.htmlTitle() : mTitle;
    }

    public String cookies() {
        HttpMessage r = response;
        return r != null ? cookiesOf(r) : mCookies;
    }

    /** The Set-Cookie names a response defines, joined for the history column. */
    private static String cookiesOf(HttpMessage r) {
        StringBuilder sb = new StringBuilder();
        for (String l : r.headerLines()) {
            if (l.toLowerCase().startsWith("set-cookie:")) {
                String v = l.substring("set-cookie:".length()).trim();
                int eq = v.indexOf('=');
                if (eq > 0) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(v, 0, eq);
                }
            }
        }
        return sb.toString();
    }
}
