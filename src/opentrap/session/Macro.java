package opentrap.session;

import opentrap.core.HttpClient;
import opentrap.core.HttpMessage;
import opentrap.core.HttpService;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recorded sequence of requests replayed to obtain or refresh a session. Each
 * step carries the cookies gathered so far, so a login flow spanning several
 * requests works, and named values can be extracted from the responses.
 */
public final class Macro implements Serializable {
    private static final long serialVersionUID = 1L;

    public String name = "";
    public final List<Item> items = new ArrayList<>();
    public final List<Extraction> extractions = new ArrayList<>();

    public Macro() {}
    public Macro(String name) { this.name = name; }

    /** One replayed request, stored as bytes so it is byte-exact. */
    public static final class Item implements Serializable {
        private static final long serialVersionUID = 1L;
        public String host = "";
        public int port;
        public boolean secure;
        public byte[] request = new byte[0];

        public Item() {}
        public Item(HttpService svc, HttpMessage req) {
            host = svc.host(); port = svc.port(); secure = svc.secure();
            request = req.raw().clone();
        }
        public HttpService service() { return new HttpService(host, port, secure); }
        public HttpMessage request()  { return new HttpMessage(request.clone()); }
        @Override public String toString() {
            HttpMessage m = request();
            return m.method() + " " + (secure ? "https://" : "http://") + host + m.path();
        }
    }

    public interface Recorder { void record(HttpService svc, HttpMessage req, HttpMessage resp); }

    /**
     * Replay every item in order, feeding cookies into the jar between steps,
     * and return the values named by the extraction rules (last response wins on
     * a name clash). A recorder, if given, logs each exchange.
     */
    public Map<String, String> run(CookieJar jar, Recorder recorder) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Item it : items) {
            HttpService svc = it.service();
            HttpMessage req = it.request();
            String merged = jar.headerFor(svc, req, req.header("Cookie"));
            if (merged != null && !merged.isEmpty()) req.setHeader("Cookie", merged);

            HttpClient.Options opt = new HttpClient.Options();
            opt.unpackGzip = true;
            opt.followRedirects = true;
            opt.updateContentLength = false;
            HttpClient.Result r = new HttpClient().send(svc, req, opt);
            if (!r.ok()) continue;

            jar.updateFrom(svc, r.response);
            for (Extraction ex : extractions) {
                String v = ex.extract(r.response, jar);
                if (v != null) out.put(ex.name, v);
            }
            if (recorder != null) recorder.record(svc, req, r.response);
        }
        return out;
    }

    @Override public String toString() { return name == null || name.isEmpty() ? "(unnamed macro)" : name; }
}
