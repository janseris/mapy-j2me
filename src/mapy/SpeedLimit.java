package mapy;

import java.util.Hashtable;
import java.util.Vector;

/**
 * The speed limit of the road we're on, from OpenStreetMap (maxspeed tags) through Overpass.
 * Light use: a small query (roads within 30 m, with their geometry) only when we leave the
 * roads already known, at most every 10 s and only while moving; the known roads are kept, so
 * driving along a road needs no further requests.
 */
public class SpeedLimit implements Runnable {
    public static final SpeedLimit instance = new SpeedLimit();

    static final String ROADS = "^(motorway|trunk|primary|secondary|tertiary|unclassified|residential|living_street|service|road|"
        + "motorway_link|trunk_link|primary_link|secondary_link|tertiary_link)$";

    /** km/h; -1 = unknown. */
    public volatile int limit = -1;
    public volatile String road = "";
    public volatile boolean implied;      // from the road type, not a maxspeed tag

    static class Way {
        long id;
        double[] lat, lon;
        String name, highway;
        int fwd, bwd;           // limit in / against the way's direction
        boolean implied;
    }

    final Vector ways = new Vector();

    /** Speed cameras and hazards (accident black spots...) from OSM near us: double[] { lat, lon, kind, maxspeed }. */
    final Vector hazards = new Vector();
    static final int CAMERA = 0, BLACKSPOT = 1, OTHER = 2;
    long lastHazardQuery;
    double hLat, hLon;
    /** The warning to show, "" = none; kind of it. */
    public volatile String warning = "";
    public volatile int warningKind = -1;
    long lastQuery;
    double qLat, qLon;
    boolean started;

    public synchronized void start() {
        if (started) return;
        started = true;
        new Thread(this).start();
    }

    public void run() {
        while (true) {
            try { Thread.sleep(2000); } catch (InterruptedException e) {}
            try { step(); } catch (Throwable e) { Log.add("speed limit: " + e); }
        }
    }

    void step() {
        Gps g = Gps.instance;
        if (!Settings.speedLimits || !g.hasFix()) { limit = -1; return; }
        boolean moving = g.speedKmh >= 8;
        double lat = g.lat, lon = g.lon, course = g.course;
        long now = System.currentTimeMillis();
        hazardsStep(lat, lon, g.speedKmh, course, now);
        Object[] m = match(lat, lon, moving ? course : -1, 20);
        if (m == null && moving && now - lastQuery > 10000 && Geo.distance(lon, lat, qLon, qLat) > 40) {
            lastQuery = now;
            qLat = lat;
            qLon = lon;
            query(lat, lon);
            m = match(lat, lon, course, 25);
        }
        if (m == null) {
            if (moving) { limit = -1; road = ""; }       // standing (lights): keep the last value
            return;
        }
        Way w = (Way) m[0];
        boolean forward = ((Boolean) m[1]).booleanValue();
        int l = forward ? w.fwd : w.bwd;
        if (l != limit || !w.name.equals(road)) Log.add("speed limit " + l + " on " + w.name + " (" + w.highway + (w.implied ? ", implied" : "") + ")");
        limit = l;
        road = w.name;
        implied = w.implied;
    }

    /** Cameras / hazards: a 2 km query when we've driven 1 km from the last one; then a warning 500 m ahead. */
    void hazardsStep(double lat, double lon, double speed, double course, long now) {
        if (speed >= 15 && (lastHazardQuery == 0 || Geo.distance(lon, lat, hLon, hLat) > 1000) && now - lastHazardQuery > 30000) {
            lastHazardQuery = now;
            hLat = lat;
            hLon = lon;
            queryHazards(lat, lon);
        }
        String w = "";
        int wk = -1;
        double bd = 500;
        synchronized (hazards) {
            for (int i = 0; i < hazards.size(); i++) {
                double[] h = (double[]) hazards.elementAt(i);
                double d = Geo.distance(lon, lat, h[1], h[0]);
                if (d >= bd) continue;
                if (speed >= 8 && d > 30) {                 // only what's ahead of us
                    double kx = Math.cos(Math.toRadians(lat)) * 111320;
                    double b = Geo.bearing((h[1] - lon) * kx, (h[0] - lat) * 110540);
                    double diff = Math.abs(b - course) % 360;
                    if (diff > 180) diff = 360 - diff;
                    if (diff > 40) continue;
                }
                bd = d;
                wk = (int) h[2];
                String dist = d < 30 ? "zde" : "za " + ((int) (d / 10) * 10) + " m";
                w = wk == CAMERA ? "Radar " + dist + (h[3] > 0 ? " (" + (int) h[3] + ")" : "")
                    : wk == BLACKSPOT ? "Úsek častých nehod " + dist : "Pozor " + dist;
            }
        }
        if (!w.equals(warning) && w.length() > 0 && warning.length() == 0) Log.add("warning: " + w);
        warning = w;
        warningKind = wk;
    }

    void queryHazards(double lat, double lon) {
        String a = "(around:2000," + Geo.fmt(lat, 5) + "," + Geo.fmt(lon, 5) + ")";
        String q = "[out:csv(::lat,::lon,highway,hazard,maxspeed;false;\"|\")][timeout:10];(node" + a + "[highway=speed_camera];node" + a + "[hazard];);out 100;";
        try {
            Net.Response r = Overpass.query(q, "radary a nebezpečná místa (OSM)");
            if (r.code != 200) { Log.add("hazards: HTTP " + r.code); return; }
            String text = Frpc.utf8Decode(r.body, 0, r.body.length);
            Vector v = new Vector();
            int start = 0;
            while (start < text.length()) {
                int end = text.indexOf('\n', start);
                if (end < 0) end = text.length();
                String[] f = Overpass.split(text.substring(start, end), '|');
                start = end + 1;
                if (f.length < 4) continue;
                try {
                    double kind = f[2].equals("speed_camera") ? CAMERA
                        : (f[3].indexOf("accident") >= 0 || f[3].indexOf("blackspot") >= 0) ? BLACKSPOT : OTHER;
                    v.addElement(new double[] { Double.parseDouble(f[0]), Double.parseDouble(f[1]), kind, f.length > 4 ? parse(f[4]) : -1 });
                } catch (Throwable e) {}
            }
            synchronized (hazards) {
                hazards.removeAllElements();
                for (int i = 0; i < v.size(); i++) hazards.addElement(v.elementAt(i));
            }
            Log.add("hazards: " + v.size() + " within 2 km");
        } catch (Throwable e) {
            Log.add("hazards: " + e);
        }
    }

    /** Nearest known road within maxDist m (aligned with the course when course >= 0): { Way, Boolean forward }. */
    Object[] match(double lat, double lon, double course, double maxDist) {
        double kx = Math.cos(Math.toRadians(lat)) * 111320, ky = 110540;
        Way best = null;
        boolean bestFwd = true;
        double bd = maxDist;
        synchronized (ways) {
            for (int i = 0; i < ways.size(); i++) {
                Way w = (Way) ways.elementAt(i);
                for (int j = 0; j + 1 < w.lat.length; j++) {
                    double ax = (w.lon[j] - lon) * kx, ay = (w.lat[j] - lat) * ky;
                    double bx = (w.lon[j + 1] - lon) * kx, by = (w.lat[j + 1] - lat) * ky;
                    double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
                    double t = len2 > 0 ? -(ax * dx + ay * dy) / len2 : 0;
                    if (t < 0) t = 0;
                    if (t > 1) t = 1;
                    double px = ax + t * dx, py = ay + t * dy, d = Math.sqrt(px * px + py * py);
                    boolean fwd = true;
                    if (course >= 0 && len2 > 1) {
                        double seg = Geo.bearing(dx, dy);
                        double diff = Math.abs(seg - course) % 360;
                        if (diff > 180) diff = 360 - diff;
                        if (diff > 50 && diff < 130) continue;          // crossing road, not ours
                        fwd = diff <= 90;
                    }
                    if (d < bd) { bd = d; best = w; bestFwd = fwd; }
                }
            }
        }
        return best == null ? null : new Object[] { best, new Boolean(bestFwd) };
    }

    void query(double lat, double lon) {
        String q = "[out:json][timeout:10];way(around:30," + Geo.fmt(lat, 6) + "," + Geo.fmt(lon, 6) + ")[highway~\"" + ROADS + "\"];out tags geom;";
        try {
            Net.Response r = Overpass.query(q, "rychlostní limit (OSM)");
            if (r.code != 200) { Log.add("speed limit query: HTTP " + r.code); return; }
            Vector el = Json.arr(Json.parse(Frpc.utf8Decode(r.body, 0, r.body.length)), "elements");
            int added = 0;
            for (int i = 0; i < el.size(); i++) {
                Object e = el.elementAt(i);
                Vector geom = Json.arr(e, "geometry");
                if (geom.size() < 2) continue;
                Way w = new Way();
                w.id = (long) Json.num(e, "id");
                w.lat = new double[geom.size()];
                w.lon = new double[geom.size()];
                for (int k = 0; k < geom.size(); k++) {
                    w.lat[k] = Json.num(geom.elementAt(k), "lat");
                    w.lon[k] = Json.num(geom.elementAt(k), "lon");
                }
                Hashtable t = Json.obj(e, "tags");
                w.highway = tag(t, "highway");
                w.name = tag(t, "name");
                if (w.name.length() == 0) w.name = tag(t, "ref");
                int all = parse(tag(t, "maxspeed"));
                if (all < 0) {
                    all = parse(tag(t, "maxspeed:type"));
                    if (all < 0) all = parse(tag(t, "source:maxspeed"));
                    if (all < 0) {
                        if (w.highway.equals("motorway")) all = 130;
                        else if (w.highway.equals("living_street")) all = 20;
                        w.implied = all > 0;
                    }
                }
                int f = parse(tag(t, "maxspeed:forward")), b = parse(tag(t, "maxspeed:backward"));
                w.fwd = f > 0 ? f : all;
                w.bwd = b > 0 ? b : all;
                synchronized (ways) {
                    for (int k = ways.size() - 1; k >= 0; k--) if (((Way) ways.elementAt(k)).id == w.id) ways.removeElementAt(k);
                    ways.addElement(w);
                    while (ways.size() > 40) ways.removeElementAt(0);
                }
                added++;
            }
            Log.add("speed limit query: " + added + " roads, " + r.body.length + " B");
        } catch (Throwable e) {
            Log.add("speed limit query: " + e);
        }
    }

    static String tag(Hashtable t, String k) {
        Object v = t == null ? null : t.get(k);
        return v instanceof String ? (String) v : "";
    }

    /** "50", "30 mph", "CZ:urban"... in km/h; -1 when unknown or "none". */
    static int parse(String v) {
        if (v == null || v.length() == 0) return -1;
        if (v.equals("CZ:urban") || v.endsWith(":urban")) return 50;
        if (v.equals("CZ:rural") || v.endsWith(":rural")) return 90;
        if (v.endsWith(":motorway")) return 130;
        if (v.endsWith(":trunk")) return 110;
        if (v.endsWith(":living_street") || v.endsWith(":zone20")) return 20;
        if (v.endsWith(":zone30")) return 30;
        if (v.equals("walk")) return 6;
        int i = 0;
        while (i < v.length() && Character.isDigit(v.charAt(i))) i++;
        if (i == 0) return -1;
        int n = Integer.parseInt(v.substring(0, i));
        if (v.indexOf("mph") > 0) n = (int) (n * 1.609 + 0.5);
        return n;
    }
}
