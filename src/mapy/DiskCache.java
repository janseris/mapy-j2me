package mapy;

import java.io.*;
import java.util.Enumeration;
import java.util.Hashtable;
import javax.microedition.rms.*;

/**
 * Persistent LRU byte cache in RMS (the phone's record store, on C:), for map tiles and photos.
 * RMS needs no permission prompts, unlike file access from an unsigned MIDlet.
 *
 * One record store holds the data, a second one the index (key -> record id, size, last use,
 * time saved), kept in memory and written back every few changes and on exit. When the total
 * passes the limit, the least recently used entries are deleted. Entries older than MAX_AGE are
 * fetched again (OSM asks apps to keep tiles at least 7 days).
 */
public class DiskCache {
    static final String DATA = "mapy_cache", INDEX = "mapy_cache_idx";
    static final long MAX_AGE = 30L * 24 * 3600 * 1000;

    static class Entry {
        int id, size;
        long used, saved;
    }

    private static RecordStore data;
    private static final Hashtable index = new Hashtable();    // key -> Entry
    private static long total;
    private static int dirty;
    private static boolean opened, broken;

    public static long limit() { return Settings.cacheMB * 1024L * 1024L; }

    private static synchronized boolean open() {
        if (opened) return !broken;
        opened = true;
        try {
            data = RecordStore.openRecordStore(DATA, true);
            RecordStore idx = RecordStore.openRecordStore(INDEX, true);
            if (idx.getNumRecords() > 0) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(idx.getRecord(1)));
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    String k = in.readUTF();
                    Entry e = new Entry();
                    e.id = in.readInt(); e.size = in.readInt(); e.used = in.readLong(); e.saved = in.readLong();
                    index.put(k, e);
                    total += e.size;
                }
            }
            idx.closeRecordStore();
            Log.add("disk cache: " + index.size() + " entries, " + (total / 1024) + " KB, limit " + Settings.cacheMB + " MB");
        } catch (Throwable e) {
            broken = true;
            Log.add("disk cache unavailable: " + e);
        }
        return !broken;
    }

    public static synchronized byte[] get(String key) {
        if (Settings.cacheMB <= 0 || !open()) return null;
        Entry e = (Entry) index.get(key);
        if (e == null) return null;
        long now = System.currentTimeMillis();
        if (now - e.saved > MAX_AGE) { remove(key, e); return null; }
        try {
            byte[] b = data.getRecord(e.id);
            e.used = now;
            if (++dirty >= 50) saveIndex();
            return b;
        } catch (Throwable ex) {
            remove(key, e);
            return null;
        }
    }

    public static synchronized void put(String key, byte[] b) {
        if (Settings.cacheMB <= 0 || b == null || b.length == 0 || b.length > limit() / 4 || !open()) return;
        try {
            Entry old = (Entry) index.get(key);
            if (old != null) remove(key, old);
            while (total + b.length > limit() && index.size() > 0) evictOldest();
            Entry e = new Entry();
            e.id = data.addRecord(b, 0, b.length);
            e.size = b.length;
            e.used = e.saved = System.currentTimeMillis();
            index.put(key, e);
            total += b.length;
            if (++dirty >= 10) saveIndex();
        } catch (RecordStoreFullException ex) {
            // phone memory full: shrink the cache by a quarter and stop for now
            long target = total * 3 / 4;
            while (total > target && index.size() > 0) evictOldest();
            Log.add("disk cache: store full, trimmed to " + (total / 1024) + " KB");
        } catch (Throwable ex) {
            Log.add("disk cache put: " + ex);
        }
    }

    private static void evictOldest() {
        String oldest = null;
        long t = Long.MAX_VALUE;
        for (Enumeration en = index.keys(); en.hasMoreElements();) {
            String k = (String) en.nextElement();
            Entry e = (Entry) index.get(k);
            if (e.used < t) { t = e.used; oldest = k; }
        }
        if (oldest != null) remove(oldest, (Entry) index.get(oldest));
    }

    /** Whether the cache has a fresh entry for key (index only, no reading). */
    public static synchronized boolean has(String key) {
        if (Settings.cacheMB <= 0 || !open()) return false;
        Entry e = (Entry) index.get(key);
        return e != null && System.currentTimeMillis() - e.saved <= MAX_AGE;
    }

    public static synchronized void remove(String key) {
        Entry e = (Entry) index.get(key);
        if (e != null) remove(key, e);
    }

    private static void remove(String key, Entry e) {
        index.remove(key);
        total -= e.size;
        try { data.deleteRecord(e.id); } catch (Throwable ex) {}
        dirty++;
    }

    public static synchronized void saveIndex() {
        if (!opened || broken) return;
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream(index.size() * 40 + 8);
            DataOutputStream o = new DataOutputStream(bo);
            o.writeInt(index.size());
            for (Enumeration en = index.keys(); en.hasMoreElements();) {
                String k = (String) en.nextElement();
                Entry e = (Entry) index.get(k);
                o.writeUTF(k); o.writeInt(e.id); o.writeInt(e.size); o.writeLong(e.used); o.writeLong(e.saved);
            }
            byte[] b = bo.toByteArray();
            RecordStore idx = RecordStore.openRecordStore(INDEX, true);
            if (idx.getNumRecords() == 0) idx.addRecord(b, 0, b.length);
            else idx.setRecord(1, b, 0, b.length);
            idx.closeRecordStore();
            dirty = 0;
        } catch (Throwable e) {
            Log.add("disk cache index: " + e);
        }
    }

    /** Deletes everything (Settings: clear cache). */
    public static synchronized void clear() {
        try {
            if (data != null) data.closeRecordStore();
        } catch (Throwable e) {}
        try { RecordStore.deleteRecordStore(DATA); } catch (Throwable e) {}
        try { RecordStore.deleteRecordStore(INDEX); } catch (Throwable e) {}
        index.clear();
        total = 0;
        opened = false;
        broken = false;
        data = null;
    }

    public static synchronized String summary() {
        if (!open()) return "unavailable";
        return index.size() + " items, " + (total / 1024) + " KB of " + Settings.cacheMB + " MB";
    }

    /** Disk-cached HTTP GET (only 200 responses are stored). */
    public static byte[] fetch(String url, String label) throws IOException {
        byte[] b = get(url);
        if (b != null) return b;
        Net.Response r = Net.get(url, label);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        put(url, r.body);
        return r.body;
    }
}
