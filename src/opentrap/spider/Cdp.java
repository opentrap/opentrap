package opentrap.spider;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/**
 * Minimal Chrome DevTools Protocol (CDP) client over a WebSocket.
 *
 * <p>Uses only the JDK-built-in {@link java.net.http.HttpClient} and
 * {@link java.net.http.WebSocket}. JSON is handled by the tiny hand-rolled
 * {@link JsonValue} parser below; no external libraries are used.</p>
 */
public final class Cdp {

    private final HttpClient http;
    private final WebSocket ws;
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<JsonValue>> pending = new ConcurrentHashMap<>();
    private final StringBuilder textBuf = new StringBuilder();
    private volatile BiConsumer<String, String> eventListener;

    private Cdp(HttpClient http, WebSocket ws) {
        this.http = http;
        this.ws = ws;
    }

    /** Opens a WebSocket to a CDP page target and returns a connected client. */
    public static Cdp connect(String wsUrl) {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        Cdp[] holder = new Cdp[1];
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public void onOpen(WebSocket webSocket) {
                webSocket.request(1);
            }

            @Override
            public CompletableFuture<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                Cdp self = holder[0];
                if (self != null) {
                    self.handleText(data, last);
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                Cdp self = holder[0];
                if (self != null) {
                    self.failAll(error);
                }
            }

            @Override
            public CompletableFuture<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                Cdp self = holder[0];
                if (self != null) {
                    self.failAll(new IllegalStateException("websocket closed: " + statusCode + " " + reason));
                }
                return null;
            }
        };
        WebSocket socket = http.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(URI.create(wsUrl), listener)
                .join();
        Cdp cdp = new Cdp(http, socket);
        holder[0] = cdp;
        return cdp;
    }

    /** Registers an optional listener for CDP events (method name + raw params JSON). */
    public void onEvent(BiConsumer<String, String> methodAndParamsJson) {
        this.eventListener = methodAndParamsJson;
    }

    private synchronized void handleText(CharSequence data, boolean last) {
        textBuf.append(data);
        if (!last) {
            return;
        }
        String msg = textBuf.toString();
        textBuf.setLength(0);
        dispatch(msg);
    }

    private void dispatch(String msg) {
        JsonValue root;
        try {
            root = JsonValue.parse(msg);
        } catch (RuntimeException ex) {
            return; // ignore unparsable frames
        }
        JsonValue idv = root.get("id");
        if (idv != null && idv.isNumber()) {
            int id = idv.asInt();
            CompletableFuture<JsonValue> fut = pending.remove(id);
            if (fut != null) {
                JsonValue err = root.get("error");
                if (err != null) {
                    fut.completeExceptionally(new RuntimeException("CDP error: " + err.raw()));
                } else {
                    JsonValue result = root.get("result");
                    fut.complete(result != null ? result : JsonValue.parse("{}"));
                }
            }
            return;
        }
        // Event message (no id).
        BiConsumer<String, String> l = eventListener;
        if (l != null) {
            JsonValue m = root.get("method");
            if (m != null && m.isString()) {
                JsonValue params = root.get("params");
                try {
                    l.accept(m.asString(), params != null ? params.raw() : "{}");
                } catch (RuntimeException ignore) {
                    // listener must not break the dispatch loop
                }
            }
        }
    }

    private void failAll(Throwable error) {
        for (Integer id : new ArrayList<>(pending.keySet())) {
            CompletableFuture<JsonValue> fut = pending.remove(id);
            if (fut != null) {
                fut.completeExceptionally(error);
            }
        }
    }

    /**
     * Sends a CDP command and blocks until the matching result arrives.
     *
     * @param method     the CDP method, e.g. "Page.navigate"
     * @param paramsJson  a raw JSON object string (may be {@code "{}"} or {@code null})
     * @return the {@code result} object of the response
     */
    public JsonValue send(String method, String paramsJson) {
        int id = nextId.getAndIncrement();
        String params = (paramsJson == null || paramsJson.isBlank()) ? "{}" : paramsJson.trim();
        String frame = "{\"id\":" + id + ",\"method\":\"" + jsonEscape(method)
                + "\",\"params\":" + params + "}";
        CompletableFuture<JsonValue> fut = new CompletableFuture<>();
        pending.put(id, fut);
        try {
            ws.sendText(frame, true).join();
        } catch (RuntimeException ex) {
            pending.remove(id);
            throw new RuntimeException("failed to send CDP command " + method, ex);
        }
        try {
            return fut.get(30, TimeUnit.SECONDS);
        } catch (Exception ex) {
            pending.remove(id);
            throw new RuntimeException("CDP command " + method + " failed/timed out", ex);
        }
    }

    /** Closes the WebSocket and releases resources. */
    public void close() {
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        } catch (RuntimeException ignore) {
            // ignore
        }
        try {
            ws.abort();
        } catch (RuntimeException ignore) {
            // ignore
        }
        failAll(new IllegalStateException("cdp closed"));
    }

    /** Escapes a string for inclusion in a JSON string literal (without surrounding quotes). */
    public static String jsonEscape(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // Minimal JSON value: parser + accessors. Correct for CDP responses.
    // ------------------------------------------------------------------

    /**
     * A tiny immutable JSON value. Supports objects, arrays, strings (with
     * escapes), numbers, booleans and null. Enough to navigate CDP responses.
     */
    public static final class JsonValue {

        public enum Kind { OBJECT, ARRAY, STRING, NUMBER, BOOLEAN, NULL }

        private final Kind kind;
        private final Object value; // Map<String,JsonValue> | List<JsonValue> | String | Double | Boolean | null
        private final String raw;   // the exact source substring this value spans

        private JsonValue(Kind kind, Object value, String raw) {
            this.kind = kind;
            this.value = value;
            this.raw = raw;
        }

        /** Parses a complete JSON document from {@code text}. */
        public static JsonValue parse(String text) {
            Parser p = new Parser(text);
            p.skipWs();
            JsonValue v = p.parseValue();
            p.skipWs();
            return v;
        }

        public Kind kind() {
            return kind;
        }

        public boolean isObject() {
            return kind == Kind.OBJECT;
        }

        public boolean isArray() {
            return kind == Kind.ARRAY;
        }

        public boolean isString() {
            return kind == Kind.STRING;
        }

        public boolean isNumber() {
            return kind == Kind.NUMBER;
        }

        public boolean isBoolean() {
            return kind == Kind.BOOLEAN;
        }

        public boolean isNull() {
            return kind == Kind.NULL;
        }

        /** Returns the child value for {@code key}, or {@code null} if absent / not an object. */
        @SuppressWarnings("unchecked")
        public JsonValue get(String key) {
            if (kind != Kind.OBJECT) {
                return null;
            }
            return ((Map<String, JsonValue>) value).get(key);
        }

        /** The keys of this object, or an empty set when it is not an object. */
        @SuppressWarnings("unchecked")
        public java.util.Set<String> keys() {
            if (kind != Kind.OBJECT) return java.util.Collections.emptySet();
            return ((Map<String, JsonValue>) value).keySet();
        }

        /** Returns the element at {@code index}, or {@code null} if out of range / not an array. */
        @SuppressWarnings("unchecked")
        public JsonValue at(int index) {
            if (kind != Kind.ARRAY) {
                return null;
            }
            List<JsonValue> list = (List<JsonValue>) value;
            if (index < 0 || index >= list.size()) {
                return null;
            }
            return list.get(index);
        }

        /** Returns the array elements (empty list if this is not an array). */
        @SuppressWarnings("unchecked")
        public List<JsonValue> asArray() {
            if (kind != Kind.ARRAY) {
                return List.of();
            }
            return (List<JsonValue>) value;
        }

        /** Returns the string value (for STRING), or the raw text for other kinds. */
        public String asString() {
            if (kind == Kind.STRING) {
                return (String) value;
            }
            if (kind == Kind.NULL) {
                return null;
            }
            return raw;
        }

        /** Returns an int value (truncated for numbers; 0 otherwise). */
        public int asInt() {
            if (kind == Kind.NUMBER) {
                return (int) (double) (Double) value;
            }
            if (kind == Kind.STRING) {
                try {
                    return (int) Double.parseDouble((String) value);
                } catch (NumberFormatException ignore) {
                    return 0;
                }
            }
            return 0;
        }

        /** Returns a long value (truncated for numbers; 0 otherwise). */
        public long asLong() {
            if (kind == Kind.NUMBER) {
                return (long) (double) (Double) value;
            }
            return 0L;
        }

        /** Returns a double value (0.0 if not a number). */
        public double asDouble() {
            if (kind == Kind.NUMBER) {
                return (Double) value;
            }
            return 0.0;
        }

        /** Returns the boolean value (false unless this is a true boolean). */
        public boolean asBoolean() {
            return kind == Kind.BOOLEAN && (Boolean) value;
        }

        /** The exact source substring this value was parsed from. */
        public String raw() {
            return raw;
        }

        @Override
        public String toString() {
            return raw;
        }

        // --- recursive-descent parser ---

        private static final class Parser {
            private final String s;
            private int i;

            Parser(String s) {
                this.s = s;
            }

            void skipWs() {
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                        i++;
                    } else {
                        break;
                    }
                }
            }

            JsonValue parseValue() {
                skipWs();
                if (i >= s.length()) {
                    throw new RuntimeException("unexpected end of JSON");
                }
                char c = s.charAt(i);
                return switch (c) {
                    case '{' -> parseObject();
                    case '[' -> parseArray();
                    case '"' -> parseString();
                    case 't', 'f' -> parseBoolean();
                    case 'n' -> parseNull();
                    default -> parseNumber();
                };
            }

            JsonValue parseObject() {
                int start = i;
                expect('{');
                Map<String, JsonValue> map = new java.util.LinkedHashMap<>();
                skipWs();
                if (peek() == '}') {
                    i++;
                    return new JsonValue(Kind.OBJECT, map, s.substring(start, i));
                }
                while (true) {
                    skipWs();
                    JsonValue key = parseString();
                    skipWs();
                    expect(':');
                    JsonValue val = parseValue();
                    map.put(key.asString(), val);
                    skipWs();
                    char n = next();
                    if (n == ',') {
                        continue;
                    }
                    if (n == '}') {
                        break;
                    }
                    throw new RuntimeException("expected ',' or '}' at " + (i - 1));
                }
                return new JsonValue(Kind.OBJECT, map, s.substring(start, i));
            }

            JsonValue parseArray() {
                int start = i;
                expect('[');
                List<JsonValue> list = new ArrayList<>();
                skipWs();
                if (peek() == ']') {
                    i++;
                    return new JsonValue(Kind.ARRAY, list, s.substring(start, i));
                }
                while (true) {
                    JsonValue val = parseValue();
                    list.add(val);
                    skipWs();
                    char n = next();
                    if (n == ',') {
                        continue;
                    }
                    if (n == ']') {
                        break;
                    }
                    throw new RuntimeException("expected ',' or ']' at " + (i - 1));
                }
                return new JsonValue(Kind.ARRAY, list, s.substring(start, i));
            }

            JsonValue parseString() {
                int start = i;
                expect('"');
                StringBuilder sb = new StringBuilder();
                while (true) {
                    if (i >= s.length()) {
                        throw new RuntimeException("unterminated string");
                    }
                    char c = s.charAt(i++);
                    if (c == '"') {
                        break;
                    }
                    if (c == '\\') {
                        if (i >= s.length()) {
                            throw new RuntimeException("bad escape");
                        }
                        char e = s.charAt(i++);
                        switch (e) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> {
                                if (i + 4 > s.length()) {
                                    throw new RuntimeException("bad unicode escape");
                                }
                                String hex = s.substring(i, i + 4);
                                i += 4;
                                try {
                                    sb.append((char) Integer.parseInt(hex, 16));
                                } catch (NumberFormatException nfe) {
                                    // A unicode escape that is not hex is a
                                    // parse error like any other, not a crash.
                                    throw new RuntimeException("bad unicode escape: " + hex);
                                }
                            }
                            default -> throw new RuntimeException("bad escape char: " + e);
                        }
                    } else {
                        sb.append(c);
                    }
                }
                return new JsonValue(Kind.STRING, sb.toString(), s.substring(start, i));
            }

            JsonValue parseBoolean() {
                int start = i;
                if (s.startsWith("true", i)) {
                    i += 4;
                    return new JsonValue(Kind.BOOLEAN, Boolean.TRUE, s.substring(start, i));
                }
                if (s.startsWith("false", i)) {
                    i += 5;
                    return new JsonValue(Kind.BOOLEAN, Boolean.FALSE, s.substring(start, i));
                }
                throw new RuntimeException("invalid boolean at " + i);
            }

            JsonValue parseNull() {
                int start = i;
                if (s.startsWith("null", i)) {
                    i += 4;
                    return new JsonValue(Kind.NULL, null, s.substring(start, i));
                }
                throw new RuntimeException("invalid null at " + i);
            }

            JsonValue parseNumber() {
                int start = i;
                if (peek() == '-') {
                    i++;
                }
                while (i < s.length()) {
                    char c = s.charAt(i);
                    if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E'
                            || c == '+' || c == '-') {
                        i++;
                    } else {
                        break;
                    }
                }
                String num = s.substring(start, i);
                if (num.isEmpty()) {
                    throw new RuntimeException("invalid number at " + start);
                }
                try {
                    return new JsonValue(Kind.NUMBER, Double.parseDouble(num), num);
                } catch (NumberFormatException nfe) {
                    // A number-shaped run the JVM rejects (an exponent cut
                    // short, a bare sign) is a parse error like any other.
                    throw new RuntimeException("invalid number at " + start + ": " + num);
                }
            }

            char peek() {
                skipWs();
                if (i >= s.length()) {
                    throw new RuntimeException("unexpected end of JSON");
                }
                return s.charAt(i);
            }

            char next() {
                if (i >= s.length()) {
                    throw new RuntimeException("unexpected end of JSON");
                }
                return s.charAt(i++);
            }

            void expect(char c) {
                skipWs();
                if (i >= s.length() || s.charAt(i) != c) {
                    throw new RuntimeException("expected '" + c + "' at " + i);
                }
                i++;
            }
        }
    }
}
