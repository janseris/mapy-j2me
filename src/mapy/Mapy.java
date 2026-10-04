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
        Settings.load();
        Log.add("start: " + System.getProperty("microedition.platform") + ", view " + Geo.format(Settings.lat, Settings.lon) + " z" + Settings.zoom);
        map = new MapCanvas(this);
        display.setCurrent(map);
    }

    protected void pauseApp() {}

    protected void destroyApp(boolean u) {
        Settings.save();
    }

    void exit() {
        Settings.save();
        notifyDestroyed();
    }

    void showMap() {
        display.setCurrent(map);
    }

    void error(String what, Throwable e) {
        Log.add(what + ": " + e);
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
                display.setCurrent(results);
            }
        }.go();
    }

    // ---------------------------------------------------------------- detail

    void detail(final Place p) {
        if (p.osm) {
            // OSM object: its tags straight away; Mapy.com detail on request
            showDetail(p, null);
            return;
        }
        new Task() {
            String name() { return "Detail"; }
            void work() throws Exception {
                showDetail(p, MapyApi.detail(p.source, p.id));
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

    /** OSM POI -> the same place in Mapy.com: search its name near it, take the nearest hit. */
    void mapyDetailFor(final Place osm) {
        new Task() {
            String name() { return "Detail z Mapy.com"; }
            void work() throws Exception {
                double d = 0.002;
                Vector r = MapyApi.suggest(osm.title, osm.lon, osm.lat, new double[] { osm.lon - d, osm.lat - d, osm.lon + d, osm.lat + d }, 17);
                Place best = null;
                double bd = 300;    // metres
                for (int i = 0; i < r.size(); i++) {
                    Place c = (Place) r.elementAt(i);
                    double m = Geo.distance(osm.lon, osm.lat, c.lon, c.lat);
                    if (m < bd && c.source.length() > 0) { bd = m; best = c; }
                }
                if (best == null) {
                    // nothing by name: what Mapy.com has at that spot
                    FrpcStruct det = MapyApi.detailAt(osm.lon, osm.lat, 18);
                    showDetail(placeOf(det, osm.lon, osm.lat), det);
                    return;
                }
                Log.add("mapy match for " + osm.title + ": " + best.source + "/" + best.id + " (" + (int) bd + " m)");
                showDetail(best, MapyApi.detail(best.source, best.id));
            }
        }.go();
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

    void showDetail(Place p, FrpcStruct d) {
        detailPlace = p;
        Form f = new Form(p.title.length() > 0 ? p.title : "Detail");
        if (d != null) {
            add(f, null, d.getString("title", p.title));
            add(f, null, d.getString("subtitle", ""));
            add(f, "Adresa", d.getString("address", ""));
            FrpcStruct rv = d.getStruct("review");
            if (rv != null && rv.getDouble("review_rating_stars") != null) {
                add(f, "Hodnocení", Geo.fmt(rv.getDouble("review_rating_stars").doubleValue(), 1) + " z 5 (" + rv.getInt("total", 0) + " hodnocení)");
            }
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
        if (p.osm) f.addCommand(MAPY_DETAIL);
        f.setCommandListener(this);
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
                byte[] b = Frpc.utf8Encode(Log.text());
                // through Net like everything else: one connection at a time
                Net.Response r = Net.post("http://" + Settings.pc + "/results?name=mapy", "text/plain; charset=utf-8", b, "log na PC");
                Alert a = new Alert("Log", "Odesláno: HTTP " + r.code + ", " + b.length + " B", null, AlertType.INFO);
                a.setTimeout(3000);
                display.setCurrent(a, map);
            }
        }.go();
    }

    TextField fPc, fUa;
    ChoiceGroup fPanel;
    static final int[] PANEL_WIDTHS = { 110, 130, 150, 180, 210, 240 };

    void settings() {
        Form f = new Form("Nastavení");
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
        f.append(fPc);
        f.append(fUa);
        f.append(new StringItem(null, "Mapa a body zájmu: © OpenStreetMap contributors (openstreetmap.org/copyright). Hledání a detaily: Mapy.com."));
        f.addCommand(SAVE);
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
            else if (c == MAPY_DETAIL) mapyDetailFor(detailPlace);
            else showMap();
        } else if (c == SAVE) {
            Settings.pc = fPc.getString().trim();
            String ua = fUa.getString().trim();
            Settings.userAgent = ua.length() > 0 ? ua : Settings.DEFAULT_UA;
            Settings.panelWidth = PANEL_WIDTHS[fPanel.getSelectedIndex()];
            Settings.save();
            map.repaint();
            showMap();
        } else if (c == SEND) {
            sendLog();
        } else {
            showMap();
        }
    }
}
