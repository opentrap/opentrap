package opentrap.session;

import opentrap.core.HttpMessage;
import opentrap.core.HttpService;
import opentrap.core.LogEntry;
import opentrap.core.SessionHook;
import opentrap.core.SuiteContext;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Holds the cookie jar, the macros and the session-handling rules, and drives
 * them as the {@link SessionHook} the HttpClient consults around every send.
 *
 * <p>Any tool that passes this on its send options gets: cookies from the jar
 * applied to matching requests, Set-Cookie responses learned back into the jar,
 * and automatic re-authentication when a rule's trigger fires.
 */
public final class SessionManager implements SessionHook {

    private final SuiteContext ctx;
    private final CookieJar jar = new CookieJar();
    private final List<SessionRule> rules = new CopyOnWriteArrayList<>();
    private final List<Macro> macros = new CopyOnWriteArrayList<>();

    public SessionManager(SuiteContext ctx) { this.ctx = ctx; }

    public CookieJar jar()          { return jar; }
    public List<SessionRule> rules(){ return rules; }
    public List<Macro> macros()     { return macros; }

    private final List<Runnable> changeListeners = new CopyOnWriteArrayList<>();
    public void addChangeListener(Runnable r) { changeListeners.add(r); }
    private void fireChanged() { for (Runnable r : changeListeners) r.run(); }

    /**
     * Append a request to a macro, used by the "send to session macro" action.
     * Adds to the last macro, creating a "login" macro if there are none, so the
     * action works whether or not the session-handling window is open.
     */
    public void recordToMacro(HttpService svc, HttpMessage req) {
        if (svc == null || req == null) return;
        Macro m = macros.isEmpty() ? null : macros.get(macros.size() - 1);
        if (m == null) { m = new Macro("login"); macros.add(m); }
        m.items.add(new Macro.Item(svc, req.copy()));
        if (ctx != null) ctx.alert("session", "added a request to macro '" + m.name + "'");
        fireChanged();
    }

    public Macro macro(String name) {
        if (name == null) return null;
        for (Macro m : macros) if (name.equals(m.name)) return m;
        return null;
    }

    // ---- SessionHook ----

    @Override public void beforeSend(String tool, HttpService svc, HttpMessage req) {
        if (svc == null || req == null || !req.isRequest()) return;
        boolean apply = false;
        for (SessionRule r : rules) if (r.useCookieJar && r.appliesTo(tool, svc)) { apply = true; break; }
        if (!apply) return;
        String merged = jar.headerFor(svc, req, req.header("Cookie"));
        if (merged != null && !merged.isEmpty()) req.setHeader("Cookie", merged);
    }

    @Override public boolean afterResponse(String tool, HttpService svc, HttpMessage req, HttpMessage resp) {
        if (svc == null || resp == null) return false;
        jar.updateFrom(svc, resp); // always learn cookies, rule or not
        for (SessionRule r : rules) {
            if (r.appliesTo(tool, svc) && r.triggered(resp)) {
                Macro m = macro(r.macroName);
                if (m != null) { runMacro(m); return true; } // re-auth, then retry once
            }
        }
        return false;
    }

    /** Run a macro now, logging its traffic so it is visible in the Logger. */
    public Map<String, String> runMacro(Macro m) {
        if (m == null) return Map.of();
        Macro.Recorder rec = ctx == null ? null : (svc, rq, rp) -> {
            LogEntry e = new LogEntry("session", svc, rq);
            e.setResponse(rp);
            ctx.store().add(e);
            ctx.siteMapChanged(e);
        };
        Map<String, String> vals = m.run(jar, rec);
        if (ctx != null) {
            ctx.alert("session", "ran macro '" + m.name + "'"
                    + (vals.isEmpty() ? "" : ", extracted " + vals.keySet()));
        }
        return vals;
    }

    // ---- persistence ----

    public State snapshot() {
        State s = new State();
        s.jar = jar.all();
        s.rules = new ArrayList<>(rules);
        s.macros = new ArrayList<>(macros);
        return s;
    }

    public void restore(State s) {
        if (s == null) return;
        jar.clear();
        if (s.jar != null) for (Cookie c : s.jar) jar.set(c);
        rules.clear();
        if (s.rules != null) rules.addAll(s.rules);
        macros.clear();
        if (s.macros != null) macros.addAll(s.macros);
    }

    public static final class State implements Serializable {
        private static final long serialVersionUID = 1L;
        public List<Cookie> jar;
        public List<SessionRule> rules;
        public List<Macro> macros;
    }
}
