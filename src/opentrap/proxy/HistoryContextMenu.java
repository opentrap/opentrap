package opentrap.proxy;

import opentrap.core.*;

import javax.swing.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/** Right-click actions for a history table: scope, send-to, copy, delete. */
public class HistoryContextMenu {

    private final SuiteContext ctx;
    private final JTable table;
    private final java.util.function.IntFunction<LogEntry> resolver;
    private final java.util.function.Consumer<LogEntry> remover;   // null: no delete action

    public HistoryContextMenu(SuiteContext ctx, JTable table, HistoryModel model) {
        this(ctx, table, model::at, model::remove);
    }

    /** For any table of entries: a row-to-entry resolver, and a remover (or null). */
    public HistoryContextMenu(SuiteContext ctx, JTable table,
                              java.util.function.IntFunction<LogEntry> resolver,
                              java.util.function.Consumer<LogEntry> remover) {
        this.ctx = ctx;
        this.table = table;
        this.resolver = resolver;
        this.remover = remover;
    }

    public void install() {
        table.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { maybe(e); }
            @Override public void mouseReleased(MouseEvent e) { maybe(e); }
            private void maybe(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && !table.isRowSelected(row)) table.setRowSelectionInterval(row, row);
                build().show(table, e.getX(), e.getY());
            }
        });
    }

    private List<LogEntry> selected() {
        List<LogEntry> out = new ArrayList<>();
        for (int r : table.getSelectedRows()) {
            LogEntry e = resolver.apply(table.convertRowIndexToModel(r));
            if (e != null) out.add(e);
        }
        return out;
    }

    private JPopupMenu build() {
        JPopupMenu menu = new JPopupMenu();
        List<LogEntry> sel = selected();
        if (sel.isEmpty()) return menu;
        LogEntry first = sel.get(0);
        boolean single = sel.size() == 1;

        JMenuItem title = new JMenuItem(opentrap.ui.Ui.truncate(first.url(), 60));
        title.setEnabled(false);
        menu.add(title);
        menu.addSeparator();

        // A single item in scope offers to leave it; anything else offers to join.
        boolean inScope = single && ctx.scope().isInScope(first);
        if (inScope) {
            add(menu, "remove item from scope", () -> {
                ctx.scope().removeUrl(first.service(), first.request() == null ? "/" : first.request().path(), false);
                ctx.scopeChanged();
            });
        } else {
            add(menu, single ? "add item to scope" : "add selection to scope", () -> {
                for (LogEntry e : sel) ctx.scope().includeUrl(e.service(), e.request() == null ? "/" : e.request().path(), false);
                ctx.scopeChanged();
            });
        }
        menu.addSeparator();

        // Scan actions, as the reference lists at the top of the menu.
        if (single && first.service() != null && ctx.canScan()) {
            add(menu, "actively scan this host", () -> ctx.launchScan(first.service().url(), true));
            add(menu, "crawl this host", () -> ctx.launchScan(first.service().url(), false));
            menu.addSeparator();
        }

        if (single) {
            // Standard send-to tools, flat (as the reference lists them), then the
            // engagement tools grouped in their own submenu rather than mixed in.
            for (String tool : ctx.sinkNames()) {
                if (opentrap.ui.Engagement.isEngagement(tool)) continue;
                final String t = tool;
                if (ctx.sinkTakesSingleItem(tool)) {
                    // A table row has no focused pane to infer a choice from, so
                    // the two halves are offered separately. One click still
                    // loads one item.
                    if (first.request() != null) {
                        add(menu, "send to " + t + " (request)", () -> ctx.sendTo(t,
                                first.service(), first.request().copy(), null, null));
                    }
                    if (first.response() != null) {
                        add(menu, "send to " + t + " (response)", () -> ctx.sendTo(t,
                                first.service(), null, first.response().copy(), null));
                    }
                } else {
                    add(menu, "send to " + t, () -> ctx.sendTo(t, first.service(),
                            first.request() == null ? null : first.request().copy(),
                            first.response() == null ? null : first.response().copy(), null));
                }
            }
            java.util.List<String> eng = new ArrayList<>();
            for (String tool : ctx.sinkNames()) if (opentrap.ui.Engagement.isEngagement(tool)) eng.add(tool);
            if (!eng.isEmpty() && first.request() != null) {
                JMenu sub = new JMenu("engagement tools");
                sub.setFont(opentrap.ui.Ui.PLAIN);
                for (String tool : eng) {
                    final String t = tool;
                    JMenuItem mi = new JMenuItem(opentrap.ui.Engagement.label(t));
                    mi.setFont(opentrap.ui.Ui.PLAIN);
                    mi.addActionListener(e -> ctx.sendTo(t, first.service(),
                            first.request() == null ? null : first.request().copy(),
                            first.response() == null ? null : first.response().copy(), null));
                    sub.add(mi);
                }
                menu.add(sub);
            }
            menu.addSeparator();
        }

        if (remover != null) {
            add(menu, single ? "delete item" : "delete selected items", () -> {
                for (LogEntry e : sel) remover.accept(e);
            });
        }
        if (single && first.response() != null) {
            add(menu, "open response in browser", () -> opentrap.ui.MessageActions.openResponseInBrowser(first.response()));
        }
        menu.addSeparator();
        add(menu, single ? "copy URL" : "copy selected URLs", () -> {
            StringBuilder sb = new StringBuilder();
            for (LogEntry e : sel) sb.append(e.url()).append('\n');
            opentrap.ui.MessageActions.clip(sb.toString().trim());
        });
        if (single && first.request() != null) {
            add(menu, "copy as curl command (bash)",
                    () -> opentrap.ui.MessageActions.clip(opentrap.ui.MessageActions.curl(first.service(), first.request())));
        }
        if (single && first.response() != null) {
            add(menu, "copy links in response",
                    () -> opentrap.ui.MessageActions.clip(opentrap.ui.MessageActions.linksIn(first.response(), first.url())));
        }
        if (single) {
            menu.addSeparator();
            add(menu, "record an issue", () -> opentrap.ui.ManualIssue.record(ctx.frame(), ctx,
                    first.service(), first.request(), first.response()));
            if (first.request() != null) {
                add(menu, "request in browser",
                        () -> opentrap.ui.MessageActions.openRequestInBrowser(ctx.frame(), first.service(), first.request()));
            }
        }
        return menu;
    }

    private void add(JPopupMenu menu, String label, Runnable action) {
        JMenuItem mi = new JMenuItem(label);
        mi.setFont(opentrap.ui.Ui.PLAIN);
        mi.addActionListener(e -> action.run());
        menu.add(mi);
    }
}
