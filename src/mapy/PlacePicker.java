package mapy;

import java.io.*;
import java.util.Vector;
import javax.microedition.lcdui.*;
import javax.microedition.rms.RecordStore;

/**
 * Odkud / Kam picker, like the pubtran app's: typing on the keyboard goes straight into the
 * field, Mapy.com suggestions come when typing pauses (from 2 characters), one request at a
 * time. With an empty field: "my position", "point under the map cursor" and recent places.
 * Up/Down move the highlight, Enter picks; "Edit text" opens the phone's editor (accents).
 */
public class PlacePicker extends Canvas implements CommandListener {
    public interface Picked {
        void picked(Place p);
    }

    static final Command PICK = new Command("Select", Command.SCREEN, 1);
    static final Command EDIT = new Command("Edit text", Command.SCREEN, 2);
    static final Command SEARCH = new Command("Search", Command.SCREEN, 3);
    static final Command BACK = new Command("Back", Command.BACK, 4);
    static final Command TB_OK = new Command("Search", Command.OK, 1);
    static final Command TB_BACK = new Command("Back", Command.BACK, 2);
    static final int MIN_CHARS = 2, DELAY_MS = 800;

    /** Special places, resolved when the route is planned. */
    static final String GPS = "@gps";

    final Mapy app;
    final Displayable back;
    final Picked picked;
    String query = "";
    volatile String status = "";
    volatile Vector places = new Vector();
    int selected = 0, scroll;
    volatile int generation;
    volatile boolean busy;
    String lastSearched;
    java.util.Timer timer;
    TextBox editor;

    PlacePicker(Mapy app, String title, Displayable back, Picked picked) {
        this.app = app;
        this.back = back;
        this.picked = picked;
        setTitle(title);
        addCommand(PICK); addCommand(EDIT); addCommand(SEARCH); addCommand(BACK);
        setCommandListener(this);
        showStart();
    }

    static Place gpsPlace() {
        Place p = new Place();
        p.title = "My position (GPS)";
        p.source = GPS;
        return p;
    }

    void showStart() {
        generation++;
        Vector v = new Vector();
        v.addElement(gpsPlace());
        MapCanvas m = app.map;
        m.initCursor();
        Place c = new Place();
        c.lon = Geo.xToLon(m.wx(m.mx), m.zoom);
        c.lat = Geo.yToLat(m.wy(m.my), m.zoom);
        c.title = m.hovered != null ? m.hovered.title : "Point under the map cursor";
        c.subtitle = Geo.format(c.lat, c.lon);
        if (m.hovered != null) { c.lon = m.hovered.lon; c.lat = m.hovered.lat; }
        v.addElement(c);
        Vector r = recent();
        for (int i = 0; i < r.size(); i++) v.addElement(r.elementAt(i));
        places = v;
        status = "Type a place name or address. Recent places:";
        selected = 0;
        scroll = 0;
        repaint();
    }

    // ------------------------------------------------------------ search

    void edited() {
        generation++;
        cancelTimer();
        String q = query.trim();
        if (q.length() == 0) { lastSearched = null; showStart(); return; }
        if (q.length() >= MIN_CHARS) {
            status = busy ? "Searching... (then \"" + q + "\")" : "Searching after a pause in typing...";
            timer = new java.util.Timer();
            timer.schedule(new java.util.TimerTask() {
                public void run() {
                    if (!busy && !query.trim().equals(lastSearched)) searchNow();
                }
            }, DELAY_MS);
        } else status = "Keep typing...";
        repaint();
    }

    void cancelTimer() {
        if (timer != null) { timer.cancel(); timer = null; }
    }

    void searchNow() {
        final String q = query.trim();
        if (q.length() == 0) { showStart(); return; }
        if (busy) return;
        busy = true;
        final int gen = ++generation;
        lastSearched = q;
        new Thread() {
            public void run() {
                try {
                    status = "Searching \"" + q + "\"...";
                    repaint();
                    MapCanvas m = app.map;
                    Vector r = MapyApi.suggest(q, m.centerLon(), m.centerLat(), m.viewBox(0), m.zoom);
                    if (gen == generation) {
                        places = r;
                        selected = r.size() > 0 ? 0 : -1;
                        scroll = 0;
                        status = r.size() == 0 ? "Nothing found." : "Suggestions (" + r.size() + ") - Enter = select";
                    }
                } catch (Throwable e) {
                    if (gen == generation) status = "Error: " + e.getMessage();
                    Log.add("picker suggest: " + e);
                } finally {
                    busy = false;
                }
                repaint();
                String now = query.trim();
                if (now.length() >= MIN_CHARS && !now.equals(lastSearched) && isShown()) searchNow();
            }
        }.start();
    }

    void pick(int i) {
        Vector v = places;
        if (i < 0 || i >= v.size()) return;
        Place p = (Place) v.elementAt(i);
        if (!GPS.equals(p.source)) addRecent(p);
        close();
        picked.picked(p);
    }

    void close() {
        cancelTimer();
        generation++;
        app.display.setCurrent(back);
    }

    // ------------------------------------------------------------ recent places (RMS)

    static Vector recent() {
        Vector v = new Vector();
        try {
            RecordStore rs = RecordStore.openRecordStore("mapy_recent", true);
            if (rs.getNumRecords() > 0) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
                int n = in.readInt();
                for (int i = 0; i < n; i++) {
                    Place p = new Place();
                    p.title = in.readUTF();
                    p.subtitle = in.readUTF();
                    p.source = in.readUTF();
                    p.id = in.readLong();
                    p.lat = in.readDouble();
                    p.lon = in.readDouble();
                    v.addElement(p);
                }
            }
            rs.closeRecordStore();
        } catch (Throwable e) {
            Log.add("recent places: " + e);
        }
        return v;
    }

    static void addRecent(Place p) {
        Vector v = recent();
        for (int i = v.size() - 1; i >= 0; i--) {
            Place q = (Place) v.elementAt(i);
            if (q.title.equals(p.title) && Geo.distance(q.lon, q.lat, p.lon, p.lat) < 50) v.removeElementAt(i);
        }
        v.insertElementAt(p, 0);
        while (v.size() > 12) v.removeElementAt(v.size() - 1);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeInt(v.size());
            for (int i = 0; i < v.size(); i++) {
                Place q = (Place) v.elementAt(i);
                o.writeUTF(q.title); o.writeUTF(q.subtitle); o.writeUTF(q.source);
                o.writeLong(q.id); o.writeDouble(q.lat); o.writeDouble(q.lon);
            }
            byte[] b = bo.toByteArray();
            RecordStore rs = RecordStore.openRecordStore("mapy_recent", true);
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length); else rs.setRecord(1, b, 0, b.length);
            rs.closeRecordStore();
        } catch (Throwable e) {
            Log.add("recent save: " + e);
        }
    }

    // ------------------------------------------------------------ keys

    protected void keyPressed(int k) { key(k, false); }
    protected void keyRepeated(int k) { key(k, true); }

    void key(int k, boolean repeat) {
        if (k == 10 || k == 13) { if (!repeat) activate(); return; }
        if (k == 8 || k == -8 || k == 127) {
            if (query.length() > 0) { query = query.substring(0, query.length() - 1); edited(); }
            return;
        }
        if (k >= 32) {
            if (query.length() < 64) { query += (char) k; edited(); }
            return;
        }
        int a = 0;
        try { a = getGameAction(k); } catch (Throwable e) {}
        int n = places.size();
        if (a == UP && selected > 0) selected--;
        else if (a == DOWN && selected < n - 1) selected++;
        else if (a == FIRE && !repeat) { activate(); return; }
        repaint();
    }

    void activate() {
        String q = query.trim();
        if (q.length() > 0 && !q.equals(lastSearched)) { cancelTimer(); searchNow(); }
        else if (selected >= 0) pick(selected);
    }

    void openEditor() {
        editor = new TextBox(getTitle(), query, 64, TextField.ANY);
        editor.addCommand(TB_OK);
        editor.addCommand(TB_BACK);
        editor.setCommandListener(this);
        app.display.setCurrent(editor);
    }

    public void commandAction(Command c, Displayable d) {
        if (d == editor) {
            if (c == TB_OK) { query = editor.getString(); app.display.setCurrent(this); edited(); cancelTimer(); searchNow(); }
            else app.display.setCurrent(this);
            return;
        }
        if (c == PICK) activate();
        else if (c == EDIT) openEditor();
        else if (c == SEARCH) { cancelTimer(); lastSearched = null; searchNow(); }
        else close();
    }

    // ------------------------------------------------------------ painting

    protected void paint(Graphics g) {
        Font f = MapCanvas.small(), b = MapCanvas.bold();
        int w = getWidth(), h = getHeight(), fh = f.getHeight();
        g.setColor(0x1E1F22);
        g.fillRect(0, 0, w, h);
        // field
        int fy = 3, fhh = b.getHeight() + 6;
        g.setColor(0xFFFFFF);
        g.fillRect(3, fy, w - 6, fhh);
        g.setColor(0x1565C0);
        g.drawRect(3, fy, w - 7, fhh - 1);
        g.setFont(b);
        g.setColor(0x000000);
        String shown = "Search: " + query + "_";
        g.drawString(MapCanvas.clip(b, shown, w - 14), 7, fy + 3, Graphics.TOP | Graphics.LEFT);
        int y = fy + fhh + 2;
        g.setFont(f);
        g.setColor(0xFCEE74);
        g.drawString(MapCanvas.clip(f, status, w - 8), 4, y, Graphics.TOP | Graphics.LEFT);
        y += fh + 2;
        int rh = b.getHeight() + 4, rows = Math.max(1, (h - y) / rh);
        Vector v = places;
        if (selected >= 0) {
            if (selected < scroll) scroll = selected;
            if (selected >= scroll + rows) scroll = selected - rows + 1;
        }
        for (int r = 0; r < rows && scroll + r < v.size(); r++) {
            int i = scroll + r;
            Place p = (Place) v.elementAt(i);
            int ry = y + r * rh;
            if (i == selected) { g.setColor(0x1565C0); g.fillRect(2, ry, w - 4, rh - 1); }
            g.setFont(b);
            g.setColor(GPS.equals(p.source) ? 0x8AB4F8 : 0xFFFFFF);
            int tw = b.stringWidth(p.title);
            g.drawString(MapCanvas.clip(b, p.title, w / 2), 6, ry + 2, Graphics.TOP | Graphics.LEFT);
            if (p.subtitle.length() > 0) {
                g.setFont(f);
                g.setColor(i == selected ? 0xDBDEE1 : 0x80848E);
                int sx = 6 + Math.min(tw, w / 2) + 10;
                g.drawString(MapCanvas.clip(f, p.subtitle, w - sx - 6), sx, ry + 2 + (b.getHeight() - fh), Graphics.TOP | Graphics.LEFT);
            }
        }
    }
}
