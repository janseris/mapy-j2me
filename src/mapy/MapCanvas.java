package mapy;

import java.util.Hashtable;
import java.util.Vector;
import javax.microedition.lcdui.*;

/**
 * The map screen, full screen: an info panel on the left (progress, status, the object under the
 * cursor, zoom, credits) and the map on the right: OSM tiles, the POI overlay, the search marker
 * and a mouse cursor.
 *
 * Cursor: the arrows (the 9300's navigation key) move it smoothly while held, diagonally when two
 * directions are held; at the edge of the map the map scrolls. Objects under the cursor are
 * highlighted like links; Enter clicks.
 *
 * Loading (one background worker, requests through Net = one at a time): first the missing
 * visible tiles, nearest to the centre first; then, at zoom >= POI_ZOOM, the POIs of the view.
 * While loading only the panel or the arriving tile is repainted (a full-screen repaint loop
 * starved the TLS patch on the 9300).
 */
public class MapCanvas extends Canvas implements CommandListener, Runnable, Net.Listener {
    static final int T = Geo.TILE, CACHE_MAX = 24, POI_ZOOM = 16, BAR = 26, EDGE = 12, HOVER_R = 12;

    // the first four go on the 9300's side buttons, top to bottom
    static final Command SEARCH = new Command("Hledat", Command.SCREEN, 1);
    static final Command ZOOM_IN = new Command("Přiblížit", Command.SCREEN, 2);
    static final Command ZOOM_OUT = new Command("Oddálit", Command.SCREEN, 3);
    static final Command OPEN = new Command("Otevřít", Command.SCREEN, 4);
    static final Command NEXT = new Command("Další bod", Command.SCREEN, 5);
    static final Command HERE = new Command("Co je tady", Command.SCREEN, 6);
    static final Command POIS = new Command("Body zájmu zap/vyp", Command.SCREEN, 7);
    static final Command FULL = new Command("Celá obrazovka zap/vyp", Command.SCREEN, 8);
    static final Command RELOAD = new Command("Načíst znovu", Command.SCREEN, 9);
    static final Command LOG = new Command("Log", Command.SCREEN, 10);
    static final Command SETTINGS = new Command("Nastavení", Command.SCREEN, 11);
    // SCREEN, not EXIT: the 9300 always puts an EXIT command on the bottom side button
    static final Command EXIT = new Command("Konec", Command.SCREEN, 12);

    final Mapy app;
    int zoom;
    double cx, cy;                                  // map centre, world pixels at zoom
    final Hashtable tiles = new Hashtable();        // "z/x/y" -> Image
    final Vector tileOrder = new Vector();
    final Hashtable failed = new Hashtable();       // "z/x/y" -> Boolean, not retried until reload

    Vector pois = new Vector();                     // Place (osm)
    double[] poiBox;                                // s, w, n, e of the last POI query
    boolean poiFailed;
    Place marker;                                   // search result / detail position
    Place selected;
    Place hovered;
    int nextIndex;

    volatile String status = "";
    String lastKey = "";
    final Object wake = new Object();
    boolean fullScreen = true;

    MapCanvas(Mapy app) {
        this.app = app;
        zoom = Settings.zoom;
        try { setFullScreenMode(true); } catch (Throwable e) { fullScreen = false; }
        center(Settings.lat, Settings.lon);
        addCommand(SEARCH); addCommand(ZOOM_IN); addCommand(ZOOM_OUT); addCommand(OPEN);
        addCommand(NEXT); addCommand(HERE); addCommand(POIS); addCommand(FULL); addCommand(RELOAD);
        addCommand(LOG); addCommand(SETTINGS); addCommand(EXIT);
        setCommandListener(this);
        Net.listener = this;
        new Thread(this).start();
    }

    // ---------------------------------------------------------------- geometry

    /** Left panel width (configurable), the map, then the icon bar for the side buttons. */
    int panel() { return Settings.panelWidth; }
    int mw() { return Math.max(1, getWidth() - panel() - BAR); }
    int mh() { return getHeight(); }

    void center(double lat, double lon) {
        cx = Geo.lonToX(lon, zoom);
        cy = Geo.latToY(lat, zoom);
    }

    double centerLat() { return Geo.yToLat(cy, zoom); }
    double centerLon() { return Geo.xToLon(cx, zoom); }

    /** World pixel of map-area pixel x, y. */
    double wx(int x) { return cx - mw() / 2 + x; }
    double wy(int y) { return cy - mh() / 2 + y; }
    int sx(Place p) { return (int) (Geo.lonToX(p.lon, zoom) - (cx - mw() / 2)); }
    int sy(Place p) { return (int) (Geo.latToY(p.lat, zoom) - (cy - mh() / 2)); }

    /** west, south, east, north of the visible map (lon/lat), grown by a fraction. */
    double[] viewBox(double grow) {
        int w = mw(), h = mh();
        double gx = w * grow / 2, gy = h * grow / 2;
        return new double[] {
            Geo.xToLon(cx - w / 2 - gx, zoom), Geo.yToLat(cy + h / 2 + gy, zoom),
            Geo.xToLon(cx + w / 2 + gx, zoom), Geo.yToLat(cy - h / 2 - gy, zoom) };
    }

    /** Zooms keeping the map point under the cursor where it is. */
    void setZoom(int z) {
        if (z < 3 || z > 18 || z == zoom) return;
        initCursor();
        double lon = Geo.xToLon(wx(mx), zoom), lat = Geo.yToLat(wy(my), zoom);
        zoom = z;
        cx = Geo.lonToX(lon, zoom) - mx + mw() / 2;
        cy = Geo.latToY(lat, zoom) - my + mh() / 2;
        hovered = null;
        viewChanged();
    }

    /** Moves the map to a place, puts the cursor on it, optionally marks it. */
    public void show(Place p, boolean asMarker) {
        if (asMarker) marker = p;
        selected = p;
        if (p.zoom > 0 && (p.zoom > zoom || zoom < 13)) zoom = Math.max(13, Math.min(17, p.zoom));
        center(p.lat, p.lon);
        mx = mw() / 2;
        my = mh() / 2 - (p.osm ? 0 : 12);
        hovered = p;
        viewChanged();
    }

    void viewChanged() {
        Settings.lat = centerLat();
        Settings.lon = centerLon();
        Settings.zoom = zoom;
        repaint();
        synchronized (wake) { wake.notify(); }
    }

    // ---------------------------------------------------------------- loading

    public void run() {
        while (true) {
            try {
                if (!loadNextTile() && !loadPois()) {
                    synchronized (wake) {
                        status = "";
                        repaintPanel();
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
        int w = mw(), h = mh(), z = zoom;
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
        status = "Mapa: zbývá " + missing;
        repaintPanel();
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
            status = "Chyba mapy: " + e.getMessage();
        }
        return true;
    }

    /** Loads the POIs for the view when needed. False when there's nothing to do. */
    boolean loadPois() {
        if (!Settings.pois || zoom < POI_ZOOM || poiFailed) return false;
        double[] v = viewBox(0);
        if (poiBox != null && v[1] >= poiBox[0] && v[0] >= poiBox[1] && v[3] <= poiBox[2] && v[2] <= poiBox[3]) return false;
        double[] g = viewBox(1.0);      // twice the view, so small moves don't need a new query
        status = "Body zájmu...";
        repaintPanel();
        try {
            Vector found = Overpass.pois(g[1], g[0], g[3], g[2]);
            pois = found;
            poiBox = new double[] { g[1], g[0], g[3], g[2] };
            nextIndex = 0;
            Log.add("POIs: " + found.size());
            hovered = objectAt(mx, my, HOVER_R);
            repaint();
        } catch (Throwable e) {
            Log.add("POIs: " + e);
            poiFailed = true;           // until "Načíst znovu", don't keep hammering Overpass
            status = "Body zájmu: chyba " + e.getMessage();
        }
        return true;
    }

    // ---------------------------------------------------------------- commands

    public void commandAction(Command c, Displayable d) {
        if (c == SEARCH) app.search("");
        else if (c == OPEN) click();
        else if (c == ZOOM_IN) setZoom(zoom + 1);
        else if (c == ZOOM_OUT) setZoom(zoom - 1);
        else if (c == NEXT) next();
        else if (c == HERE) whatsHereAtCursor();
        else if (c == POIS) {
            Settings.pois = !Settings.pois;
            Settings.save();
            if (!Settings.pois) { pois = new Vector(); poiBox = null; hovered = null; }
            status = Settings.pois ? (zoom < POI_ZOOM ? "Body zájmu od přiblížení " + POI_ZOOM : "Body zájmu zapnuty") : "Body zájmu vypnuty";
            viewChanged();
        }
        else if (c == FULL) toggleFullScreen();
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

    void toggleFullScreen() {
        fullScreen = !fullScreen;
        try { setFullScreenMode(fullScreen); } catch (Throwable e) {}
        repaint();
    }

    protected void sizeChanged(int w, int h) {
        if (mx > mw() - EDGE) mx = mw() - EDGE;
        if (my > mh() - EDGE) my = mh() - EDGE;
        repaint();
        synchronized (wake) { wake.notify(); }
    }

    // ---------------------------------------------------------------- keys and the cursor

    int mx = -1, my = -1;                     // cursor, map-area pixels
    volatile boolean kLeft, kRight, kUp, kDown;
    Thread mover;

    void initCursor() {
        if (mx < 0) { mx = mw() / 2; my = mh() / 2; }
    }

    protected void keyPressed(int key) { key(key, true, false); }
    protected void keyRepeated(int key) { key(key, true, true); }
    protected void keyReleased(int key) { key(key, false, false); }

    void key(int key, boolean down, boolean repeat) {
        initCursor();
        int a = 0;
        try { a = getGameAction(key); } catch (Throwable e) {}
        if (down && !repeat) {
            String name = "";
            try { name = getKeyName(key); } catch (Throwable e) {}
            lastKey = "klávesa " + key + (name != null && name.length() > 0 ? " " + name : "") + (a != 0 ? " (akce " + a + ")" : "");
            Log.add("key " + key + " '" + name + "' game action " + a);
        }
        boolean dir = a == LEFT || a == RIGHT || a == UP || a == DOWN;
        if (dir) {
            if (a == LEFT) kLeft = down;
            if (a == RIGHT) kRight = down;
            if (a == UP) kUp = down;
            if (a == DOWN) kDown = down;
            if (down && !repeat) {
                moveCursor((a == RIGHT ? 3 : a == LEFT ? -3 : 0), (a == DOWN ? 3 : a == UP ? -3 : 0));   // react at once
                startMover();
            }
            return;
        }
        if (!down) return;
        if (repeat) return;
        if (key == '+' || key == '=' || key == '3') { setZoom(zoom + 1); return; }
        if (key == '-' || key == '1') { setZoom(zoom - 1); return; }
        if (key == '0') { toggleFullScreen(); return; }
        if (key == 'n' || key == 'N' || key == ' ') { next(); return; }
        if (key == 10 || key == 13 || a == FIRE) { click(); return; }
        if (key > 32 && key < 0x10000 && !Character.isDigit((char) key)) {
            app.search(String.valueOf((char) key));     // typing starts a search
            return;
        }
        repaintPanel();                                  // show the unknown key code
    }

    // cursor speed in pixels per second: starts slow for precise pointing, accelerates while held
    static final double V0 = 70, VMAX = 420, ACCEL = 700;

    void startMover() {
        if (mover != null && mover.isAlive()) return;
        mover = new Thread() {
            public void run() {
                double v = V0, fx = 0, fy = 0;
                long last = System.currentTimeMillis(), started = last;
                // moves while a direction is held (keyReleased stops it); time-based, so uneven
                // timer ticks (62 ms resolution on the 9300) don't make it jerky
                while ((kLeft || kRight || kUp || kDown) && System.currentTimeMillis() - started < 30000) {
                    try { Thread.sleep(25); } catch (InterruptedException e) {}
                    long now = System.currentTimeMillis();
                    double dt = (now - last) / 1000.0;
                    last = now;
                    if (dt <= 0) continue;
                    v = Math.min(VMAX, v + ACCEL * dt);
                    int hx = (kRight ? 1 : 0) - (kLeft ? 1 : 0), hy = (kDown ? 1 : 0) - (kUp ? 1 : 0);
                    double d = v * dt * (hx != 0 && hy != 0 ? 0.7071 : 1.0);
                    fx += hx * d;
                    fy += hy * d;
                    int ix = (int) fx, iy = (int) fy;
                    if (ix != 0 || iy != 0) {
                        fx -= ix;
                        fy -= iy;
                        moveCursor(ix, iy);
                    }
                }
                kLeft = kRight = kUp = kDown = false;
            }
        };
        mover.start();
    }

    /** Moves the cursor; past the edge the map scrolls instead. */
    synchronized void moveCursor(int dx, int dy) {
        int w = mw(), h = mh();
        int ox = mx, oy = my;
        int nx = mx + dx, ny = my + dy;
        boolean panned = false;
        if (nx < EDGE) { cx += nx - EDGE; nx = EDGE; panned = true; }
        if (nx > w - EDGE) { cx += nx - (w - EDGE); nx = w - EDGE; panned = true; }
        if (ny < EDGE) { cy += ny - EDGE; ny = EDGE; panned = true; }
        if (ny > h - EDGE) { cy += ny - (h - EDGE); ny = h - EDGE; panned = true; }
        int max = T << zoom;
        if (cy < 0) cy = 0;
        if (cy > max) cy = max;
        mx = nx; my = ny;
        Place before = hovered;
        hovered = objectAt(mx, my, HOVER_R);
        if (panned) {
            viewChanged();
        } else if (hovered != before) {
            repaint();                          // the hover highlight and the panel change
        } else {
            repaintCursor(ox, oy);
            repaintCursor(mx, my);
        }
    }

    void repaintCursor(int x, int y) {
        repaint(panel() + x - 2, y - 2, 16, 22);
    }

    /** Click: open the hovered object, or "what's here" at the cursor. */
    void click() {
        initCursor();
        Place p = objectAt(mx, my, HOVER_R);
        if (p != null) {
            selected = p;
            repaint();
            app.detail(p);
        } else {
            whatsHereAtCursor();
        }
    }

    void whatsHereAtCursor() {
        initCursor();
        app.whatsHere(Geo.xToLon(wx(mx), zoom), Geo.yToLat(wy(my), zoom), zoom);
    }

    // a real pointer, when the device has one (KEmulator; the 9300's Java reports none)
    protected void pointerMoved(int x, int y) { initCursor(); moveCursor(x - panel() - mx, y - my); }
    protected void pointerDragged(int x, int y) { pointerMoved(x, y); }
    protected void pointerPressed(int x, int y) {
        if (x >= getWidth() - BAR) { barAction(y * 4 / getHeight()); return; }
        if (x < panel()) return;
        pointerMoved(x, y);
        click();
    }

    /** The object nearest to map-area point x, y within r pixels. */
    Place objectAt(int x, int y, int r) {
        Place best = null;
        double bd = r * r;
        Vector all = objects();
        for (int i = 0; i < all.size(); i++) {
            Place p = (Place) all.elementAt(i);
            double dx = sx(p) - x, dy = sy(p) - (p.osm ? 0 : 12) - y;     // a pin's head is above its point
            double d = dx * dx + dy * dy;
            if (d < bd) { bd = d; best = p; }
        }
        return best;
    }

    /** Moves the cursor to the next object on screen, nearest to the centre first. */
    void next() {
        initCursor();
        Vector all = objects(), vis = new Vector();
        for (int i = 0; i < all.size(); i++) if (onScreen((Place) all.elementAt(i))) vis.addElement(all.elementAt(i));
        if (vis.size() == 0) { status = zoom < POI_ZOOM ? "Přibliž na " + POI_ZOOM + " pro body zájmu" : "Žádné body"; repaintPanel(); return; }
        for (int i = 0; i < vis.size(); i++) {
            int m = i;
            for (int j = i + 1; j < vis.size(); j++) if (dist2((Place) vis.elementAt(j)) < dist2((Place) vis.elementAt(m))) m = j;
            Object t = vis.elementAt(i); vis.setElementAt(vis.elementAt(m), i); vis.setElementAt(t, m);
        }
        if (nextIndex >= vis.size()) nextIndex = 0;
        Place p = (Place) vis.elementAt(nextIndex++);
        mx = sx(p);
        my = sy(p) - (p.osm ? 0 : 12);
        hovered = p;
        repaint();
    }

    double dist2(Place p) {
        double dx = sx(p) - mw() / 2, dy = sy(p) - mh() / 2;
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
        int x = sx(p), y = sy(p);
        return x >= 0 && y >= 0 && x < mw() && y < mh();
    }

    // ---------------------------------------------------------------- painting

    public void progress() { repaintPanel(); }

    static Font small() { return Font.getFont(Font.FACE_SYSTEM, Font.STYLE_PLAIN, Font.SIZE_SMALL); }
    static Font bold() { return Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_SMALL); }

    void repaintPanel() { repaint(0, 0, panel(), getHeight()); }

    void repaintTile(int x, int y) {
        repaint(panel() + x * T - ((int) cx - mw() / 2), y * T - ((int) cy - mh() / 2), T, T);
    }

    protected void paint(Graphics g) {
        int cxp = g.getClipX(), cyp = g.getClipY(), cw = g.getClipWidth(), ch = g.getClipHeight();
        int P = panel(), W = getWidth();
        if (cxp + cw > P && cxp < W - BAR) {
            g.translate(P, 0);
            g.clipRect(0, 0, mw(), mh());
            paintMap(g, mw(), mh());
            g.translate(-P, 0);
            g.setClip(cxp, cyp, cw, ch);
        }
        if (cxp < P) paintPanel(g, P, getHeight());
        if (cxp + cw > W - BAR) paintBar(g, W - BAR, getHeight());
    }

    /**
     * Icon bar on the right, next to the 9300's four side buttons (top to bottom: search, zoom in,
     * zoom out, open). In full screen the phone doesn't draw their labels, so we do.
     */
    void paintBar(Graphics g, int x0, int h) {
        g.setColor(0x1E1F22);
        g.fillRect(x0, 0, BAR, h);
        g.setColor(0x4E5058);
        g.drawLine(x0, 0, x0, h);
        for (int i = 0; i < 4; i++) {
            int cy0 = h * i / 4 + h / 8, cx0 = x0 + BAR / 2;
            if (i > 0) { g.setColor(0x383A40); g.drawLine(x0 + 4, h * i / 4, x0 + BAR - 4, h * i / 4); }
            g.setColor(0xFFFFFF);
            if (i == 0) {                       // magnifier
                g.drawArc(cx0 - 7, cy0 - 7, 10, 10, 0, 360);
                g.drawArc(cx0 - 6, cy0 - 6, 8, 8, 0, 360);
                g.drawLine(cx0 + 1, cy0 + 1, cx0 + 6, cy0 + 6);
                g.drawLine(cx0 + 2, cy0 + 1, cx0 + 6, cy0 + 5);
                g.drawLine(cx0 + 1, cy0 + 2, cx0 + 5, cy0 + 6);
            } else if (i == 1 || i == 2) {      // zoom in / out
                g.drawArc(cx0 - 8, cy0 - 8, 16, 16, 0, 360);
                g.fillRect(cx0 - 4, cy0 - 1, 9, 3);
                if (i == 1) g.fillRect(cx0 - 1, cy0 - 4, 3, 9);
            } else {                            // open: pointer clicking
                cursor(g, cx0 - 4, cy0 - 8);
            }
        }
    }

    void barAction(int i) {
        if (i == 0) app.search("");
        else if (i == 1) setZoom(zoom + 1);
        else if (i == 2) setZoom(zoom - 1);
        else click();
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
        // objects; the hovered one is bigger, ringed and labelled like a link
        Place hov = hovered;
        Vector ps = pois;
        for (int i = 0; i < ps.size(); i++) {
            Place p = (Place) ps.elementAt(i);
            if (p == hov) continue;
            int px = sx(p), py = sy(p);
            if (px < -6 || py < -6 || px > w + 6 || py > h + 6) continue;
            dot(g, px, py, p == selected ? 5 : 4, Kinds.color(p));
        }
        if (marker != null && marker != hov) pin(g, sx(marker), sy(marker), 0xD32F2F);
        if (hov != null) {
            int px = sx(hov), py = sy(hov);
            if (hov.osm) {
                g.setColor(0x1565C0);
                g.fillArc(px - 9, py - 9, 18, 18, 0, 360);
                dot(g, px, py, 6, Kinds.color(hov));
            } else {
                pin(g, px, py, 0xB71C1C);
            }
            label(g, hov.title, px, py - (hov.osm ? 11 : 23), w);
        }
        cursor(g, mx < 0 ? w / 2 : mx, my < 0 ? h / 2 : my);
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

    /** Windows XP style arrow pointer (white with a black outline), tip at x, y. */
    static final int[] CUR_X = { 0, 0, 4, 7, 9, 6, 11 };
    static final int[] CUR_Y = { 0, 16, 12, 18, 17, 11, 11 };

    static void cursor(Graphics g, int x, int y) {
        g.setColor(0xFFFFFF);
        g.fillTriangle(x, y, x, y + 16, x + 11, y + 11);       // arrow head
        g.fillTriangle(x + 4, y + 12, x + 6, y + 11, x + 9, y + 17);   // tail
        g.fillTriangle(x + 4, y + 12, x + 7, y + 18, x + 9, y + 17);
        g.setColor(0x000000);
        for (int i = 0; i < CUR_X.length; i++) {
            int j = (i + 1) % CUR_X.length;
            g.drawLine(x + CUR_X[i], y + CUR_Y[i], x + CUR_X[j], y + CUR_Y[j]);
        }
    }

    void label(Graphics g, String s, int x, int y, int w) {
        Font f = small();
        g.setFont(f);
        if (s.length() > 40) s = s.substring(0, 39) + "...";
        int tw = f.stringWidth(s) + 6, fh = f.getHeight();
        int lx = Math.max(0, Math.min(w - tw, x - tw / 2)), ly = Math.max(0, y - fh);
        g.setColor(0xFFFFFF);
        g.fillRect(lx, ly, tw, fh + 1);
        g.setColor(0x1565C0);
        g.drawRect(lx, ly, tw, fh + 1);
        g.drawString(s, lx + 3, ly + 1, Graphics.TOP | Graphics.LEFT);
        g.drawLine(lx + 3, ly + fh - 1, lx + tw - 4, ly + fh - 1);     // underline, like a link
    }

    /** Left panel: progress / status, the object under the cursor, help, zoom and credits. */
    void paintPanel(Graphics g, int pw, int h) {
        Font f = small(), b = bold();
        int fh = f.getHeight();
        g.setColor(0x1E1F22);
        g.fillRect(0, 0, pw, h);
        g.setColor(0x4E5058);
        g.drawLine(pw - 1, 0, pw - 1, h);
        int y = 2, tw = pw - 6;
        g.setFont(b);
        g.setColor(0xFFFFFF);
        g.drawString("Mapy 9300", 3, y, Graphics.TOP | Graphics.LEFT);
        y += b.getHeight() + 2;
        g.setFont(f);
        String prog = Net.progressText();
        if (prog.length() > 0) {
            g.setColor(0xFCEE74);
            y = wrap(g, f, prog, 3, y, tw, 4);
        } else if (status.length() > 0) {
            g.setColor(0xFCEE74);
            y = wrap(g, f, status, 3, y, tw, 3);
        }
        y += 3;
        Place hov = hovered;
        if (hov != null) {
            g.setFont(b);
            g.setColor(0x8AB4F8);
            y = wrap(g, b, hov.title, 3, y, tw, 3);
            g.setFont(f);
            if (hov.subtitle.length() > 0) {
                g.setColor(0xB5BAC1);
                y = wrap(g, f, hov.subtitle, 3, y, tw, 2);
            }
            g.setColor(0x80848E);
            y = wrap(g, f, "Enter = otevřít", 3, y, tw, 1);
        } else {
            g.setColor(0x80848E);
            String help = Settings.pois && zoom < POI_ZOOM
                ? "Body zájmu od přiblížení " + POI_ZOOM + ". Enter = co je tady."
                : "Najeď kurzorem na bod, Enter = otevřít. N = další bod.";
            y = wrap(g, f, help, 3, y, tw, 4);
        }
        // bottom: last key (for finding Chr+arrow codes), zoom, credits
        int by = h - 3 * fh - 2;
        g.setColor(0x80848E);
        if (lastKey.length() > 0 && by - fh > y) g.drawString(clip(f, lastKey, tw), 3, by - fh, Graphics.TOP | Graphics.LEFT);
        g.setColor(0xB5BAC1);
        g.drawString("Zoom " + zoom, 3, by, Graphics.TOP | Graphics.LEFT);
        g.drawString("© OpenStreetMap", 3, by + fh, Graphics.TOP | Graphics.LEFT);
        g.drawString("contributors", 3, by + 2 * fh, Graphics.TOP | Graphics.LEFT);
    }

    static String clip(Font f, String s, int w) {
        if (f.stringWidth(s) <= w) return s;
        while (s.length() > 1 && f.stringWidth(s + "...") > w) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    /** Draws s wrapped to width w, at most maxLines; returns the y below it. */
    static int wrap(Graphics g, Font f, String s, int x, int y, int w, int maxLines) {
        int lines = 0, fh = f.getHeight();
        while (s.length() > 0 && lines < maxLines) {
            int n = s.length();
            while (n > 1 && f.substringWidth(s, 0, n) > w) {
                int sp = s.lastIndexOf(' ', n - 1);
                n = sp > 0 ? sp : n - 1;
            }
            String line = s.substring(0, n);
            s = s.substring(n).trim();
            if (lines == maxLines - 1 && s.length() > 0) line = clip(f, line + " " + s, w);
            g.drawString(line, x, y, Graphics.TOP | Graphics.LEFT);
            y += fh;
            lines++;
        }
        return y;
    }
}
