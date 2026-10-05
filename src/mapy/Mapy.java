package mapy;

import java.io.*;
import java.util.Vector;
import javax.microedition.lcdui.*;
import javax.microedition.midlet.*;

/**
 * Mapy for the Nokia 9300: OSM map with a POI overlay, Mapy.com search and place details.
 * All network work runs in background threads through Net (one request at a time);
 * the map's bottom strip shows the progress.
 */
public class Mapy extends MIDlet implements CommandListener {
    static final Command BACK = new Command("Zpět", Command.BACK, 1);
    static final Command DO_SEARCH = new Command("Hledat", Command.SCREEN, 1);
    static final Command SHOW = new Command("Ukázat na mapě", Command.SCREEN, 1);
    static final Command MAPY_DETAIL = new Command("Detail z Mapy.com", Command.SCREEN, 2);
    static final Command SAVE = new Command("Uložit", Command.SCREEN, 1);
    static final Command SEND = new Command("Odeslat log na PC", Command.SCREEN, 2);
    static final Command ROUTE_WALK = new Command("Trasa sem pěšky", Command.SCREEN, 2);
    static final Command ROUTE_CAR = new Command("Trasa sem autem", Command.SCREEN, 2);

    Display display;
    MapCanvas map;
    TextBox searchBox;
    List results;
    Vector resultPlaces = new Vector();
    Form detailForm;
    Place detailPlace;
    volatile boolean busy;

    protected void startApp() {
        if (display != null) return;
        display = Display.getDisplay(this);
        Log.start();
        Settings.load();
        Log.add("start: " + System.getProperty("microedition.platform") + ", view " + Geo.format(Settings.lat, Settings.lon) + " z" + Settings.zoom);
        map = new MapCanvas(this);
        SpeedLimit.instance.start();
        display.setCurrent(map);
        if (Settings.gpsAuto) {
            Log.add("GPS on at start");
            Gps.instance.connect();     // gives up by itself (status "nepřipojeno") when the phone isn't there
        }
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean u) {
        Log.add("exit");
        Log.save();
        Settings.save();
        DiskCache.saveIndex();
    }

    void exit() {
        Log.add("exit");
        Log.save();
        Settings.save();
        DiskCache.saveIndex();
        notifyDestroyed();
    }

    void showMap() {
        display.setCurrent(map);
    }

    /**
     * True while the map is on screen. A background result (search results, a detail, an alert)
     * replaces the screen only then: the user may have opened Settings or the log meanwhile, and
     * those must stay put.
     */
    boolean mapOnTop() {
        Displayable c = display.getCurrent();
        return c == map || c == null || map.isShown();
    }

    /** Shows a background result if the map is still on screen; otherwise just says it's ready. */
    void showResult(Displayable d, String what) {
        if (mapOnTop()) display.setCurrent(d);
        else {
            Log.add(what + " ready, not shown: another screen is open");
            map.status = what + " připraven, ale byla otevřená jiná obrazovka";
        }
    }

    void error(String what, Throwable e) {
        Log.add(what + ": " + e);
        String msg = e.getMessage() != null && !(e instanceof RuntimeException) ? e.getMessage() : e.toString();
        if (!mapOnTop()) { map.status = what + ": chyba " + msg; return; }
        Alert a = new Alert("Chyba", what + ":\n" + msg, null, AlertType.ERROR);
        a.setTimeout(Alert.FOREVER);
        display.setCurrent(a, map);
    }

    /** Runs network work in the background; the map shows the progress meanwhile. */
    abstract class Task extends Thread {
        abstract void work() throws Exception;
        abstract String name();

        public void run() {
            try {
                work();
            } catch (Throwable e) {
                error(name(), e);
            } finally {
                busy = false;
            }
        }

        void go() {
            if (busy) {
                Alert a = new Alert("Mapy", "Počkej, ještě se načítá předchozí požadavek.", null, AlertType.INFO);
                a.setTimeout(2000);
                display.setCurrent(a, display.getCurrent());
                return;
            }
            busy = true;
            showMap();
            start();
        }
    }

    // ---------------------------------------------------------------- search

    void search(String start) {
        if (searchBox == null) {
            searchBox = new TextBox("Hledat (místo, adresa, firma)", "", 100, TextField.ANY);
            searchBox.addCommand(DO_SEARCH);
            searchBox.addCommand(BACK);
            searchBox.setCommandListener(this);
        }
        searchBox.setString(start);
        display.setCurrent(searchBox);
    }

    void runSearch(final String q) {
        if (q.trim().length() == 0) { showMap(); return; }
        new Task() {
            String name() { return "Hledání"; }
            void work() throws Exception {
                double[] v = map.viewBox(0);
                Vector r = MapyApi.suggest(q, map.centerLon(), map.centerLat(), v, map.zoom);
                resultPlaces = r;
                results = new List("Výsledky: " + q, List.IMPLICIT);
                for (int i = 0; i < r.size(); i++) results.append(((Place) r.elementAt(i)).toString(), null);
                if (r.size() == 0) results.append("(nic nenalezeno)", null);
                results.addCommand(BACK);
                results.addCommand(SHOW);
                results.setSelectCommand(SHOW);
                results.setCommandListener(Mapy.this);
                showResult(results, "Výsledek hledání");
            }
        }.go();
    }

    // ---------------------------------------------------------------- detail

    void detail(final Place p) {
        new Task() {
            String name() { return "Detail"; }
            void work() throws Exception {
                Object[] c;
                try {
                    c = lookup(p);
                } catch (Exception e) {
                    if (!p.osm) throw e;
                    Log.add("Mapy.com lookup for " + p.title + " failed: " + e);
                    c = new Object[] { p, null };
                }
                showDetail((Place) c[0], (FrpcStruct) c[1]);
            }
        }.go();
    }

    void whatsHere(final double lon, final double lat, final int zoom) {
        new Task() {
            String name() { return "Co je tady"; }
            void work() throws Exception {
                FrpcStruct d = MapyApi.detailAt(lon, lat, zoom);
                Place p = placeOf(d, lon, lat);
                showDetail(p, d);
            }
        }.go();
    }

    /** Mapy.com details already fetched: key -> { Place (Mapy.com place or the OSM one), FrpcStruct detail or null }. */
    final Lru details = new Lru(40);

    static String keyOf(Place p) {
        return p.osm ? "osm:" + p.id : p.source + "/" + p.id;
    }

    /**
     * The Mapy.com detail of a place. An OSM object is first found in Mapy.com by its name near its
     * position (nearest result within 300 m); without a match the detail is null.
     */
    Object[] lookup(Place p) throws Exception {
        String key = keyOf(p);
        Object[] c = (Object[]) details.get(key);
        if (c != null) return c;
        Place best = p;
        if (p.osm) {
            best = null;
            double d = 0.002, bd = 300;     // metres
            Vector r = MapyApi.suggest(p.title, p.lon, p.lat, new double[] { p.lon - d, p.lat - d, p.lon + d, p.lat + d }, 17);
            for (int i = 0; i < r.size(); i++) {
                Place m = (Place) r.elementAt(i);
                double dist = Geo.distance(p.lon, p.lat, m.lon, m.lat);
                if (dist < bd && m.source.length() > 0) { bd = dist; best = m; }
            }
            if (best == null) {
                Log.add("no Mapy.com match for " + p.title);
                c = new Object[] { p, null };
                details.put(key, c);
                return c;
            }
            Log.add("Mapy.com match for " + p.title + ": " + best.source + "/" + best.id + " (" + (int) bd + " m)");
        }
        c = new Object[] { best, MapyApi.detail(best.source, best.id) };
        details.put(key, c);
        return c;
    }

    // ---------------------------------------------------------------- hover preview

    volatile boolean previewing;

    /** Thumbnail and rating for the panel when the cursor rests on an object. */
    void preview(final Place p) {
        if (!Settings.preview || previewing || busy) return;
        previewing = true;
        new Thread() {
            public void run() {
                try {
                    Object[] c = lookup(p);
                    FrpcStruct d = (FrpcStruct) c[1];
                    String rating = d == null ? "" : rating(d);
                    map.setPreview(p, null, rating);
                    String u = d == null ? null : Photos.header(d);
                    if (u != null) map.setPreview(p, Photos.load(u, 80, "náhled"), rating);
                } catch (Throwable e) {
                    Log.add("preview " + p.title + ": " + e);
                } finally {
                    previewing = false;
                }
            }
        }.start();
    }

    static String rating(FrpcStruct d) {
        FrpcStruct rv = d.getStruct("review");
        if (rv == null || rv.getDouble("review_rating_stars") == null) return "";
        return Geo.fmt(rv.getDouble("review_rating_stars").doubleValue(), 1) + " z 5 (" + rv.getInt("total", 0) + " hodnocení)";
    }

    static Place placeOf(FrpcStruct d, double lon, double lat) {
        Place p = new Place();
        p.title = d.getString("title", "Místo");
        p.subtitle = d.getString("subtitle", "");
        p.source = d.getString("srcSource", "");
        p.id = d.getLong("srcId", 0);
        Vector m = d.getArray("mark");
        p.lon = m.size() >= 2 ? MapyApi.num(m.elementAt(0)) : lon;
        p.lat = m.size() >= 2 ? MapyApi.num(m.elementAt(1)) : lat;
        return p;
    }

    Vector detailPhotos = new Vector();
    Command photosCommand;

    void showDetail(Place p, FrpcStruct d) {
        detailPlace = p;
        final Form f = new Form(p.title.length() > 0 ? p.title : "Detail");
        detailPhotos = d == null ? new Vector() : Photos.gallery(d);
        final String header = d == null ? null : Photos.header(d);
        if (d != null) {
            add(f, null, d.getString("title", p.title));
            add(f, null, d.getString("subtitle", ""));
            add(f, "Adresa", d.getString("address", ""));
            add(f, "Hodnocení", rating(d));
            String desc = d.getString("description", "");
            if (desc.length() > 1500) desc = desc.substring(0, 1500) + "...";
            add(f, null, stripTags(desc));
            Vector kv = d.getArray("keyval");
            for (int i = 0; i < kv.size(); i++) {
                FrpcStruct e = FrpcStruct.structAt(kv, i);
                if (e == null) continue;
                for (int k = 0; k < e.size(); k++) add(f, e.keyAt(k), valueText(e.get(e.keyAt(k))));
            }
        } else {
            add(f, null, p.title);
            add(f, null, p.subtitle);
            add(f, "Data OpenStreetMap", p.tags);
        }
        add(f, "Poloha", Geo.format(p.lat, p.lon));
        f.addCommand(BACK);
        f.addCommand(SHOW);
        f.addCommand(ROUTE_WALK);
        f.addCommand(ROUTE_CAR);
        if (p.osm && d == null) f.addCommand(MAPY_DETAIL);
        photosCommand = null;
        if (detailPhotos.size() > 0) {
            photosCommand = new Command("Fotky (" + detailPhotos.size() + ")", Command.SCREEN, 1);
            f.addCommand(photosCommand);
        }
        if (header != null) {
            // the text first, then the photo on top when it arrives
            new Thread() {
                public void run() {
                    // The Form is changed only on the UI thread (callSerially), and only while it's
                    // still on screen: inserting into it from this thread after the user had gone
                    // back to the map crashed the app (KERN-EXEC 3 in Main, Mapy 3.3).
                    Item it;
                    try {
                        Image im = Photos.load(header, 120, "foto detailu");
                        it = new ImageItem(null, im, Item.LAYOUT_CENTER | Item.LAYOUT_NEWLINE_AFTER, "foto");
                    } catch (Throwable e) {
                        Log.add("detail photo " + header + ": " + e);
                        it = new StringItem("Fotka", "nepodařilo se načíst (" + e + ")");
                    }
                    final Item item = it;
                    display.callSerially(new Runnable() {
                        public void run() {
                            if (display.getCurrent() != f) { Log.add("detail photo: form closed, dropped"); return; }
                            if (item instanceof ImageItem && f.size() > 0) f.insert(0, item); else f.append(item);
                        }
                    });
                }
            }.start();
        }
        f.setCommandListener(this);
        if (!mapOnTop()) {
            Log.add("detail " + p.title + " ready, not shown: another screen is open");
            map.status = "Detail " + p.title + " připraven (Enter)";
            return;
        }
        detailForm = f;
        display.setCurrent(f);
    }

    static void add(Form f, String label, String text) {
        if (text == null || text.length() == 0) return;
        StringItem s = new StringItem(label, text);
        s.setLayout(Item.LAYOUT_NEWLINE_AFTER | Item.LAYOUT_LEFT);
        f.append(s);
    }

    static String valueText(Object v) {
        if (v instanceof String) return (String) v;
        if (v instanceof FrpcStruct) {
            FrpcStruct s = (FrpcStruct) v;
            return s.getString("label", s.getString("value", ""));
        }
        if (v instanceof Vector) {
            Vector a = (Vector) v;
            StringBuffer b = new StringBuffer();
            for (int i = 0; i < a.size(); i++) {
                if (i > 0) b.append(", ");
                b.append(valueText(a.elementAt(i)));
            }
            return b.toString();
        }
        return v == null ? "" : v.toString();
    }

    static String stripTags(String s) {
        StringBuffer b = new StringBuffer(s.length());
        boolean tag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') tag = true;
            else if (c == '>') tag = false;
            else if (!tag) b.append(c);
        }
        return b.toString();
    }

    // ---------------------------------------------------------------- routes

    RouteForm routeForm;

    /** The Odkud / Kam form (keeps its places between uses). */
    void routeForm() {
        if (routeForm == null) routeForm = new RouteForm(this);
        display.setCurrent(routeForm);
    }

    /** Route to a place from a detail: from the GPS position, or (without GPS) from the map cursor. */
    void planRoute(Place to, boolean car) {
        if (routeForm == null) routeForm = new RouteForm(this);
        routeForm.to = to;
        routeForm.car = car;
        Place from = PlacePicker.gpsPlace();
        if (!Gps.instance.hasFix()) {
            map.initCursor();
            from = new Place();
            from.title = "Kurzor na mapě";
            from.lon = Geo.xToLon(map.wx(map.mx), map.zoom);
            from.lat = Geo.yToLat(map.wy(map.my), map.zoom);
            Log.add("route from the map cursor (no GPS fix)");
        }
        planRoute(from, to, car, car && routeForm.noToll, false);
    }

    /** Plans a route; "Moje poloha" resolves to the current GPS fix. Optionally starts navigation. */
    void planRoute(final Place from, final Place to, final boolean car, final boolean noToll, final boolean nav) {
        new Task() {
            String name() { return "Trasa"; }
            void work() throws Exception {
                Gps g = Gps.instance;
                double fl = from.lon, fa = from.lat, tl = to.lon, ta = to.lat;
                if (RouteForm.isGps(from) || RouteForm.isGps(to)) {
                    if (!g.hasFix()) {
                        if (!g.running) g.connect();
                        for (int i = 0; i < 40 && !g.hasFix(); i++) {      // up to 20 s for the first fix
                            map.status = "Trasa: čekám na GPS (" + g.status + ")";
                            map.repaintPanel();
                            Thread.sleep(500);
                        }
                        if (!g.hasFix()) throw new Exception("GPS nemá polohu: " + g.status);
                    }
                    if (RouteForm.isGps(from)) { fl = g.lon; fa = g.lat; }
                    if (RouteForm.isGps(to)) { tl = g.lon; ta = g.lat; }
                }
                Log.add("route " + from.title + " -> " + to.title + (car ? " car" : " foot") + (noToll ? " no toll" : ""));
                Route r = Route.plan(fl, fa, tl, ta, car, noToll);
                Place dest = to;
                if (RouteForm.isGps(to)) { dest = new Place(); dest.title = "Moje poloha"; dest.lon = tl; dest.lat = ta; }
                r.to = dest;
                r.noToll = noToll;
                map.marker = dest;
                if (r.tollNote.length() > 0) map.status = "Trasa " + r.tollNote;
                if (nav) {
                    map.navigating = true;
                    map.follow = true;
                    map.cursorHidden = true;
                }
                map.setRoute(r);
                if (nav) map.centerOnGps();
            }
        }.go();
    }

    /** Recalculation during navigation, from the current GPS position. */
    void reroute() {
        final Route old = map.route;
        if (old == null || old.to == null || busy) return;
        busy = true;
        new Thread() {
            public void run() {
                try {
                    Gps g = Gps.instance;
                    Log.add("off route, recalculating");
                    Route r = Route.plan(g.lon, g.lat, old.to.lon, old.to.lat, old.car, old.noToll);
                    r.noToll = old.noToll;
                    r.to = old.to;
                    map.setRoute(r);
                } catch (Throwable e) {
                    Log.add("reroute: " + e);
                } finally {
                    busy = false;
                }
            }
        }.start();
    }

    // ---------------------------------------------------------------- log, settings

    void showLog() {
        try {
            Form f = new Form("Log");
            String s = Log.text();
            if (s.length() > 4000) s = "...\n" + s.substring(s.length() - 4000);
            f.append(new StringItem(null, s));
            f.addCommand(SEND);
            f.addCommand(BACK);
            f.setCommandListener(this);
            display.setCurrent(f);
        } catch (Throwable e) {
            Log.add("showLog: " + e);
            map.status = "Log nejde zobrazit: " + e + " (Menu: Odeslat log na PC)";
            map.repaint();
        }
    }

    void sendLog() {
        new Task() {
            String name() { return "Odeslání logu"; }
            void work() throws Exception {
                byte[] b = Frpc.utf8Encode(Log.all());
                // through Net like everything else: one connection at a time
                Net.Response r = Net.post("http://" + Settings.pc + "/results?name=mapy", "text/plain; charset=utf-8", b, "log na PC");
                Alert a = new Alert("Log", "Odesláno: HTTP " + r.code + ", " + b.length + " B", null, AlertType.INFO);
                a.setTimeout(3000);
                if (mapOnTop()) display.setCurrent(a, map);
            }
        }.go();
    }

    List layerList;
    int[] layerIds;

    /** Map type chooser (a native List: Up/Down move, Enter picks). */
    void chooseLayer() {
        layerList = new List("Typ mapy", List.IMPLICIT);
        layerIds = new int[Layers.NAMES.length];
        int n = 0;
        for (int i = 0; i < Layers.NAMES.length; i++) {
            if (Layers.needsKey(i) && Settings.mapyKey.length() == 0) continue;    // Mapy.com maps only with a key
            layerIds[n++] = i;
            layerList.append((i == Layers.current() ? "* " : "") + Layers.NAMES[i], null);
            if (i == Layers.current()) layerList.setSelectedIndex(n - 1, true);
        }
        layerList.addCommand(BACK);
        layerList.setCommandListener(this);
        display.setCurrent(layerList);
    }
    void settings() {
        new SettingsScreen(this).show();
    }

    public void commandAction(Command c, Displayable d) {
        if (d == layerList) {
            int sel = layerList.getSelectedIndex();
            int i = sel >= 0 ? layerIds[sel] : -1;
            if (c == List.SELECT_COMMAND && i >= 0) {
                if (Layers.needsKey(i) && Settings.mapyKey.length() == 0) {
                    Alert a = new Alert("Typ mapy", "Mapy Mapy.com potřebují vlastní API klíč: zaregistrujte se zdarma na developer.mapy.com a zadejte klíč v Nastavení.", null, AlertType.INFO);
                    a.setTimeout(Alert.FOREVER);
                    display.setCurrent(a, layerList);
                    return;
                }
                Settings.layer = i;
                Settings.save();
                Log.add("map type " + Layers.NAMES[i]);
                map.layerChanged();
            }
            showMap();
            return;
        }
        if (d == searchBox) {
            if (c == DO_SEARCH) runSearch(searchBox.getString());
            else showMap();
        } else if (d == results) {
            if (c == SHOW) {
                int i = results.getSelectedIndex();
                if (i >= 0 && i < resultPlaces.size()) {
                    map.show((Place) resultPlaces.elementAt(i), true);
                }
                showMap();
            } else showMap();
        } else if (d == detailForm) {
            if (c == SHOW) { map.show(detailPlace, !detailPlace.osm); showMap(); }
            else if (c == MAPY_DETAIL) detail(detailPlace);
            else if (c == ROUTE_WALK) planRoute(detailPlace, false);
            else if (c == ROUTE_CAR) planRoute(detailPlace, true);
            else if (c == photosCommand) display.setCurrent(new PhotoCanvas(this, detailForm, detailPlace.title, detailPhotos));
            else showMap();
        } else if (c == SEND) {
            sendLog();
        } else {
            showMap();
        }
    }
}
