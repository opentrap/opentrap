package opentrap.organizer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Holds the Organizer's saved items and notifies listeners on any change. */
public final class OrganizerStore {

    public interface Listener { void changed(); }

    private final List<OrganizerItem> items = new CopyOnWriteArrayList<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public void add(OrganizerItem i)    { if (i != null) { items.add(i); fire(); } }
    public void remove(OrganizerItem i) { items.remove(i); fire(); }
    public void clear()                 { items.clear(); fire(); }
    public List<OrganizerItem> all()    { return new ArrayList<>(items); }
    public void addListener(Listener l) { listeners.add(l); }
    public void changed()               { fire(); }

    private void fire() { for (Listener l : listeners) l.changed(); }

    public ArrayList<OrganizerItem> snapshot() { return new ArrayList<>(items); }
    public void restore(List<OrganizerItem> list) {
        items.clear();
        if (list != null) items.addAll(list);
        fire();
    }
}
