package opentrap.target;

import opentrap.core.HttpService;
import opentrap.core.LogEntry;

import javax.swing.tree.DefaultMutableTreeNode;
import java.util.*;

/**
 * A deduplicated structural view of observed traffic.
 *
 * Unlike the proxy history (which keeps every exchange), the site map keeps one
 * entry per unique method + URL (path and query). Re-requesting the same URL
 * updates the existing entry with the latest request and response rather than
 * adding a duplicate. Different query values are different entries.
 */
public class SiteMap {

    public static class Node {
        public final String label;
        public final boolean isHost;
        public HttpService service;
        public String path = "";
        public LogEntry entry;              // representative leaf request, if any
        public final Map<String, Node> children = new LinkedHashMap<>();

        // Deduplicated: key = method + " " + pathAndQuery -> latest exchange.
        private final Map<String, LogEntry> entryMap = new LinkedHashMap<>();

        Node(String label, boolean isHost) { this.label = label; this.isHost = isHost; }

        void put(LogEntry e) {
            entryMap.put(key(e), e);   // replaces any earlier exchange for the same URL
        }

        /** The deduplicated entries at or below this node. */
        public List<LogEntry> entries() { return new ArrayList<>(entryMap.values()); }

        @Override public String toString() { return label; }
    }

    static String key(LogEntry e) {
        return e.method() + " " + e.pathAndQuery();
    }

    private final Node root = new Node("", false);

    public Node root() { return root; }

    public synchronized void add(LogEntry e) {
        if (e.service() == null || !e.hasRequest()) return;
        String hostKey = e.service().url();
        Node host = root.children.computeIfAbsent(hostKey, k -> {
            Node n = new Node(e.service().url(), true);
            n.service = e.service();
            return n;
        });
        host.put(e);

        String path = e.requestPath();
        String[] parts = path.split("/");
        Node cur = host;
        StringBuilder acc = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            acc.append('/').append(p);
            final String seg = p;
            Node child = cur.children.computeIfAbsent(seg, k -> {
                Node n = new Node(seg, false);
                n.service = e.service();
                n.path = acc.toString();
                return n;
            });
            child.put(e);
            cur = child;
        }
        cur.entry = e;   // latest representative for the leaf

        // Parameterised requests branch under the endpoint, so /x?a=1 and
        // /x?a=2 are distinct children of x (each query variant is its own node).
        String query = e.requestQuery();
        if (query != null && !query.isEmpty()) {
            final Node leaf = cur;
            String ckey = "?" + e.method() + " " + query;
            Node pchild = cur.children.computeIfAbsent(ckey, k -> {
                Node n = new Node("?" + query, false);
                n.service = e.service();
                n.path = leaf.path;
                return n;
            });
            pchild.put(e);
            pchild.entry = e;
        }
    }

    public synchronized DefaultMutableTreeNode toTreeNode() {
        DefaultMutableTreeNode r = new DefaultMutableTreeNode(root);
        for (Node host : root.children.values()) addChildren(r, host);
        return r;
    }

    private void addChildren(DefaultMutableTreeNode parent, Node node) {
        DefaultMutableTreeNode tn = new DefaultMutableTreeNode(node);
        parent.add(tn);
        for (Node c : node.children.values()) addChildren(tn, c);
    }

    public synchronized void clear() { root.children.clear(); }
}
