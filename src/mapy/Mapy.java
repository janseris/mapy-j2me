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
        display.setCurrent(map);
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
        if (!mapOnTop()) { map.status = what + ": chyba " + e.getMessage(); return; }
        Alert a = new Alert("Chyba", what + ":\n" + e.getMessage(), null, AlertType.ERROR);
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
                    if (u != null) map.setPreview(p, Photos.load(Photos.sized(u, 80), "náhled"), rating);
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
                    try {
                        Image im = Photos.load(Photos.sized(header, 120), "foto detailu");
                        ImageItem it = new ImageItem(null, im, Item.LAYOUT_CENTER | Item.LAYOUT_NEWLINE_AFTER, "foto");
                        if (f.size() > 0) f.insert(0, it); else f.append(it);
                    } catch (Throwable e) {
                        Log.add("detail photo: " + e);
                    }
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

    /** Route to a place: from the GPS position, or (without GPS) from the map cursor. */
    void planRoute(final Place to, final boolean car) {
        new Task() {
            String name() { return "Trasa"; }
            void work() throws Exception {
                double fl, fa;
                Gps g = Gps.instance;
                if (g.hasFix()) { fl = g.lon; fa = g.lat; }
                else {
                    map.initCursor();
                    fl = Geo.xToLon(map.wx(map.mx), map.zoom);
                    fa = Geo.yToLat(map.wy(map.my), map.zoom);
                    Log.add("route from the map cursor (no GPS fix)");
                }
                Route r = Route.plan(fl, fa, to.lon, to.lat, car);
                r.to = to;
                map.marker = to;
                map.setRoute(r);
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
                    Route r = Route.plan(g.lon, g.lat, old.to.lon, old.to.lat, old.car);
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
        Form f = new Form("Log");
        String s = Log.text();
        if (s.length() > 6000) s = "...\n" + s.substring(s.length() - 6000);
        f.append(s);
        f.addCommand(BACK);
        f.addCommand(SEND);
        f.setCommandListener(this);
        display.setCurrent(f);
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

    TextField fPc, fUa, fBt;
    Form settingsForm;
    static final Command BT_SEARCH = new Command("Hledat GPS zařízení", Command.SCREEN, 2);
    static final Command BT_DEFAULT = new Command("GPS: výchozí Android", Command.SCREEN, 4);
    ChoiceGroup fPanel, fCache, fPreview, fFollow;
    static final int[] CACHE_MB = { 0, 4, 8, 16, 32, 48 };
    static final Command CLEAR_CACHE = new Command("Smazat mezipaměť", Command.SCREEN, 3);
    static final int[] PANEL_WIDTHS = { 110, 130, 150, 180, 210, 240 };

    void settings() {
        Form f = new Form("Nastavení");
        settingsForm = f;
        fPc = new TextField("PC pro log (adresa:port)", Settings.pc, 64, TextField.ANY);
        fUa = new TextField("User-Agent", Settings.userAgent, 200, TextField.ANY);
        String[] labels = new String[PANEL_WIDTHS.length];
        int sel = 2;
        for (int i = 0; i < labels.length; i++) {
            labels[i] = PANEL_WIDTHS[i] + " px";
            if (PANEL_WIDTHS[i] == Settings.panelWidth) sel = i;
        }
        fPanel = new ChoiceGroup("Šířka levého panelu", Choice.POPUP, labels, null);
        fPanel.setSelectedIndex(sel, true);
        f.append(fPanel);
        String[] cl = new String[CACHE_MB.length];
        int cs = 3;
        for (int i = 0; i < cl.length; i++) {
            cl[i] = CACHE_MB[i] == 0 ? "vypnuto" : CACHE_MB[i] + " MB";
            if (CACHE_MB[i] == Settings.cacheMB) cs = i;
        }
        fCache = new ChoiceGroup("Mezipaměť dlaždic a fotek v telefonu (" + DiskCache.summary() + ")", Choice.POPUP, cl, null);
        fCache.setSelectedIndex(cs, true);
        f.append(fCache);
        fPreview = new ChoiceGroup("Náhled při najetí kurzorem (fotka, hodnocení)", Choice.POPUP, new String[] { "zapnuto", "vypnuto" }, null);
        fPreview.setSelectedIndex(Settings.preview ? 0 : 1, true);
        f.append(fPreview);
        fBt = new TextField("Bluetooth GPS: adresa (Menu: Hledat GPS zařízení)", Gps.pretty(Settings.btAddress), 17, TextField.ANY);
        f.append(fBt);
        String[] fl = { "zapnuto", "vypnuto" };
        fFollow = new ChoiceGroup("Mapa sleduje polohu GPS", Choice.POPUP, fl, null);
        fFollow.setSelectedIndex(Settings.follow ? 0 : 1, true);
        f.append(fFollow);
        f.append(fPc);
        f.append(fUa);
        f.append(new StringItem(null, "Mapa, body zájmu a trasy: © OpenStreetMap contributors (openstreetmap.org/copyright), trasy: OSRM na serveru FOSSGIS (routing.openstreetmap.de). Chyba v mapě? openstreetmap.org/fixthemap. Hledání, detaily, fotky a ikony: Mapy.com."));
        f.addCommand(SAVE);
        f.addCommand(BT_SEARCH);
        f.addCommand(CLEAR_CACHE);
        f.addCommand(BT_DEFAULT);
        f.addCommand(BACK);
        f.setCommandListener(this);
        display.setCurrent(f);
    }

    public void commandAction(Command c, Displayable d) {
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
        } else if (c == BT_SEARCH) {
            new BtSearch(display, settingsForm, new BtSearch.Picked() {
                public void picked(String a) { fBt.setString(Gps.pretty(a)); }
            }).start();
        } else if (c == BT_DEFAULT) {
            fBt.setString(Gps.pretty(Settings.DEFAULT_BT));
        } else if (c == SAVE) {
            Settings.pc = fPc.getString().trim();
            String ua = fUa.getString().trim();
            Settings.userAgent = ua.length() > 0 ? ua : Settings.DEFAULT_UA;
            Settings.panelWidth = PANEL_WIDTHS[fPanel.getSelectedIndex()];
            String bt = Gps.clean(fBt.getString());
            if (!bt.equals(Settings.btAddress) && Gps.instance.running) Gps.instance.disconnect();
            Settings.btAddress = bt.length() == 12 ? bt : Settings.DEFAULT_BT;
            Settings.follow = fFollow.getSelectedIndex() == 0;
            map.follow = Settings.follow;
            Settings.cacheMB = CACHE_MB[fCache.getSelectedIndex()];
            Settings.preview = fPreview.getSelectedIndex() == 0;
            Settings.save();
            map.repaint();
            showMap();
        } else if (c == CLEAR_CACHE) {
            DiskCache.clear();
            Photos.images.clear();
            details.clear();
            Alert a = new Alert("Mezipaměť", "Smazáno.", null, AlertType.INFO);
            a.setTimeout(2000);
            display.setCurrent(a, map);
        } else if (c == SEND) {
            sendLog();
        } else {
            showMap();
        }
    }
}
