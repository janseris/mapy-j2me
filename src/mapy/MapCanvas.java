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
public class MapCanvas extends Canvas implements CommandListener, Runnable, Net.Listener, Gps.Listener {
    static final int T = Geo.TILE, CACHE_MAX = 24, POI_ZOOM = 16, BAR = 26, EDGE = 12, HOVER_R = 12;

    // the first four go on the 9300's side buttons, top to bottom
    static final Command SEARCH = new Command("Hledat", Command.SCREEN, 1);
    static final Command ZOOM_IN = new Command("Přiblížit", Command.SCREEN, 2);
    static final Command ZOOM_OUT = new Command("Oddálit", Command.SCREEN, 3);
    static final Command MENU = new Command("Menu", Command.SCREEN, 4);
    static final Command OPEN = new Command("Otevřít", Command.SCREEN, 4);
    static final Command ROUTE = new Command("Trasa (odkud, kam)", Command.SCREEN, 5);
    static final Command MYPOS = new Command("Moje poloha (GPS)", Command.SCREEN, 5);
    static final Command FOLLOW = new Command("Sledovat polohu zap/vyp", Command.SCREEN, 5);
    static final Command GPS = new Command("GPS připojit/odpojit", Command.SCREEN, 5);
    static final Command NAV = new Command("Navigace start/stop", Command.SCREEN, 5);
    static final Command CLEAR_ROUTE = new Command("Zrušit trasu", Command.SCREEN, 5);
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

    // hover preview (thumbnail + rating from Mapy.com) shown in the panel
    Place previewFor;
    Image previewImage;
    String previewRating = "";

    void setPreview(Place p, Image im, String rating) {
        previewFor = p;
        previewImage = im;
        previewRating = rating;
        if (hovered == p) repaintPanel();
    }

    /** After the cursor rests on an object for a second, ask for its preview. */
    void hoverChanged(final Place p) {
        if (p == null || !Settings.preview || p == previewFor) return;
        new Thread() {
            public void run() {
                try { Thread.sleep(1000); } catch (InterruptedException e) {}
                if (hovered == p && isShown()) app.preview(p);
            }
        }.start();
    }

    volatile String status = "";
    String lastKey = "";
    final Object wake = new Object();
    boolean fullScreen = true;

    MapCanvas(Mapy app) {
        this.app = app;
        zoom = Settings.zoom;
        try { setFullScreenMode(true); } catch (Throwable e) { fullScreen = false; }
        center(Settings.lat, Settings.lon);
        // only the side buttons: the phone's own menu passes its arrow keys to the map, so the
        // rest is in our menu drawn on the map (side button 4), which owns the keys while open
        applyCommands();
        setCommandListener(this);
        Net.listener = this;
        Gps.instance.listener = this;
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
        if (z < 3 || z > Layers.maxZoom() || z == zoom) return;
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

    String parentKey;
    Image parentImage;

    static String key(int z, int x, int y) { return Layers.current() + ":" + z + "/" + x + "/" + y; }

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
            // past the map type's own detail, the tile is cut out of its deepest tile and enlarged
            int nz = Math.min(z, Layers.nativeZoom()), d = z - nz;
            int px = tx >> d, py = by >> d;
            String pk = key(nz, px, py);
            Image src = d > 0 && pk.equals(parentKey) ? parentImage : null;
            if (src == null) {
                String url = Layers.url(nz, px, py);
                byte[] body = DiskCache.get(url);         // phone storage first, then the network
                if (body == null) {
                    Net.Response r = Net.get(url, "dlaždice " + k);
                    if (r.code == 200) DiskCache.put(url, r.body);
                    else Log.add("tile " + pk + ": HTTP " + r.code);
                    body = r.code == 200 ? r.body : null;
                }
                if (body != null) src = Image.createImage(body, 0, body.length);
                if (d > 0) { parentKey = pk; parentImage = src; }
            }
            if (src == null) {
                failed.put(k, Boolean.TRUE);
            } else {
                Image im = src;
                if (d > 0) {
                    int part = T >> d, ox = (tx - (px << d)) * part, oy = (by - (py << d)) * part;
                    im = Photos.scale(Image.createImage(src, ox, oy, part, part, 0), T, T);
                }
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
        if (!internalCommand) undoMenuArrows();
        lastCommand = System.currentTimeMillis();
        Log.add("menu: " + c.getLabel());
        enterDown = false;              // the Enter that picked this menu item is not a map click
        if (c == MENU) { if (menuOpen) closeMenu(); else openMenu(); return; }
        if (menuOpen) closeMenu();
        if (c == SEARCH) app.search("");
        else if (c == ROUTE) app.routeForm();
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
        else if (c == FOLLOW) {
            follow = !follow;
            Settings.follow = follow;
            Settings.save();
            if (follow) {
                cursorHidden = true;
                if (Gps.instance.hasFix()) centerOnGps();
                else if (!Gps.instance.running) Gps.instance.connect();
            }
            status = follow ? "Sledování polohy zapnuto" : "Sledování polohy vypnuto";
            repaint();
        }
        else if (c == GPS) { if (Gps.instance.running) Gps.instance.disconnect(); else Gps.instance.connect(); repaintPanel(); }
        else if (c == MYPOS) {
            if (Gps.instance.hasFix()) { follow = true; cursorHidden = true; centerOnGps(); }
            else {
                status = "GPS: " + Gps.instance.status;
                if (!Gps.instance.running) Gps.instance.connect();
                if (Settings.gpsTime > 0) {         // meanwhile, the last known position
                    center(Settings.gpsLat, Settings.gpsLon);
                    status = "Poslední známá poloha " + age(Settings.gpsTime) + ", čekám na GPS";
                    viewChanged();
                }
                repaintPanel();
            }
        }
        else if (c == NAV) {
            if (route == null) { status = "Nejdřív naplánuj trasu (detail místa: Trasa sem)"; repaintPanel(); }
            else {
                navigating = !navigating;
                follow = navigating || Settings.follow;
                cursorHidden = follow;
                if (navigating && !Gps.instance.running) Gps.instance.connect();
                if (navigating && Gps.instance.hasFix()) centerOnGps();
                repaint();
            }
        }
        else if (c == CLEAR_ROUTE) { route = null; navigating = false; repaint(); }
        else if (c == RELOAD) {
            failed.clear();
            poiFailed = false;
            poiBox = null;
            viewChanged();
        }
        else if (c == LOG) app.showLog();
        else if (c == SIDE_KEYS) startCalibration();
        else if (c == LAYER) app.chooseLayer();
        else if (c == SETTINGS) app.settings();
        else if (c == EXIT) app.exit();
    }

    /**
     * With Settings.akce off the map has no commands at all: the phone's Akce menu (Menu key) then
     * has nothing to show and can't pass arrow keys to the map. Keys that aren't ours (side
     * buttons, Menu, Tab...) open our menu instead; their codes are logged.
     */
    void applyCommands() {
        Command[] all = { SEARCH, ZOOM_IN, ZOOM_OUT, MENU };
        for (int i = 0; i < all.length; i++) removeCommand(all[i]);
        if (Settings.akce) for (int i = 0; i < all.length; i++) addCommand(all[i]);
    }

    /** Another map type: drop the tiles in memory (the disk cache keeps each type by its URL). */
    /** The next map type (skips Mapy.com without a key). */
    void nextLayer() {
        int l = Layers.current();
        for (int i = 1; i <= Layers.NAMES.length; i++) {
            int n = (l + i) % Layers.NAMES.length;
            if (Layers.needsKey(n) && Settings.mapyKey.length() == 0) continue;
            Settings.layer = n;
            break;
        }
        Settings.save();
        Log.add("map type " + Layers.NAMES[Layers.current()]);
        layerChanged();
    }

    void layerChanged() {
        synchronized (tiles) { tiles.clear(); tileOrder.removeAllElements(); }
        failed.clear();
        if (zoom > Layers.maxZoom()) zoom = Layers.maxZoom();
        status = "Mapa: " + Layers.NAMES[Layers.current()];
        viewChanged();
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

    // ---------------------------------------------------------------- route, GPS, navigation

    Route route;
    boolean navigating, follow = Settings.follow, cursorHidden;
    boolean hadFix;
    double routeAt, offRoute;
    int offCount;
    long lastReroute;

    public void setRoute(Route r) {
        route = r;
        routeAt = 0;
        offCount = 0;
        if (!navigating) fit(r);
        repaint();
    }

    /** Zoom and centre so the whole route fits. */
    void fit(Route r) {
        double w0 = 999, e0 = -999, s0 = 999, n0 = -999;
        for (int i = 0; i < r.lon.length; i++) {
            w0 = Math.min(w0, r.lon[i]); e0 = Math.max(e0, r.lon[i]);
            s0 = Math.min(s0, r.lat[i]); n0 = Math.max(n0, r.lat[i]);
        }
        int z = 17;
        while (z > 3 && (Geo.lonToX(e0, z) - Geo.lonToX(w0, z) > mw() - 40 || Geo.latToY(s0, z) - Geo.latToY(n0, z) > mh() - 40)) z--;
        zoom = z;
        cx = (Geo.lonToX(w0, z) + Geo.lonToX(e0, z)) / 2;
        cy = (Geo.latToY(n0, z) + Geo.latToY(s0, z)) / 2;
        viewChanged();
    }

    /**
     * Centres on the GPS position. When moving, the position sits a bit behind the centre so more
     * of the way ahead is visible, like in the phone app.
     */
    void centerOnGps() {
        Gps g = Gps.instance;
        if (navigating && zoom < 16) zoom = 17;
        center(g.lat, g.lon);
        if (g.speedKmh > 5) {
            double a = Math.toRadians(g.course), ahead = Math.min(mw(), mh()) / 4;
            cx += Math.sin(a) * ahead;
            cy -= Math.cos(a) * ahead;
        }
        viewChanged();
    }

    /** New GPS position (from the GPS thread, at most twice a second). */
    public void position() {
        Gps g = Gps.instance;
        if (g.hasFix() && route != null) {
            double[] l = route.locate(g.lon, g.lat);
            offRoute = l[0];
            routeAt = l[1];
            double limit = route.car ? 50 : 30;
            offCount = offRoute > limit ? offCount + 1 : 0;
            if (navigating && offCount >= 3 && System.currentTimeMillis() - lastReroute > 15000 && route.to != null) {
                lastReroute = System.currentTimeMillis();
                offCount = 0;
                app.reroute();
            }
        }
        boolean f = g.hasFix();
        if (f && !hadFix && follow) cursorHidden = true;      // first fix: jump to it
        hadFix = f;
        if (f && follow && isShown()) centerOnGps();
        else repaint();
    }

    // ---------------------------------------------------------------- keys and the cursor

    int mx = -1, my = -1;                     // cursor, map-area pixels
    volatile boolean kLeft, kRight, kUp, kDown;
    Thread mover;

    void initCursor() {
        if (mx < 0) { mx = mw() / 2; my = mh() / 2; }
    }

    long shownAt, lastCommand;

    // The phone's "Akce" menu (Menu key) passes its arrow keys to the map too, and Java can't tell
    // it's open. When a command comes right after a burst of up/down taps, that burst was the menu
    // being browsed: put the cursor and the map back where they were.
    static final long BURST_GAP = 2500;
    long lastArrow;
    double[] snap;
    boolean burstVertical;
    int burstTaps;

    void undoMenuArrows() {
        double[] s = snap;
        if (s == null || !burstVertical || System.currentTimeMillis() - lastArrow > BURST_GAP) return;
        kLeft = kRight = kUp = kDown = false;
        cx = s[0]; cy = s[1]; mx = (int) s[2]; my = (int) s[3];
        if ((int) s[4] != zoom) { zoom = (int) s[4]; }
        snap = null;
        hovered = objectAt(mx, my, HOVER_R);
        Log.add("menu arrows undone (" + burstTaps + " keys)");
        viewChanged();
    }
    boolean enterDown;
    long enterAt;

    protected void showNotify() {
        shownAt = System.currentTimeMillis();
        kLeft = kRight = kUp = kDown = false;
    }

    /** Another screen came up (menu choice, Settings...): stop the cursor, forget held keys. */
    protected void hideNotify() {
        kLeft = kRight = kUp = kDown = false;
        menuOpen = false;
        enterDown = false;
    }

    protected void keyPressed(int key) { key(key, true, false); }
    protected void keyRepeated(int key) { key(key, true, true); }
    protected void keyReleased(int key) { key(key, false, false); }

    void key(int key, boolean down, boolean repeat) {
        initCursor();
        int a = 0;
        try { a = getGameAction(key); } catch (Throwable e) {}
        if (calibrating >= 0) { if (down && !repeat) calibrateKey(key); return; }
        int side = sideButton(key);
        if (side >= 0) { if (down && !repeat) { Log.add("side button " + (side + 1)); barAction(side); } return; }
        if (menuOpen) { menuKey(key, a, down); return; }
        if (down && !repeat) {
            String name = "";
            try { name = getKeyName(key); } catch (Throwable e) {}
            lastKey = "klávesa " + key + (name != null && name.length() > 0 ? " " + name : "") + (a != 0 ? " (akce " + a + ")" : "");
            // arrows are too many to log (the menu is navigated with them too)
            if (a != LEFT && a != RIGHT && a != UP && a != DOWN) Log.add("key " + key + " '" + name + "' game action " + a);
        }
        boolean dir = a == LEFT || a == RIGHT || a == UP || a == DOWN;
        if (dir) {
            long now = System.currentTimeMillis();
            if (a == LEFT) kLeft = down;
            if (a == RIGHT) kRight = down;
            if (a == UP) kUp = down;
            if (a == DOWN) kDown = down;
            if (down) pressedAt[a == LEFT ? 0 : a == RIGHT ? 1 : a == UP ? 2 : 3] = now;
            if (arrowLog < 40) {                // a sample of the raw arrow events, for diagonal problems
                arrowLog++;
                Log.add("arrow " + (a == LEFT ? "L" : a == RIGHT ? "R" : a == UP ? "U" : "D") + (down ? (repeat ? " repeat" : " down") : " up") + " " + (now % 100000));
            }
            if (down && !repeat) {
                if (now - lastArrow > BURST_GAP) {         // a new burst of arrow keys: remember the view
                    snap = new double[] { cx, cy, mx, my, zoom };
                    burstVertical = true;
                    burstTaps = 0;
                }
                if (a == LEFT || a == RIGHT) burstVertical = false;
                burstTaps++;
                if (!gliding) moveCursor((a == RIGHT ? 3 : a == LEFT ? -3 : 0), (a == DOWN ? 3 : a == UP ? -3 : 0));   // react at once
                startMover();
            }
            lastArrow = now;
            return;
        }
        if (!down) {
            // Enter clicks when it's released, and only if it was also pressed on the map and no
            // menu item was picked meanwhile: on the 9300 the menu's Enter also reaches the canvas
            if ((key == 10 || key == 13 || a == FIRE) && enterDown) {
                enterDown = false;
                final long pressed = enterAt;
                new Thread() {
                    public void run() {
                        // wait a moment: the menu's command may be delivered after the key
                        try { Thread.sleep(300); } catch (InterruptedException e) {}
                        if (lastCommand < pressed - 800 && pressed - shownAt > 500 && isShown()) click();
                        else Log.add("enter ignored (menu or screen change)");
                    }
                }.start();
            }
            return;
        }
        if (repeat) return;
        if (key == '+' || key == '=' || key == '3') { setZoom(zoom + 1); return; }
        if (key == '-' || key == '1') { setZoom(zoom - 1); return; }
        if (key == '0') { toggleFullScreen(); return; }
        if (key == 27) { undoMenuArrows(); return; }
        if (key == 9) { openMenu(); return; }                   // Tab
        if (key == 'n' || key == 'N' || key == ' ') { next(); return; }
        if (key == '5' || key == '.') { commandAction(MYPOS, this); return; }     // my position
        if (key == '9' || key == ',') { nextLayer(); return; }                    // next map type
        if (key == 10 || key == 13 || a == FIRE) {
            enterDown = true;           // clicks on release, see below
            enterAt = System.currentTimeMillis();
            return;
        }
        if (key < 0 || key == 0) {
            // a key Java has no meaning for: before the side buttons are known, learn them;
            // afterwards (Menu key...) it opens our menu
            Log.add("unknown key " + key);
            if (Settings.sideKeys[0] == 0 && !sideAsked) { sideAsked = true; startCalibration(); }
            else openMenu();
            return;
        }
        Command sc = shortcut(key);
        if (sc != null) {                               // letter shortcuts; search only with H (or the menu)
            Log.add("shortcut " + (char) key + ": " + sc.getLabel());
            internalCommand = true;
            try { commandAction(sc, this); } finally { internalCommand = false; }
            return;
        }
        repaintPanel();                                  // show the unknown key code
    }

    // ---------------------------------------------------------------- side buttons

    /**
     * Without commands the 9300 sends the four side buttons to the canvas as key codes, which
     * MIDP doesn't name. We learn them once: "press the top side button", ... the bottom one.
     */
    int calibrating = -1;
    int[] learned = new int[4];
    boolean sideAsked;
    String calibMsg = "";

    void startCalibration() {
        menuOpen = false;
        kLeft = kRight = kUp = kDown = false;
        calibrating = 0;
        calibMsg = "";
        repaint();
    }

    void calibrateKey(int key) {
        if (key == 27) { calibrating = -1; status = "Boční tlačítka nenastavena (Menu: Nastavit boční tlačítka)"; repaint(); return; }
        for (int i = 0; i < calibrating; i++) {
            if (learned[i] == key) {
                calibMsg = "Kód " + key + " už má " + (i + 1) + ". tlačítko. Stiskněte " + (calibrating + 1) + ". tlačítko (Esc = zrušit).";
                repaint();
                return;
            }
        }
        learned[calibrating] = key;
        Log.add("side button " + (calibrating + 1) + " = key " + key);
        calibrating++;
        calibMsg = "";
        if (calibrating == 4) {
            calibrating = -1;
            for (int i = 0; i < 4; i++) Settings.sideKeys[i] = learned[i];
            Settings.save();
            status = "Boční tlačítka: Hledat, Přiblížit, Oddálit, Menu";
        }
        repaint();
    }

    int sideButton(int key) {
        if (key == 0) return -1;
        for (int i = 0; i < 4; i++) if (Settings.sideKeys[i] == key) return i;
        return -1;
    }

    void paintCalibration(Graphics g, int w, int h) {
        Font b = bold(), f = small();
        int bw = Math.min(w - 10, 330), bh = 4 * f.getHeight() + b.getHeight() + 14, x0 = (w - bw) / 2, y0 = (h - bh) / 2;
        g.setColor(0x2B2D31);
        g.fillRect(x0, y0, bw, bh);
        g.setColor(0x8AB4F8);
        g.drawRect(x0, y0, bw - 1, bh - 1);
        g.setFont(b);
        g.setColor(0xFFFFFF);
        String[] names = { "horní (Hledat)", "2. (Přiblížit)", "3. (Oddálit)", "dolní (Menu)" };
        g.drawString("Stiskněte " + names[calibrating] + " boční tlačítko", x0 + 8, y0 + 6, Graphics.TOP | Graphics.LEFT);
        g.setFont(f);
        g.setColor(0xDBDEE1);
        String t = calibMsg.length() > 0 ? calibMsg
            : "Nastavení bočních tlačítek vpravo (" + (calibrating + 1) + " ze 4), shora dolů. Esc = zrušit.";
        wrap(g, f, t, x0 + 8, y0 + 10 + b.getHeight(), bw - 16, 4);
    }

    // ---------------------------------------------------------------- our menu

    static final Command LAYER = new Command("Typ mapy", Command.SCREEN, 6);
    static final Command SIDE_KEYS = new Command("Nastavit boční tlačítka", Command.SCREEN, 11);
    static final Command[] MENU_ITEMS = { OPEN, SEARCH, ROUTE, ZOOM_IN, ZOOM_OUT, LAYER, MYPOS, FOLLOW, GPS, NAV, CLEAR_ROUTE, NEXT, HERE, POIS, FULL, RELOAD, LOG, SETTINGS, SIDE_KEYS, EXIT };
    volatile boolean menuOpen;
    boolean internalCommand;
    int menuSel, menuTop;

    void openMenu() {
        kLeft = kRight = kUp = kDown = false;
        enterDown = false;
        menuOpen = true;
        menuSel = 0;
        menuTop = 0;
        repaint();
    }

    void closeMenu() {
        menuOpen = false;
        repaint();
    }

    /** Keys while our menu is open: up/down choose, Enter or right picks, left / Esc closes. */
    void menuKey(int key, int a, boolean down) {
        if (!down) return;                       // releases (and the Enter release) end here too
        int n = MENU_ITEMS.length;
        if (a == UP) menuSel = (menuSel + n - 1) % n;
        else if (a == DOWN) menuSel = (menuSel + 1) % n;
        else if (a == FIRE || a == RIGHT || key == 10 || key == 13) {
            Command c = MENU_ITEMS[menuSel];
            closeMenu();
            internalCommand = true;
            try { commandAction(c, this); } finally { internalCommand = false; }
            return;
        } else if (a == LEFT || key == 27 || key == 8 || key == -8) { closeMenu(); return; }
        else if (key >= '1' && key <= '9' && key - '1' < n) { menuSel = key - '1'; }
        repaint();
    }

    /** Keyboard shortcuts on the map (Czech initials), shown in the menu. */
    static final Object[][] SHORTCUTS = {
        { "H", SEARCH }, { "T", ROUTE }, { "P", MYPOS }, { "M", LAYER }, { "S", FOLLOW }, { "G", GPS },
        { "V", NAV }, { "Z", CLEAR_ROUTE }, { "C", HERE }, { "B", POIS }, { "F", FULL }, { "R", RELOAD },
        { "L", LOG }, { "K", SETTINGS }, { "O", OPEN },
    };

    static Command shortcut(int key) {
        if (key < 'A' || key > 'z') return null;
        char ch = Character.toUpperCase((char) key);
        for (int i = 0; i < SHORTCUTS.length; i++) if (((String) SHORTCUTS[i][0]).charAt(0) == ch) return (Command) SHORTCUTS[i][1];
        return null;
    }

    static String shortcutOf(Command c) {
        for (int i = 0; i < SHORTCUTS.length; i++) if (SHORTCUTS[i][1] == c) return (String) SHORTCUTS[i][0];
        return null;
    }

    String menuLabel(Command c) {
        String l;
        if (c == FOLLOW) l = "Sledovat polohu";
        else if (c == GPS) l = "GPS přes Bluetooth";
        else if (c == NAV) l = "Navigace";
        else if (c == POIS) l = "Body zájmu";
        else if (c == FULL) l = "Celá obrazovka";
        else if (c == MYPOS) l = "Moje poloha";
        else l = c.getLabel();
        String k = shortcutOf(c);
        if (c == ZOOM_IN) k = "+";
        if (c == ZOOM_OUT) k = "-";
        if (c == NEXT) k = "N";
        return k == null ? l : l + "  (" + k + ")";
    }

    /** Menu item state: -1 = an action, 0 = off, 1 = on (drawn as a checkbox). */
    int menuCheck(Command c) {
        if (c == FOLLOW) return follow ? 1 : 0;
        if (c == GPS) return Gps.instance.running ? 1 : 0;
        if (c == NAV) return navigating ? 1 : 0;
        if (c == POIS) return Settings.pois ? 1 : 0;
        if (c == FULL) return fullScreen ? 1 : 0;
        return -1;
    }

    static void checkbox(Graphics g, int x, int y, int sz, boolean on, boolean selected) {
        g.setColor(selected ? 0xFFFFFF : 0xB5BAC1);
        g.drawRect(x, y, sz, sz);
        if (on) {
            g.setColor(selected ? 0xFFFFFF : 0x6DD58C);
            g.drawLine(x + 2, y + sz / 2, x + sz / 2 - 1, y + sz - 2);
            g.drawLine(x + 2, y + sz / 2 + 1, x + sz / 2 - 1, y + sz - 1);
            g.drawLine(x + sz / 2 - 1, y + sz - 2, x + sz - 2, y + 2);
            g.drawLine(x + sz / 2 - 1, y + sz - 1, x + sz - 2, y + 3);
        }
    }

    /** GPS state: 0 = off, 1 = connected but no position, 2 = receiving positions. */
    static int gpsState() {
        Gps g = Gps.instance;
        return g.hasFix() ? 2 : g.running ? 1 : 0;
    }

    /** Location pin icon (about 10 x 14 px, tip at x, y + 13): blue when receiving, crossed out otherwise. */
    static void gpsIcon(Graphics g, int x, int y, int state) {
        int col = state == 2 ? 0x1E88E5 : state == 1 ? 0xF9A825 : 0x80848E;
        g.setColor(col);
        g.fillArc(x, y, 10, 10, 0, 360);
        g.fillTriangle(x + 1, y + 7, x + 9, y + 7, x + 5, y + 13);
        g.setColor(0xFFFFFF);
        g.fillArc(x + 3, y + 3, 4, 4, 0, 360);
        if (state != 2) {                       // crossed out: no position
            g.setColor(0xEE4444);
            g.drawLine(x - 1, y + 13, x + 11, y - 1);
            g.drawLine(x, y + 13, x + 12, y - 1);
        }
    }

    void paintMenu(Graphics g, int w, int h) {
        Font f = small(), b = bold();
        int ih = f.getHeight() + 3, n = MENU_ITEMS.length;
        int mw0 = 0;
        for (int i = 0; i < n; i++) mw0 = Math.max(mw0, b.stringWidth(menuLabel(MENU_ITEMS[i])));
        int bw = Math.min(w - 4, mw0 + 42), rows = Math.min(n, (h - 8) / ih);
        if (menuSel < menuTop) menuTop = menuSel;
        if (menuSel >= menuTop + rows) menuTop = menuSel - rows + 1;
        int bh = rows * ih + 4, x0 = w - bw - 2, y0 = 2;
        g.setColor(0x2B2D31);
        g.fillRect(x0, y0, bw, bh);
        g.setColor(0x8AB4F8);
        g.drawRect(x0, y0, bw - 1, bh - 1);
        for (int r = 0; r < rows; r++) {
            int i = menuTop + r, y = y0 + 2 + r * ih;
            if (i == menuSel) { g.setColor(0x1565C0); g.fillRect(x0 + 2, y, bw - 4, ih); }
            Command mc = MENU_ITEMS[i];
            int chk = menuCheck(mc), cs = f.getHeight() - 5;
            if (chk >= 0) checkbox(g, x0 + 6, y + (ih - cs) / 2, cs, chk == 1, i == menuSel);
            else if (mc == MYPOS) gpsIcon(g, x0 + 6, y + (ih - 14) / 2, gpsState());
            g.setFont(i == menuSel ? b : f);
            g.setColor(i == menuSel ? 0xFFFFFF : 0xDBDEE1);
            g.drawString(menuLabel(mc), x0 + 22, y + 1, Graphics.TOP | Graphics.LEFT);
        }
        g.setColor(0x80848E);
        if (menuTop > 0) g.fillTriangle(x0 + bw - 10, y0 + 8, x0 + bw - 6, y0 + 4, x0 + bw - 2, y0 + 8);
        if (menuTop + rows < n) g.fillTriangle(x0 + bw - 10, y0 + bh - 8, x0 + bw - 6, y0 + bh - 4, x0 + bw - 2, y0 + bh - 8);
    }

    // cursor speed in pixels per second: starts slow for precise pointing, accelerates while held
    static final double V0 = 50, VMAX = 300, ACCEL = 220;
    /** A tap only nudges the cursor; it glides when the key is held this long. */
    static final long HOLD_MS = 250;

    /** Last press of left, right, up, down; a direction counts as held for a moment after a press,
     *  so a joystick that sends its diagonal as quick alternating presses still moves diagonally. */
    final long[] pressedAt = new long[4];
    static final long PRESS_HOLD = 180;
    int arrowLog;
    volatile boolean gliding;

    boolean held(boolean flag, int i, long now) {
        return flag || now - pressedAt[i] < PRESS_HOLD;
    }

    void startMover() {
        if (mover != null && mover.isAlive()) return;
        mover = new Thread() {
            public void run() {
                double v = V0, fx = 0, fy = 0;
                long last = System.currentTimeMillis(), started = last;
                // moves while a direction is held (keyReleased stops it); time-based, so uneven
                // timer ticks (62 ms resolution on the 9300) don't make it jerky
                while (System.currentTimeMillis() - started < 15000) {
                    try { Thread.sleep(25); } catch (InterruptedException e) {}
                    long now = System.currentTimeMillis();
                    boolean l = held(kLeft, 0, now), rt = held(kRight, 1, now), u = held(kUp, 2, now), dn = held(kDown, 3, now);
                    if (!(l || rt || u || dn)) break;
                    double dt = (now - last) / 1000.0;
                    last = now;
                    if (dt <= 0) continue;
                    if (now - started < HOLD_MS) continue;     // a tap only nudges; holding glides
                    gliding = true;
                    v = Math.min(VMAX, v + ACCEL * dt);
                    int hx = (rt ? 1 : 0) - (l ? 1 : 0), hy = (dn ? 1 : 0) - (u ? 1 : 0);
                    double d = v * dt * (hx != 0 && hy != 0 ? 0.7071 : 1.0);
                    fx += hx * d;
                    fy += hy * d;
                    int ix = (int) fx, iy = (int) fy;
                    if (ix != 0 || iy != 0) {
                        fx -= ix;
                        fy -= iy;
                        burstVertical = false;      // a held key moving the cursor: real map use, not the menu
                        moveCursor(ix, iy);
                    }
                }
                gliding = false;
                if (System.currentTimeMillis() - started >= 15000) kLeft = kRight = kUp = kDown = false;   // a lost release
            }
        };
        mover.start();
    }

    /** Moves the cursor; past the edge the map scrolls instead. */
    synchronized void moveCursor(int dx, int dy) {
        if (menuOpen) return;
        if (!isShown()) { kLeft = kRight = kUp = kDown = false; return; }
        cursorHidden = false;
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
        if (hovered != before) hoverChanged(hovered);
        if (panned) {
            if (follow) status = "Sledování vypnuto (Menu: Moje poloha)";
            follow = false;
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
        if (!isShown()) return;
        initCursor();
        Place p = objectAt(mx, my, HOVER_R);
        Log.add("click: " + (p != null ? p.title : "co je tady"));
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
            if (i == calibrating) { g.setColor(0x8A6D00); g.fillRect(x0 + 1, h * i / 4 + 1, BAR - 1, h / 4 - 1); }   // the one to press
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
            } else {                            // menu: three lines
                g.fillRect(cx0 - 7, cy0 - 6, 14, 2);
                g.fillRect(cx0 - 7, cy0 - 1, 14, 2);
                g.fillRect(cx0 - 7, cy0 + 4, 14, 2);
            }
        }
    }

    void barAction(int i) {
        if (i < 3 && menuOpen) closeMenu();
        if (i == 0) app.search("");
        else if (i == 1) setZoom(zoom + 1);
        else if (i == 2) setZoom(zoom - 1);
        else if (menuOpen) closeMenu();
        else openMenu();
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
        paintRoute(g, ox, oy, w, h);
        // objects; the hovered one is bigger, ringed and labelled like a link
        Place hov = hovered;
        Vector ps = pois;
        for (int i = 0; i < ps.size(); i++) {
            Place p = (Place) ps.elementAt(i);
            if (p == hov) continue;
            int px = sx(p), py = sy(p);
            if (px < -9 || py < -9 || px > w + 9 || py > h + 9) continue;
            if (p == selected) { g.setColor(0x1565C0); g.drawArc(px - 10, py - 10, 19, 19, 0, 360); }
            Kinds.draw(g, p.kind, px, py);
        }
        if (marker != null && marker != hov) pin(g, sx(marker), sy(marker), 0xD32F2F);
        if (hov != null) {
            int px = sx(hov), py = sy(hov);
            if (hov.osm) {
                g.setColor(0x1565C0);
                g.fillArc(px - 12, py - 12, 24, 24, 0, 360);
                g.setColor(0xFFFFFF);
                g.fillArc(px - 10, py - 10, 20, 20, 0, 360);
                Kinds.draw(g, hov.kind, px, py);
            } else {
                pin(g, px, py, 0xB71C1C);
            }
            label(g, hov.title, px, py - (hov.osm ? 13 : 23), w);
        }
        paintGps(g, ox, oy);
        if (!(follow && cursorHidden)) cursor(g, mx < 0 ? w / 2 : mx, my < 0 ? h / 2 : my);
        paintSpeed(g, w, h);
        paintWarning(g, w);
        if (menuOpen) paintMenu(g, w, h);
        if (calibrating >= 0) paintCalibration(g, w, h);
    }

    void paintRoute(Graphics g, int ox, int oy, int w, int h) {
        Route r = route;
        if (r == null || r.lon.length < 2) return;
        int px = (int) (Geo.lonToX(r.lon[0], zoom) - ox), py = (int) (Geo.latToY(r.lat[0], zoom) - oy);
        for (int i = 1; i < r.lon.length; i++) {
            int qx = (int) (Geo.lonToX(r.lon[i], zoom) - ox), qy = (int) (Geo.latToY(r.lat[i], zoom) - oy);
            boolean visible = !((px < -10 && qx < -10) || (px > w + 10 && qx > w + 10) || (py < -10 && qy < -10) || (py > h + 10 && qy > h + 10));
            if (visible) {
                boolean done = navigating && r.along[i] < routeAt;
                g.setColor(0xFFFFFF);
                g.drawLine(px - 2, py, qx - 2, qy); g.drawLine(px + 2, py, qx + 2, qy);
                g.drawLine(px, py - 2, qx, qy - 2); g.drawLine(px, py + 2, qx, qy + 2);
                g.setColor(done ? 0x9E9E9E : (r.car ? 0x1565C0 : 0x7B1FA2));
                g.drawLine(px, py, qx, qy);
                g.drawLine(px - 1, py, qx - 1, qy); g.drawLine(px + 1, py, qx + 1, qy);
                g.drawLine(px, py - 1, qx, qy - 1); g.drawLine(px, py + 1, qx, qy + 1);
            }
            px = qx; py = qy;
        }
        // finish flag
        int ex = (int) (Geo.lonToX(r.lon[r.lon.length - 1], zoom) - ox), ey = (int) (Geo.latToY(r.lat[r.lat.length - 1], zoom) - oy);
        g.setColor(0x000000);
        g.drawLine(ex, ey, ex, ey - 16);
        g.setColor(0x2E7D32);
        g.fillTriangle(ex + 1, ey - 16, ex + 11, ey - 12, ex + 1, ey - 8);
    }

    /** "před 3 min" for the last known position. */
    static String age(long t) {
        long s = (System.currentTimeMillis() - t) / 1000;
        if (s < 60) return "před " + s + " s";
        if (s < 3600) return "před " + s / 60 + " min";
        if (s < 86400) return "před " + s / 3600 + " h";
        return "před " + s / 86400 + " dny";
    }

    void paintGps(Graphics g, int ox, int oy) {
        Gps gp = Gps.instance;
        if (!gp.hasFix()) {
            // no current position: the last known one, hollow and grey
            if (Settings.gpsTime == 0) return;
            int x = (int) (Geo.lonToX(Settings.gpsLon, zoom) - ox), y = (int) (Geo.latToY(Settings.gpsLat, zoom) - oy);
            g.setColor(0xFFFFFF);
            g.fillArc(x - 7, y - 7, 14, 14, 0, 360);
            g.setColor(0x80848E);
            g.fillArc(x - 5, y - 5, 10, 10, 0, 360);
            g.setColor(0xFFFFFF);
            g.fillArc(x - 2, y - 2, 4, 4, 0, 360);
            return;
        }
        int x = (int) (Geo.lonToX(gp.lon, zoom) - ox), y = (int) (Geo.latToY(gp.lat, zoom) - oy);
        if (gp.speedKmh > 2) {
            // moving: a navigation arrow pointing where we go
            g.setColor(0xFFFFFF);
            arrow(g, x, y, gp.course, 14);
            g.setColor(0x1E88E5);
            arrow(g, x, y, gp.course, 11);
        } else {
            g.setColor(0xFFFFFF);
            g.fillArc(x - 8, y - 8, 16, 16, 0, 360);
            g.setColor(0x1E88E5);
            g.fillArc(x - 6, y - 6, 12, 12, 0, 360);
        }
    }

    /** Arrow head (chevron with a notch) centred on x, y, pointing to course degrees, size r. */
    static void arrow(Graphics g, int x, int y, double course, int r) {
        double a = Math.toRadians(course);
        double s = Math.sin(a), c = Math.cos(a);
        int tx = x + (int) (s * r), ty = y - (int) (c * r);                               // tip
        int lx = x + (int) (Math.sin(a - 2.5) * r), ly = y - (int) (Math.cos(a - 2.5) * r);
        int rx = x + (int) (Math.sin(a + 2.5) * r), ry = y - (int) (Math.cos(a + 2.5) * r);
        int nx = x - (int) (s * r * 0.35), ny = y + (int) (c * r * 0.35);              // notch
        g.fillTriangle(tx, ty, lx, ly, nx, ny);
        g.fillTriangle(tx, ty, rx, ry, nx, ny);
    }

    static final String[] DIRS = { "S", "SV", "V", "JV", "J", "JZ", "Z", "SZ" };

    static String direction(double course) {
        int i = (int) ((course + 22.5) / 45) & 7;
        return DIRS[i] + " " + (int) course + "°";
    }

    /** Speed and heading box in the map's bottom left corner, like the phone app. */
    void paintSpeed(Graphics g, int w, int h) {
        Gps gp = Gps.instance;
        if (!gp.hasFix()) return;
        Font big = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, Font.SIZE_LARGE), f = small();
        String sp = String.valueOf((int) (gp.speedKmh + 0.5));
        int bw = Math.max(big.stringWidth(sp), f.stringWidth("km/h")) + 40, bh = big.getHeight() + f.getHeight() + 2;
        int x0 = 3, y0 = h - bh - 3;
        g.setColor(0xFFFFFF);
        g.fillRoundRect(x0, y0, bw, bh, 8, 8);
        g.setColor(0x1565C0);
        g.drawRoundRect(x0, y0, bw, bh, 8, 8);
        int lim = SpeedLimit.instance.limit;
        boolean over = lim > 0 && gp.speedKmh > lim + 0.5;
        if (over) {                                             // over the limit: the box goes red
            g.setColor(0xD32F2F);
            g.fillRoundRect(x0 + 1, y0 + 1, bw - 1, bh - 1, 8, 8);
        }
        g.setFont(big);
        g.setColor(over ? 0xFFFFFF : 0x000000);
        g.drawString(sp, x0 + 5, y0 + 1, Graphics.TOP | Graphics.LEFT);
        g.setFont(f);
        g.setColor(over ? 0xFFFFFF : 0x444444);
        g.drawString("km/h", x0 + 5, y0 + 1 + big.getHeight(), Graphics.TOP | Graphics.LEFT);
        compass(g, x0 + bw - 17, y0 + bh / 2, 14, gp.speedKmh > 2 ? gp.course : -1);
        if (lim > 0) limitSign(g, x0 + bw + 4 + bh / 2, y0 + bh / 2, bh / 2, lim, over, SpeedLimit.instance.implied);
        if (follow) {
            g.setColor(0x1565C0);
            g.fillArc(x0 + bw - 6, y0 - 3, 8, 8, 0, 360);         // dot = following
        }
    }

    /** Radar / accident black spot ahead: a yellow banner on top of the map. */
    void paintWarning(Graphics g, int w) {
        String t = SpeedLimit.instance.warning;
        if (t.length() == 0 || !Gps.instance.hasFix()) return;
        Font b = bold();
        int tw = b.stringWidth(t) + 30, bh = b.getHeight() + 6, x0 = (w - tw) / 2;
        g.setColor(0xFFD600);
        g.fillRect(x0, 2, tw, bh);
        g.setColor(0x000000);
        g.drawRect(x0, 2, tw - 1, bh - 1);
        // warning triangle
        g.setColor(0xD32F2F);
        g.fillTriangle(x0 + 12, 5, x0 + 4, bh - 1, x0 + 20, bh - 1);
        g.setColor(0xFFFFFF);
        g.fillTriangle(x0 + 12, 9, x0 + 8, bh - 3, x0 + 16, bh - 3);
        g.setColor(0x000000);
        g.setFont(b);
        g.drawString(t, x0 + 25, 5, Graphics.TOP | Graphics.LEFT);
    }

    /** Speed limit sign: white disc, red ring, black number; when over the limit, red with a white number. */
    static void limitSign(Graphics g, int x, int y, int r, int lim, boolean over, boolean implied) {
        g.setColor(0xD32F2F);
        g.fillArc(x - r, y - r, 2 * r, 2 * r, 0, 360);
        int ri = r * 7 / 10;
        if (!over) {
            g.setColor(0xFFFFFF);
            g.fillArc(x - ri, y - ri, 2 * ri, 2 * ri, 0, 360);
        }
        Font f = Font.getFont(Font.FACE_SYSTEM, Font.STYLE_BOLD, lim >= 100 ? Font.SIZE_SMALL : Font.SIZE_MEDIUM);
        g.setFont(f);
        g.setColor(over ? 0xFFFFFF : 0x000000);
        String t = lim + (implied ? "?" : "");
        g.drawString(t, x, y - f.getHeight() / 2, Graphics.TOP | Graphics.HCENTER);
    }

    /** Compass: a ring with N on top and the heading arrow; course < 0 = unknown (standing). */
    static void compass(Graphics g, int x, int y, int r, double course) {
        g.setColor(0xE8EAED);
        g.fillArc(x - r, y - r, 2 * r, 2 * r, 0, 360);
        g.setColor(0x80848E);
        g.drawArc(x - r, y - r, 2 * r, 2 * r, 0, 360);
        g.setColor(0xD32F2F);
        g.fillTriangle(x, y - r + 1, x - 3, y - r + 6, x + 3, y - r + 6);       // north tick
        if (course >= 0) {
            g.setColor(0x1E88E5);
            arrow(g, x, y, course, r - 4);
        } else {
            g.setColor(0x1E88E5);
            g.fillArc(x - 3, y - 3, 6, 6, 0, 360);
        }
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
        gpsIcon(g, pw - 16, y, gpsState());
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
        Route rt = route;
        if (rt != null) {
            Gps gp = Gps.instance;
            if (navigating && gp.hasFix()) {
                Route.Step st = rt.nextStep(routeAt);
                if (st != null) {
                    double to = rt.along[st.index] - routeAt;
                    g.setFont(b);
                    g.setColor(0xFFFFFF);
                    y = wrap(g, b, (to > 15 ? "Za " + Route.km(to) + ": " : "") + st.text, 3, y, tw, 3);
                    g.setFont(f);
                }
                double rest = rt.distance - routeAt;
                g.setColor(0xB5BAC1);
                y = wrap(g, f, rest < 25 ? "Jste v cíli" : "Zbývá " + Route.km(rest) + ", " + Route.time(rt.duration * rest / Math.max(1, rt.distance)), 3, y, tw, 2);
                if (offRoute > (rt.car ? 50 : 30)) { g.setColor(0xEE6C6C); y = wrap(g, f, "Mimo trasu (" + (int) offRoute + " m)", 3, y, tw, 1); }
            } else {
                g.setColor(0xFFFFFF);
                y = wrap(g, f, (rt.car ? "Autem " : "Pěšky ") + Route.km(rt.distance) + ", " + Route.time(rt.duration), 3, y, tw, 2);
                g.setColor(0x80848E);
                y = wrap(g, f, navigating ? "Čekám na GPS: " + gp.status : "Menu: Navigace start", 3, y, tw, 2);
            }
            y += 3;
        } else if (Gps.instance.running && !Gps.instance.hasFix()) {
            g.setColor(0x80848E);
            y = wrap(g, f, "GPS: " + Gps.instance.status, 3, y, tw, 2);
            if (Settings.gpsTime > 0) y = wrap(g, f, "Šedá tečka = poslední poloha " + age(Settings.gpsTime), 3, y, tw, 2);
        }
        Gps gq = Gps.instance;
        if (gq.hasFix()) {
            // speed, heading and whether the map follows
            compass(g, 3 + 13, y + 13, 13, gq.speedKmh > 2 ? gq.course : -1);
            g.setFont(b);
            g.setColor(0xFFFFFF);
            g.drawString((int) (gq.speedKmh + 0.5) + " km/h", 32, y, Graphics.TOP | Graphics.LEFT);
            g.setFont(f);
            g.setColor(0xB5BAC1);
            g.drawString(gq.speedKmh > 2 ? direction(gq.course) : "stojím", 32, y + b.getHeight(), Graphics.TOP | Graphics.LEFT);
            y += Math.max(28, b.getHeight() + fh) + 1;
            SpeedLimit sl = SpeedLimit.instance;
            if (Settings.speedLimits && sl.limit > 0) {
                boolean over = gq.speedKmh > sl.limit + 0.5;
                g.setColor(over ? 0xEE6C6C : 0xB5BAC1);
                y = wrap(g, f, "Limit " + sl.limit + (sl.implied ? " (odhad)" : "") + (over ? " - PŘEKROČENO" : "") + (sl.road.length() > 0 ? ", " + sl.road : ""), 3, y, tw, 2);
            }
            g.setColor(follow ? 0x8AB4F8 : 0x80848E);
            y = wrap(g, f, follow ? "Sledování polohy: zap" : "Sledování: vyp (Moje poloha)", 3, y, tw, 1);
            y += 3;
        }
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
            if (hov == previewFor) {
                if (previewRating.length() > 0) {
                    g.setColor(0xFCEE74);
                    y = wrap(g, f, previewRating, 3, y, tw, 2);
                }
                Image im = previewImage;
                int room = h - 4 * fh - 6 - y;
                if (im != null && room > 30) {
                    int cx0 = g.getClipX(), cy0 = g.getClipY(), cw0 = g.getClipWidth(), ch0 = g.getClipHeight();
                    g.clipRect(3, y + 2, tw, Math.min(room, im.getHeight()));
                    g.drawImage(im, 3 + tw / 2, y + 2, Graphics.TOP | Graphics.HCENTER);
                    g.setClip(cx0, cy0, cw0, ch0);
                    y += Math.min(room, im.getHeight()) + 4;
                }
            }
            g.setColor(0x80848E);
            y = wrap(g, f, "Enter = otevřít", 3, y, tw, 1);
        } else {
            g.setColor(0x80848E);
            String help = Settings.pois && zoom < POI_ZOOM
                ? "Body zájmu od přiblížení " + POI_ZOOM + ". Enter = co je tady."
                : "Enter = otevřít, H = hledat, T = trasa, P = moje poloha, M = typ mapy, N = další bod. Menu (Tab) ukazuje všechny klávesy." + (Settings.akce ? " (nebo 4. boční tlačítko)" : "");
            y = wrap(g, f, help, 3, y, tw, 4);
        }
        // bottom: last key (for finding Chr+arrow codes), zoom, credits
        int creditLines = Layers.credit(1).length() > 0 ? 2 : 1;
        int by = h - (1 + creditLines) * fh - 2;
        g.setColor(0x80848E);
        if (lastKey.length() > 0 && by - fh > y) g.drawString(clip(f, lastKey, tw), 3, by - fh, Graphics.TOP | Graphics.LEFT);
        g.setColor(0xB5BAC1);
        g.drawString("Zoom " + zoom + (zoom > Layers.nativeZoom() ? " (zvětšeno)" : ""), 3, by, Graphics.TOP | Graphics.LEFT);
        g.drawString(Layers.credit(0), 3, by + fh, Graphics.TOP | Graphics.LEFT);
        if (creditLines > 1) g.drawString(Layers.credit(1), 3, by + 2 * fh, Graphics.TOP | Graphics.LEFT);
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
