package opentrap.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared services every tool reaches for: history, scope, alerts, settings,
 * and the routes that move a message from one tool to another.
 */
public class SuiteContext {

    public interface MessageSink {
        /** Receive a message sent from another tool. */
        void receive(HttpService service, HttpMessage request, HttpMessage response, String caption);

        /**
         * True when the tool stores one blob per send rather than a
         * request/response pair, so the sender must pick exactly one of the two
         * and leave the other null. The comparer is the only such tool: it
         * compares two items the user chose, so handing it both halves of one
         * exchange loads two items from a single click and makes a pair the user
         * never asked for.
         */
        default boolean singleItem() { return false; }
    }

    public interface AlertListener { void alertRaised(Alert a); }

    public interface ScopeListener { void scopeChanged(); }

    public interface SiteMapListener { void siteMapChanged(LogEntry e); }

    public interface IssueListener { void issueAdded(Issue i); }

    public interface TaskListener { void taskChanged(ScanTask t); }

    private final MessageStore store = new MessageStore();
    private final WebSocketStore wsStore = new WebSocketStore();
    private final Scope scope = new Scope();
    private final Settings settings = new Settings();
    // The out-of-band collaborator session, shared by the scanner's blind checks
    // and the collaborator tab so one registration and one poll loop serve both.
    private final opentrap.oast.Collaborator collaborator = new opentrap.oast.Collaborator(settings);
    // Session handling: the cookie jar, macros and rules, shared by every tool
    // that sends through the HttpClient session hook.
    private final opentrap.session.SessionManager sessions = new opentrap.session.SessionManager(this);
    // Upstream proxy chaining: installed as the HttpClient's proxy provider so
    // every tool's outbound traffic can be routed through an HTTP or SOCKS proxy.
    private final UpstreamProxyManager upstreamProxies = new UpstreamProxyManager(settings);
    { HttpClient.setProxyProvider(upstreamProxies); }
    // The Organizer's shelf of saved items, persisted with the project.
    private final opentrap.organizer.OrganizerStore organizer = new opentrap.organizer.OrganizerStore();
    private final List<Alert> alerts = new ArrayList<>();
    private final List<Issue> issues = new ArrayList<>();
    private final java.util.Set<String> issueKeys = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final List<ScanTask> tasks = new ArrayList<>();

    private final Map<String, MessageSink> sinks = new LinkedHashMap<>();
    private final List<AlertListener> alertListeners = new CopyOnWriteArrayList<>();
    private final List<ScopeListener> scopeListeners = new CopyOnWriteArrayList<>();
    private final List<SiteMapListener> siteMapListeners = new CopyOnWriteArrayList<>();
    private final List<IssueListener> issueListeners = new CopyOnWriteArrayList<>();
    private final List<TaskListener> taskListeners = new CopyOnWriteArrayList<>();
    private final List<ToolModule> tools = new ArrayList<>();

    // Live passive scanning: the proxy consults this to route in-scope traffic
    // through the scan engine. Null = no live scanning.
    private volatile java.util.function.Function<HttpService, UpstreamProxy> liveScanRouter;

    private java.awt.Frame frame;
    // The project file this session is bound to, chosen at startup. Null for a
    // temporary in-memory project; used as the default target for "save state".
    private volatile java.io.File projectFile;
    private volatile int proxyPort = -1;
    private volatile String caSpki = "";
    private volatile String browserBinary = "";

    public MessageStore store()   { return store; }
    public WebSocketStore wsStore() { return wsStore; }
    public Scope scope()          { return scope; }
    public Settings settings()    { return settings; }
    public opentrap.oast.Collaborator collaborator() { return collaborator; }
    public opentrap.session.SessionManager sessions() { return sessions; }
    public UpstreamProxyManager upstreamProxies() { return upstreamProxies; }
    public opentrap.organizer.OrganizerStore organizer() { return organizer; }
    public List<ToolModule> tools(){ return tools; }

    public java.awt.Frame frame() { return frame; }
    public void setFrame(java.awt.Frame f) { this.frame = f; }

    public java.io.File projectFile() { return projectFile; }
    public void setProjectFile(java.io.File f) { this.projectFile = f; }

    /** Proxy routing info, set by the proxy tool, used by the browser crawler. */
    public java.util.function.Function<HttpService, UpstreamProxy> liveScanRouter() { return liveScanRouter; }
    public void setLiveScanRouter(java.util.function.Function<HttpService, UpstreamProxy> r) { this.liveScanRouter = r; }

    public int proxyPort()            { return proxyPort; }
    public void setProxyPort(int p)   { this.proxyPort = p; }
    public String caSpki()            { return caSpki; }
    public void setCaSpki(String s)   { this.caSpki = s == null ? "" : s; }
    public String browserBinary()     { return browserBinary; }
    public void setBrowserBinary(String b) { this.browserBinary = b == null ? "" : b; }

    public void registerTool(ToolModule t) { tools.add(t); }

    // ---- routing between tools ----

    /** Register a destination for "send to <name>". */
    public void registerSink(String toolName, MessageSink sink) { sinks.put(toolName, sink); }

    public boolean hasSink(String toolName) { return sinks.containsKey(toolName); }

    /** See {@link MessageSink#singleItem()}. False for an unknown tool. */
    public boolean sinkTakesSingleItem(String toolName) {
        MessageSink s = sinks.get(toolName);
        return s != null && s.singleItem();
    }

    public List<String> sinkNames() { return new ArrayList<>(sinks.keySet()); }

    public void sendTo(String toolName, HttpService svc, HttpMessage req, HttpMessage resp, String caption) {
        MessageSink s = sinks.get(toolName);
        if (s != null) s.receive(svc, req, resp, caption);
    }

    // ---- history ----

    public LogEntry record(String tool, HttpService svc, HttpMessage req) {
        LogEntry e = new LogEntry(tool, svc, req);
        store.add(e);
        return e;
    }

    public void siteMapChanged(LogEntry e) {
        for (SiteMapListener l : siteMapListeners) l.siteMapChanged(e);
    }

    public void addSiteMapListener(SiteMapListener l) { siteMapListeners.add(l); }

    // ---- issues ----

    /** Add a scanner issue, de-duplicated. Returns true if newly added. */
    public boolean addIssue(Issue i) {
        if (i == null || !issueKeys.add(i.dedupeKey())) return false;
        synchronized (issues) { issues.add(i); }
        for (IssueListener l : issueListeners) l.issueAdded(i);
        return true;
    }

    public List<Issue> issues() {
        synchronized (issues) { return new ArrayList<>(issues); }
    }

    public void addIssueListener(IssueListener l) { issueListeners.add(l); }

    // ---- scan tasks ----

    public void addTask(ScanTask t) {
        synchronized (tasks) { tasks.add(t); }
        taskChanged(t);
    }

    public List<ScanTask> tasks() {
        synchronized (tasks) { return new ArrayList<>(tasks); }
    }

    public void taskChanged(ScanTask t) {
        for (TaskListener l : taskListeners) l.taskChanged(t);
    }

    public void addTaskListener(TaskListener l) { taskListeners.add(l); }

    // ---- alerts ----

    public synchronized void alert(String tool, String message) {
        if (!alerts.isEmpty()) {
            Alert last = alerts.get(alerts.size() - 1);
            if (last.tool.equals(tool == null ? "suite" : tool) && last.message.equals(message)) {
                last.repeats++;
                for (AlertListener l : alertListeners) l.alertRaised(last);
                return;
            }
        }
        Alert a = new Alert(tool, message);
        alerts.add(a);
        for (AlertListener l : alertListeners) l.alertRaised(a);
    }

    public synchronized List<Alert> alerts() { return new ArrayList<>(alerts); }
    public synchronized void clearAlerts()   { alerts.clear(); }
    public void addAlertListener(AlertListener l) { alertListeners.add(l); }

    // ---- scope ----

    public void scopeChanged() { for (ScopeListener l : scopeListeners) l.scopeChanged(); }
    public void addScopeListener(ScopeListener l) { scopeListeners.add(l); }
}
