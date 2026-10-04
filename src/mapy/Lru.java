package mapy;

import java.util.Hashtable;
import java.util.Vector;

/** Small in-memory LRU map (CLDC has no LinkedHashMap). */
public class Lru {
    private final Hashtable map = new Hashtable();
    private final Vector order = new Vector();      // least recently used first
    private final int max;

    public Lru(int max) { this.max = max; }

    public synchronized Object get(Object key) {
        Object v = map.get(key);
        if (v != null) { order.removeElement(key); order.addElement(key); }
        return v;
    }

    public synchronized boolean has(Object key) { return map.containsKey(key); }

    public synchronized void put(Object key, Object value) {
        if (map.containsKey(key)) order.removeElement(key);
        map.put(key, value);
        order.addElement(key);
        while (order.size() > max) {
            map.remove(order.elementAt(0));
            order.removeElementAt(0);
        }
    }

    /** Keeps only the n most recently used entries (after OutOfMemoryError). */
    public synchronized void trim(int n) {
        while (order.size() > n) {
            map.remove(order.elementAt(0));
            order.removeElementAt(0);
        }
    }

    public synchronized int size() { return order.size(); }

    public synchronized void clear() { map.clear(); order.removeAllElements(); }
}
