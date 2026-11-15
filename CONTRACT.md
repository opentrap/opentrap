# OpenTRAP module contract

Source root `src` in the repository root. Compile check, from the repository
root: `javac -d build -cp "lib/*:build" $(find src -name '*.java')`

Java 17 or newer (built and tested on Java 25), Swing. No external deps except
BouncyCastle (already used by the proxy only).

## Implement this

```java
package opentrap.core;
public interface ToolModule {
    String name();                       // lower-case tab caption
    ToolModule.Tab[] tabs();             // one entry = no inner tab bar
    default JMenu[] menus() { return null; }
    default boolean alwaysTabbed() { return false; }
    default void init(SuiteContext ctx) {}
    default void shutdown() {}
    record Tab(String title, javax.swing.JComponent component) {}
}
```

## opentrap.core.SuiteContext  (passed to init)

```java
MessageStore store();        Scope scope();       Settings settings();
Frame frame();               void setFrame(Frame f);
void registerSink(String toolName, MessageSink sink);   // enables "send to <toolName>"
List<String> sinkNames();    boolean hasSink(String n);
void sendTo(String tool, HttpService svc, HttpMessage req, HttpMessage resp, String caption);
LogEntry record(String tool, HttpService svc, HttpMessage req);  // adds to history
void siteMapChanged(LogEntry e);   void addSiteMapListener(SiteMapListener l);
void alert(String tool, String message);   List<Alert> alerts();
void scopeChanged();         void addScopeListener(ScopeListener l);

interface MessageSink { void receive(HttpService svc, HttpMessage req, HttpMessage resp, String caption); }
interface SiteMapListener { void siteMapChanged(LogEntry e); }
interface ScopeListener { void scopeChanged(); }
```

## opentrap.core.HttpMessage

Always retains raw bytes; parsing is advisory.
```java
new HttpMessage(byte[]);  byte[] raw();  void setRaw(byte[]);  int length();  HttpMessage copy();
int bodyOffset();  byte[] body();  void setBody(byte[]);  String bodyText();
String headerText();  List<String> headerLines();  String startLine();
String header(String name);  boolean hasHeader(String);  void setHeader(String,String);  void removeHeader(String);
void updateContentLength();   boolean isRequest();
String method();  void setMethod(String);  String requestTarget();  void setRequestTarget(String);
String path();  String query();  String fileExtension();
int statusCode();  String statusText();  String contentType();  String mimeType();  String htmlTitle();
List<Param> parameters();  boolean hasParameters();
```

`opentrap.core.Param`: `String name(); String value(); Param.Type type(); int nameStart(); int nameEnd(); int valueStart(); int valueEnd(); String typeLabel();`
`Param.Type` = URL, BODY, COOKIE, MULTIPART, XML, JSON, HEADER.

## opentrap.core.HttpService
`new HttpService(String host,int port,boolean secure)`; `host() port() secure() protocol() url()`; `HttpService.parse(String url)`.

## opentrap.core.HttpClient
```java
HttpClient.Options o = new HttpClient.Options();
  // connectTimeoutMs readTimeoutMs updateContentLength unpackGzip followRedirects maxRedirects
HttpClient.Result r = new HttpClient().send(svc, requestMessage, o);
  // r.ok() r.response (HttpMessage) r.error (String) r.millis r.remoteIp r.redirectsFollowed
```

## opentrap.core.LogEntry
`number() tool() service() request() response() time() url() pathAndQuery() status() responseLen() mimeType() title() error() durationMillis() notes() highlight() edited() remoteIp()`
setters: `setResponse setRequest setError setDurationMillis setNotes setHighlight setEdited setRemoteIp`.
Construct: `new LogEntry(String tool, HttpService svc, HttpMessage request)`.

## opentrap.core.MessageStore
`add(LogEntry) all() size() get(i) clear() remove(e) replaceAll(list) addListener(MessageStore.Listener)`
`interface Listener { void entryAdded(LogEntry e); void entryUpdated(LogEntry e); }`

## opentrap.core.WebSocketStore
`add(WebSocketMessage) all() recent(n) size() get(i) clear() replaceAll(list) addListener(WebSocketStore.Listener)`
`interface Listener { void messageAdded(WebSocketMessage m); default messageRemoved(m) reloaded(list) }`

## opentrap.core.Scope
`boolean isInScope(HttpService svc, String path)`, `isInScope(LogEntry)`, `include()`, `exclude()` (List<Scope.Rule>), `includeUrl(svc,path,wholeHost)`, `excludeUrl(...)`.

## opentrap.core.Bytes
`indexOf concat slice str(byte[]) bytes(String) lines(String) isPrintableText toHex`
`Bytes.str` / `Bytes.bytes` use ISO-8859-1 so byte round-trips are lossless. Use them, never `new String(b)`.

## opentrap.ui.Ui  (use these so everything looks consistent)
```java
Ui.PLAIN Ui.BOLD Ui.MONO Ui.HEADING            // fonts
Ui.ACCENT Ui.GRID Ui.ERROR_RED Ui.ZEBRA        // colours
Ui.heading(s) Ui.label(s) Ui.bold(s) Ui.check(s,sel) Ui.radio(s,sel)
Ui.button(s) Ui.button(s,tooltip) Ui.field(s,cols) Ui.combo(T[]) Ui.note(s)
Ui.pad(t,l,b,r) Ui.row(comps...) Ui.vbox(comps...)
Ui.form("label:", field, "label:", field, ...)   // aligned form; see the rules below
Ui.filterBar(JButton, comps...)                  // shared-height filter or search strip
Ui.styleTable(JTable) Ui.setWidths(JTable,int...) Ui.scroll(Component) Ui.truncate(s,max)
```

## opentrap.ui.MessageEditor
```java
new MessageEditor(ctx, MessageEditor.REQUEST, editable)    // views: raw params headers hex
new MessageEditor(ctx, MessageEditor.RESPONSE, editable)   // views: raw headers hex render
void setMessage(HttpService svc, HttpMessage m)
void setMessage(HttpService svc, HttpMessage m, HttpMessage relatedRequest)
HttpMessage message();  byte[] bytes();  void setChangeListener(Runnable)
void addActionProvider(ActionProvider p);  JPopupMenu buildMenu();  void selectView(String)
```
It already provides the "send to <tool>" menu from `ctx.sinkNames()`.

## opentrap.ui.OptionsPage
```java
OptionsPage p = new OptionsPage();
p.section("heading", component1, component2, ...);   // stacked, rule between sections
p.addSection(component);
OptionsPage.fieldRow("label", jTextField, "suffix");
OptionsPage.indent(component, px);
```

## opentrap.ui.ScopeEditor
`new ScopeEditor(ctx, ctx.scope())` or any other Scope instance.

## Rules
- Package per tool: `opentrap.decoder`, `opentrap.comparer`, etc. One public class implementing `ToolModule`.
- No `System.exit`, no static mutable singletons, no printing to stdout.
- Long work goes on a background thread; touch Swing only via `SwingUtilities.invokeLater`.
- Register a sink in `init` if your tool can receive messages: `ctx.registerSink("decoder", (svc,req,resp,cap) -> {...})`.
- British spelling in user-visible text. Lower-case tab and control labels, as the original used.
- Lay out cleanly and align. Whenever a panel has more than one labelled input, build it with `Ui.form("label:", field, "label:", field, ...)` so the labels are right-aligned in one column and every field shares a left edge. For a filter or search strip use `Ui.filterBar(...)` so the controls share one height. Do not hand-place labelled fields with FlowLayout or padded-string labels; they never line up. Neat alignment is a requirement, not a nicety.
