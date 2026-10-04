package mapy;

import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * The map: OSM tiles, the POI overlay and the search marker, with a crosshair in the middle.
 *
 * Loading (one background worker, requests through Net = one at a time): first the missing
 * visible tiles, nearest to the centre first; then, at zoom >= POI_ZOOM, the POIs of the view
 * (one Overpass query per area). Only the status strip or the arriving tile is repainted while
 * loading (a full-screen repaint loop starved the TLS patch on the 9300).
 *
 * Keys: arrows pan, Enter/fire = select the nearest object or open its detail, + / - (or 3 / 1) zoom,
 * N = next object, letters start a search.
 */
public class MapCanvas extends Canvas implements CommandListener, Runnable, Net.Listener {
    static final int T = Geo.TILE, CACHE_MAX = 24, POI_ZOOM = 16, PAN = 64, PICK_RADIUS = 28;

    static final Command SEARCH = new Command("Hledat", Command.SCREEN, 1);
    static final Command SELECT = new Command("Vybrat / detail", Command.SCREEN, 2);
    static final Command ZOOM_IN = new Command("Přiblížit", Command.SCREEN, 3);
    static final Command ZOOM_OUT = new Command("Oddálit", Command.SCREEN, 4);
    static final Command NEXT = new Command("Další bod", Command.SCREEN, 5);
    static final Command HERE = new Command("Co je tady", Command.SCREEN, 6);
    static final Command POIS = new Command("Body zájmu zap/vyp", Command.SCREEN, 7);
    static final Command RELOAD = new Command("Načíst znovu", Command.SCREEN, 8);
    static final Command LOG = new Command("Log", Command.SCREEN, 9);
    static final Command SETTINGS = new Command("Nastavení", Command.SCREEN, 10);
    static final Command EXIT = new Command("Konec", Command.EXIT, 11);

    final Mapy app;
    int zoom;
    double cx, cy;                                  // centre, world pixels at zoom
    final Hashtable tiles = new Hashtable();        // "z/x/y" -> Image
    final Vector tileOrder = new Vector();
    final Hashtable failed = new Hashtable();       // "z/x/y" -> Boolean, not retried until reload

    Vector pois = new Vector();                     // Place (osm)
    double[] poiBox;                                // s, w, n, e of the last POI query
    boolean poiFailed;
    Place marker;                                   // search result / detail position
    Place selected;
    int nextIndex;

    volatile String status = "";
    volatile boolean workerWaiting;
    final Object wake = new Object();
    Thread worker;

    MapCanvas(Mapy app) {
        this.app = app;
        zoom = Settings.zoom;
        center(Settings.lat, Settings.lon);
        addCommand(SEARCH); addCommand(SELECT); addCommand(ZOOM_IN); addCommand(ZOOM_OUT);
        addCommand(NEXT); addCommand(HERE); addCommand(POIS); addCommand(RELOAD);
        addCommand(LOG); addCommand(SETTINGS); addCommand(EXIT);
        setCommandListener(this);
        Net.listener = this;
        worker = new Thread(this);
        worker.start();
    }

    // ---------------------------------------------------------------- view

    void center(double lat, double lon) {
        cx = Geo.lonToX(lon, zoom);
        cy = Geo.latToY(lat, zoom);
    }

    double centerLat() { return Geo.yToLat(cy, zoom); }
    double centerLon() { return Geo.xToLon(cx, zoom); }

    /** west, south, east, north of the visible area (lon/lat). */
    double[] viewBox(double grow) {
        int w = getWidth(), h = getHeight();
        double gx = w * grow / 2, gy = h * grow / 2;
        return new double[] {
            Geo.xToLon(cx - w / 2 - gx, zoom), Geo.yToLat(cy + h / 2 + gy, zoom),
            Geo.xToLon(cx + w / 2 + gx, zoom), Geo.yToLat(cy - h / 2 - gy, zoom) };
    }

    void setZoom(int z) {
        if (z < 3 || z > 18 || z == zoom) return;
        double lat = centerLat(), lon = centerLon();
        zoom = z;
        center(lat, lon);
        viewChanged();
    }

    /** Moves the map to a place and marks it. */
    public void show(Place p, boolean asMarker) {
        if (asMarker) marker = p;
        selected = p;
        if (p.zoom > 0 && (p.zoom > zoom || zoom < 13)) zoom = Math.max(13, Math.min(17, p.zoom));
        center(p.lat, p.lon);
        viewChanged();
    }

    void viewChanged() {
        Settings.lat = centerLat();
        Settings.lon = centerLon();
        Settings.zoom = zoom;
        repaint();
        synchronized (wake) { wake.notify(); }
    }

    // ---------------------------------------------------------------- worker

    public void run() {
        while (true) {
            try {
                if (!loadNextTile() && !loadPois()) {
                    synchronized (wake) {
                        status = "";
                        repaintStrip();
                        try { wake.wait(); } catch (InterruptedException e) {}
                    }
                }
            } catch (Throwable e) {
                Log.add("worker: " + e);
                try { Thread.sleep(1000); } catch (InterruptedException ie) {}
            }
        }
    }

    static String key(int z, int x, int y) { return z + "/" + x + "/" + y; }

    /** Loads one missing visible tile, nearest to the centre first. False when none is missing. */
    boolean loadNextTile() {
        int w = getWidth(), h = getHeight(), z = zoom;
        double ccx = cx, ccy = cy;
        int x0 = (int) Math.floor((ccx - w / 2) / T), x1 = (int) Math.floor((ccx + w / 2) / T);
        int y0 = (int) Math.floor((ccy - h / 2) / T), y1 = (int) Math.floor((ccy + h / 2) / T);
        int max = 1 << z, bx = 0, by = 0, missing = 0;
        double best = Double.MAX_VALUE;
        for (int y = y0; y <= y1; y++) {
            if (y < 0 || y >= max) continue;
            for (int x = x0; x <= x1; x++) {
                String k = key(z, x & (max - 1), y);
                if (tiles.containsKey(k) || failed.containsKey(k)) continue;
                missing++;
                double dx = x * T + T / 2 - ccx, dy = y * T + T / 2 - ccy, d = dx * dx + dy * dy;
                if (d < best) { best = d; bx = x; by = y; }
            }
        }
        if (missing == 0) return false;
        int tx = bx & (max - 1);
        String k = key(z, tx, by);
        status = "mapa: " + missing + (missing == 1 ? " dlaždice" : missing < 5 ? " dlaždice" : " dlaždic");
        repaintStrip();
        try {
            Net.Response r = Net.get("https://tile.openstreetmap.org/" + z + "/" + tx + "/" + by + ".png", "dlaždice " + k);
            if (r.code != 200) {
                Log.add("tile " + k + ": HTTP " + r.code);
                failed.put(k, Boolean.TRUE);
            } else {
                Image im = Image.createImage(r.body, 0, r.body.length);
                synchronized (tiles) {
                    tiles.put(k, im);
                    tileOrder.addElement(k);
                    while (tileOrder.size() > CACHE_MAX) {
                        tiles.remove(tileOrder.elementAt(0));
                        tileOrder.removeElementAt(0);
                    }
                }
                if (z == zoom) repaintTile(bx, by);
            }
        } catch (OutOfMemoryError e) {
            synchronized (tiles) {
                while (tileOrder.size() > 6) { tiles.remove(tileOrder.elementAt(0)); tileOrder.removeElementAt(0); }
            }
            System.gc();
            Log.add("tile " + k + ": out of memory, cache trimmed");
        } catch (Throwable e) {
            Log.add("tile " + k + ": " + e);
            failed.put(k, Boolean.TRUE);
            status = "chyba mapy: " + e.getMessage();
        }
        return true;
    }

    /** Loads the POIs for the view when needed. False when there's nothing to do. */
    boolean loadPois() {
        if (!Settings.pois || zoom < POI_ZOOM || poiFailed) return false;
        double[] v = viewBox(0);
        if (poiBox != null && v[1] >= poiBox[0] && v[0] >= poiBox[1] && v[3] <= poiBox[2] && v[2] <= poiBox[3]) return false;
        double[] g = viewBox(1.0);      // twice the view, so small pans don't need a new query
        status = "body zájmu...";
        repaintStrip();
        try {
            Vector found = Overpass.pois(g[1], g[0], g[3], g[2]);
            pois = found;
            poiBox = new double[] { g[1], g[0], g[3], g[2] };
            nextIndex = 0;
            Log.add("POIs: " + found.size());
            repaint();
        } catch (Throwable e) {
            Log.add("POIs: " + e);
            poiFailed = true;           // until "Načíst znovu", don't keep hammering Overpass
            status = "body zájmu: chyba " + e.getMessage();
        }
        return true;
    }

    // ---------------------------------------------------------------- input

    public void commandAction(Command c, Displayable d) {
        if (c == SEARCH) app.search("");
        else if (c == SELECT) select();
        else if (c == ZOOM_IN) setZoom(zoom + 1);
        else if (c == ZOOM_OUT) setZoom(zoom - 1);
        else if (c == NEXT) next();
        else if (c == HERE) app.whatsHere(centerLon(), centerLat(), zoom);
        else if (c == POIS) {
            Settings.pois = !Settings.pois;
            Settings.save();
            if (!Settings.pois) { pois = new Vector(); poiBox = null; if (selected != null && selected.osm) selected = null; }
            status = Settings.pois ? (zoom < POI_ZOOM ? "body zájmu od přiblížení " + POI_ZOOM : "body zájmu zapnuty") : "body zájmu vypnuty";
            viewChanged();
        }
        else if (c == RELOAD) {
            failed.clear();
            poiFailed = false;
            poiBox = null;
            viewChanged();
        }
        else if (c == LOG) app.showLog();
        else if (c == SETTINGS) app.settings();
        else if (c == EXIT) app.exit();
    }

    protected void keyPressed(int key) { key(key, false); }
    protected void keyRepeated(int key) { key(key, true); }

    void key(int key, boolean repeat) {
        int a = 0;
        try { a = getGameAction(key); } catch (Throwable e) {}
        if (key == '+' || key == '3' || key == '=') { if (!repeat) setZoom(zoom + 1); return; }
        if (key == '-' || key == '1') { if (!repeat) setZoom(zoom - 1); return; }
        if (key == 'n' || key == 'N' || key == ' ') { if (!repeat) next(); return; }
        if (key == 10 || key == 13 || a == FIRE) { if (!repeat) select(); return; }
        if (a == LEFT) cx -= PAN; else if (a == RIGHT) cx += PAN;
        else if (a == UP) cy -= PAN; else if (a == DOWN) cy += PAN;
        else if (key > 32 && key < 0x10000 && !repeat && Character.isDigit((char) key) == false) {
            app.search(String.valueOf((char) key));     // typing starts a search
            return;
        }
        else return;
        int max = T << zoom;
        if (cy < 0) cy = 0;
        if (cy > max) cy = max;
        viewChanged();
    }

    /** Selects the object nearest to the crosshair, or opens the detail of the selected one. */
    void select() {
        Place near = nearest();
        if (selected != null && (near == null || near == selected) && onScreen(selected)) {
            app.detail(selected);
            return;
        }
        if (near != null) {
            selected = near;
            repaint();
        } else {
            app.whatsHere(centerLon(), centerLat(), zoom);
        }
    }

    Place nearest() {
        Place best = null;
        double bd = PICK_RADIUS * PICK_RADIUS;
        Vector all = objects();
        for (int i = 0; i < all.size(); i++) {
            Place p = (Place) all.elementAt(i);
            double dx = Geo.lonToX(p.lon, zoom) - cx, dy = Geo.latToY(p.lat, zoom) - cy, d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Cycles through the objects on screen, nearest to the centre first. */
    void next() {
        Vector all = objects(), vis = new Vector();
        for (int i = 0; i < all.size(); i++) if (onScreen((Place) all.elementAt(i))) vis.addElement(all.elementAt(i));
        if (vis.size() == 0) { status = zoom < POI_ZOOM ? "přibliž na " + POI_ZOOM + " pro body zájmu" : "žádné body"; repaintStrip(); return; }
        // sort by distance from centre (small list: selection sort)
        for (int i = 0; i < vis.size(); i++) {
            int m = i;
            for (int j = i + 1; j < vis.size(); j++) if (dist2((Place) vis.elementAt(j)) < dist2((Place) vis.elementAt(m))) m = j;
            Object t = vis.elementAt(i); vis.setElementAt(vis.elementAt(m), i); vis.setElementAt(t, m);
        }
        if (nextIndex >= vis.size()) nextIndex = 0;
        selected = (Place) vis.elementAt(nextIndex++);
        repaint();
    }

    double dist2(Place p) {
        double dx = Geo.lonToX(p.lon, zoom) - cx, dy = Geo.latToY(p.lat, zoom) - cy;
        return dx * dx + dy * dy;
    }

    Vector objects() {
        Vector v = new Vector();
        Vector ps = pois;
        for (int i = 0; i < ps.size(); i++) v.addElement(ps.elementAt(i));
        if (marker != null) v.addElement(marker);
        return v;
    }

    boolean onScreen(Place p) {
        double x = Geo.lonToX(p.lon, zoom) - cx, y = Geo.latToY(p.lat, zoom) - cy;
        return Math.abs(x) < getWidth() / 2 && Math.abs(y) < getHeight() / 2;
    }

    // ---------------------------------------------------------------- painting

    public void progress() { repaintStrip(); }

    Font small() { return Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL); }

    int stripHeight() { return 2 * small().getHeight() + 3; }

    void repaintStrip() {
        int h = getHeight();
        repaint(0, h - stripHeight(), getWidth(), stripHeight());
    }

    void repaintTile(int x, int y) {
        int w = getWidth(), h = getHeight();
        repaint(x * T - ((int) cx - w / 2), y * T - ((int) cy - h / 2), T, T);
    }

    protected void paint(Graphics g) {
        int w = getWidth(), h = getHeight(), sh = stripHeight();
        int clipY = g.getClipY();
        if (clipY < h - sh) paintMap(g, w, h);
        paintStrip(g, w, h, sh);
    }

    void paintMap(Graphics g, int w, int h) {
        int ox = (int) cx - w / 2, oy = (int) cy - h / 2;
        int x0 = (int) Math.floor((double) ox / T), x1 = (int) Math.floor((double) (ox + w) / T);
        int y0 = (int) Math.floor((double) oy / T), y1 = (int) Math.floor((double) (oy + h) / T);
        int max = 1 << zoom;
        g.setColor(0xE5E3DF);
        g.fillRect(0, 0, w, h);
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                int px = x * T - ox, py = y * T - oy;
                Image im = (y >= 0 && y < max) ? (Image) tiles.get(key(zoom, x & (max - 1), y)) : null;
                if (im != null) g.drawImage(im, px, py, Graphics.TOP | Graphics.LEFT);
                else { g.setColor(0xCFCCC6); g.drawRect(px, py, T - 1, T - 1); }
            }
        }
        // POIs
        Vector ps = pois;
        for (int i = 0; i < ps.size(); i++) {
            Place p = (Place) ps.elementAt(i);
            if (p == selected) continue;
            int px = (int) (Geo.lonToX(p.lon, zoom) - ox), py = (int) (Geo.latToY(p.lat, zoom) - oy);
            if (px < -6 || py < -6 || px > w + 6 || py > h + 6) continue;
            dot(g, px, py, 4, Kinds.color(p));
        }
        if (marker != null && marker != selected) pin(g, (int) (Geo.lonToX(marker.lon, zoom) - ox), (int) (Geo.latToY(marker.lat, zoom) - oy), 0xD32F2F);
        if (selected != null) {
            int px = (int) (Geo.lonToX(selected.lon, zoom) - ox), py = (int) (Geo.latToY(selected.lat, zoom) - oy);
            if (selected.osm) dot(g, px, py, 7, Kinds.color(selected));
            else pin(g, px, py, 0xD32F2F);
            label(g, selected.title, px, py - (selected.osm ? 10 : 22), w);
        }
        // crosshair
        g.setColor(0x202020);
        g.drawLine(w / 2 - 8, h / 2, w / 2 - 3, h / 2); g.drawLine(w / 2 + 3, h / 2, w / 2 + 8, h / 2);
        g.drawLine(w / 2, h / 2 - 8, w / 2, h / 2 - 3); g.drawLine(w / 2, h / 2 + 3, w / 2, h / 2 + 8);
    }

    static void dot(Graphics g, int x, int y, int r, int color) {
        g.setColor(0xFFFFFF);
        g.fillArc(x - r - 1, y - r - 1, 2 * r + 2, 2 * r + 2, 0, 360);
        g.setColor(color);
        g.fillArc(x - r, y - r, 2 * r, 2 * r, 0, 360);
    }

    static void pin(Graphics g, int x, int y, int color) {
        g.setColor(0xFFFFFF);
        g.fillArc(x - 7, y - 21, 14, 14, 0, 360);
        g.fillTriangle(x - 6, y - 12, x + 6, y - 12, x, y + 1);
        g.setColor(color);
        g.fillArc(x - 6, y - 20, 12, 12, 0, 360);
        g.fillTriangle(x - 5, y - 12, x + 5, y - 12, x, y - 1);
        g.setColor(0xFFFFFF);
        g.fillArc(x - 2, y - 16, 4, 4, 0, 360);
    }

    void label(Graphics g, String s, int x, int y, int w) {
        Font f = small();
        g.setFont(f);
        if (s.length() > 40) s = s.substring(0, 39) + "...";
        int tw = f.stringWidth(s) + 6, fh = f.getHeight();
        int lx = Math.max(0, Math.min(w - tw, x - tw / 2)), ly = Math.max(0, y - fh);
        g.setColor(0xFFFFFF);
        g.fillRect(lx, ly, tw, fh + 1);
        g.setColor(0x606060);
        g.drawRect(lx, ly, tw, fh + 1);
        g.setColor(0x000000);
        g.drawString(s, lx + 3, ly + 1, Graphics.TOP | Graphics.LEFT);
    }

    void paintStrip(Graphics g, int w, int h, int sh) {
        Font f = small();
        int fh = f.getHeight();
        g.setFont(f);
        g.setColor(0x1E1F22);
        g.fillRect(0, h - sh, w, sh);
        String line1;
        String prog = Net.progressText();
        if (prog.length() > 0) line1 = prog;
        else if (status.length() > 0) line1 = status;
        else if (selected != null) line1 = selected.title + (selected.subtitle.length() > 0 ? " - " + selected.subtitle : "") + "   [Enter = detail]";
        else line1 = Settings.pois && zoom < POI_ZOOM ? "Přibliž (+) pro body zájmu, Enter = co je tady" : "Enter = vybrat bod / co je tady, N = další bod, písmena = hledat";
        g.setColor(prog.length() > 0 ? 0xFCEE74 : 0xFFFFFF);
        g.drawString(line1.length() > 110 ? line1.substring(0, 110) : line1, 3, h - sh + 1, Graphics.TOP | Graphics.LEFT);
        g.setColor(0xB5BAC1);
        g.drawString("z" + zoom + "   © OpenStreetMap contributors", 3, h - fh - 1, Graphics.TOP | Graphics.LEFT);
    }
}
