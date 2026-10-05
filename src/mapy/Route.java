package mapy;

import java.io.IOException;
import java.util.Hashtable;
import java.util.Vector;

/**
 * A route from the FOSSGIS OSRM server (routing.openstreetmap.de, OpenStreetMap data):
 * full line plus turn steps, for walking ("routed-foot") or driving ("routed-car").
 * Usage policy: max 1 request per second, a valid User-Agent, attribution, no heavy use.
 */
public class Route {
    static final String BASE = "https://routing.openstreetmap.de/";

    public static class Step {
        public double lon, lat, distance, duration;
        public int index;                  // nearest point of the line
        public String type = "", modifier = "", name = "", text = "";
        public int exit;
    }

    public boolean car;
    public double[] lon, lat;              // the line
    public double[] along;                 // metres from the start to each point
    public double distance, duration;      // m, s
    public Vector steps = new Vector();    // Step
    public Place to;

    /** What the toll avoidance actually did ("" when not asked for). */
    public String tollNote = "";
    public boolean noToll;

    public static Route plan(double fromLon, double fromLat, double toLon, double toLat, boolean car) throws IOException {
        return plan(fromLon, fromLat, toLon, toLat, car, false);
    }

    /**
     * noToll: avoid paid roads. In Czechia that's the motorways (vignette), elsewhere toll=yes roads;
     * OSRM's "exclude" works only for classes the server's profile defines, so we try
     * motorway+toll, then motorway, then toll, and say what was used.
     */
    public static Route plan(double fromLon, double fromLat, double toLon, double toLat, boolean car, boolean noToll) throws IOException {
        String url = BASE + (car ? "routed-car" : "routed-foot") + "/route/v1/driving/"
            + Geo.fmt(fromLon, 6) + "," + Geo.fmt(fromLat, 6) + ";" + Geo.fmt(toLon, 6) + "," + Geo.fmt(toLat, 6)
            + "?overview=full&geometries=polyline&steps=true";
        Net.Response r = null;
        String note = "";
        if (car && noToll) {
            String[] ex = { "motorway,toll", "motorway", "toll" };
            String[] names = { "bez dálnic a placených úseků", "bez dálnic (placené známkou)", "bez mýtných úseků (dálnice možné)" };
            for (int i = 0; i < ex.length && (r == null || r.code != 200); i++) {
                r = Net.get(url + "&exclude=" + Net.encode(ex[i]), "trasa autem bez placených");
                Log.add("route exclude=" + ex[i] + ": HTTP " + r.code);
                if (r.code == 200) note = names[i];
            }
            if (r.code != 200) note = "server neumí vyhnout se placeným úsekům: trasa může vést po dálnici";
        }
        if (r == null || r.code != 200) r = Net.get(url, car ? "trasa autem" : "trasa pěšky");
        if (r.code != 200) throw new IOException("trasa: HTTP " + r.code);
        Object o = Json.parse(Frpc.utf8Decode(r.body, 0, r.body.length));
        if (!"Ok".equals(Json.str(o, "code"))) throw new IOException("trasa: " + Json.str(o, "code") + " " + Json.str(o, "message"));
        Vector routes = Json.arr(o, "routes");
        if (routes.size() == 0) throw new IOException("trasa nenalezena");
        Hashtable rt = (Hashtable) routes.elementAt(0);
        Route route = new Route();
        route.car = car;
        route.tollNote = note;
        route.distance = Json.num(rt, "distance");
        route.duration = Json.num(rt, "duration");
        route.decode(Json.str(rt, "geometry"));
        Vector legs = Json.arr(rt, "legs");
        int from = 0;
        for (int l = 0; l < legs.size(); l++) {
            Vector st = Json.arr(legs.elementAt(l), "steps");
            for (int i = 0; i < st.size(); i++) {
                Object so = st.elementAt(i);
                Hashtable m = Json.obj(so, "maneuver");
                Step s = new Step();
                s.distance = Json.num(so, "distance");
                s.duration = Json.num(so, "duration");
                s.name = Json.str(so, "name");
                s.type = Json.str(m, "type");
                s.modifier = Json.str(m, "modifier");
                s.exit = (int) Json.num(m, "exit");
                Vector loc = Json.arr(m, "location");
                if (loc.size() >= 2) {
                    s.lon = ((Double) loc.elementAt(0)).doubleValue();
                    s.lat = ((Double) loc.elementAt(1)).doubleValue();
                }
                s.index = route.nearestIndex(s.lon, s.lat, from);
                from = s.index;
                s.text = text(s, car);
                route.steps.addElement(s);
            }
        }
        Log.add("route " + (car ? "car" : "foot") + ": " + (int) route.distance + " m, " + (int) route.duration + " s, "
            + route.lon.length + " points, " + route.steps.size() + " steps, " + r.body.length + " B");
        return route;
    }

    /** Google encoded polyline, precision 5. */
    void decode(String p) {
        Vector la = new Vector(), lo = new Vector();
        int i = 0, lat = 0, lng = 0;
        while (i < p.length()) {
            int[] r = next(p, i); lat += r[0]; i = r[1];
            r = next(p, i); lng += r[0]; i = r[1];
            la.addElement(new Double(lat / 1e5));
            lo.addElement(new Double(lng / 1e5));
        }
        int n = la.size();
        lon = new double[n]; this.lat = new double[n]; along = new double[n];
        for (int k = 0; k < n; k++) {
            this.lat[k] = ((Double) la.elementAt(k)).doubleValue();
            lon[k] = ((Double) lo.elementAt(k)).doubleValue();
            if (k > 0) along[k] = along[k - 1] + Geo.distance(lon[k - 1], this.lat[k - 1], lon[k], this.lat[k]);
        }
    }

    static int[] next(String p, int i) {
        int result = 0, shift = 0, b;
        do {
            b = p.charAt(i++) - 63;
            result |= (b & 0x1f) << shift;
            shift += 5;
        } while (b >= 0x20);
        return new int[] { (result & 1) != 0 ? ~(result >> 1) : (result >> 1), i };
    }

    int nearestIndex(double x, double y, int from) {
        int best = from;
        double bd = Double.MAX_VALUE;
        for (int k = from; k < lon.length; k++) {
            double d = Geo.distance(x, y, lon[k], lat[k]);
            if (d < bd) { bd = d; best = k; }
            if (d < 1) break;
        }
        return best;
    }

    /** Where a position is on the route: { distance from the line (m), metres along the route }. */
    public double[] locate(double x, double y) {
        double best = Double.MAX_VALUE, at = 0;
        double cosLat = Math.cos(Math.toRadians(y)), k = 111320.0;
        for (int i = 0; i + 1 < lon.length; i++) {
            // project onto segment i..i+1 in a local flat frame (metres)
            double ax = (lon[i] - x) * k * cosLat, ay = (lat[i] - y) * k;
            double bx = (lon[i + 1] - x) * k * cosLat, by = (lat[i + 1] - y) * k;
            double dx = bx - ax, dy = by - ay, len2 = dx * dx + dy * dy;
            double t = len2 == 0 ? 0 : -(ax * dx + ay * dy) / len2;
            if (t < 0) t = 0;
            if (t > 1) t = 1;
            double px = ax + t * dx, py = ay + t * dy, d = Math.sqrt(px * px + py * py);
            if (d < best) { best = d; at = along[i] + t * (along[i + 1] - along[i]); }
        }
        return new double[] { best, at };
    }

    /** The next step after position "at" metres along the route (or the last one). */
    public Step nextStep(double at) {
        for (int i = 0; i < steps.size(); i++) {
            Step s = (Step) steps.elementAt(i);
            if (along[s.index] > at + 5) return s;
        }
        return steps.size() > 0 ? (Step) steps.elementAt(steps.size() - 1) : null;
    }

    static String dir(String m) {
        if (m.equals("left")) return "vlevo";
        if (m.equals("right")) return "vpravo";
        if (m.equals("slight left")) return "mírně vlevo";
        if (m.equals("slight right")) return "mírně vpravo";
        if (m.equals("sharp left")) return "ostře vlevo";
        if (m.equals("sharp right")) return "ostře vpravo";
        if (m.equals("uturn")) return "otočte se";
        return "rovně";
    }

    /** Czech instruction from an OSRM step. */
    static String text(Step s, boolean car) {
        String on = s.name.length() > 0 ? " na " + s.name : "";
        String t = s.type;
        if (t.equals("depart")) return (car ? "Vyjeďte" : "Vyjděte") + (s.name.length() > 0 ? " po " + s.name : "");
        if (t.equals("arrive")) return "Cíl";
        if (t.equals("roundabout") || t.equals("rotary") || t.equals("roundabout turn"))
            return "Na kruhovém objezdu " + (s.exit > 0 ? s.exit + ". výjezdem" : "vyjeďte") + on;
        if (t.equals("exit roundabout") || t.equals("exit rotary")) return "Vyjeďte z kruhového objezdu" + on;
        if (t.equals("merge")) return "Připojte se " + dir(s.modifier) + on;
        if (t.equals("on ramp")) return "Najeďte " + dir(s.modifier) + on;
        if (t.equals("off ramp")) return "Sjeďte " + dir(s.modifier) + on;
        if (t.equals("fork")) return "Na rozcestí " + dir(s.modifier) + on;
        if (t.equals("end of road")) return "Na konci silnice " + dir(s.modifier) + on;
        if (s.modifier.equals("straight")) return "Pokračujte rovně" + on;
        if (s.modifier.equals("uturn")) return "Otočte se" + on;
        if (t.equals("new name") || t.equals("continue")) return "Pokračujte" + (s.modifier.length() > 0 && !s.modifier.equals("straight") ? " " + dir(s.modifier) : "") + on;
        return "Odbočte " + dir(s.modifier) + on;
    }

    public static String km(double m) {
        if (m < 1000) return ((int) (m / 10) * 10) + " m";
        return Geo.fmt(m / 1000, m < 10000 ? 1 : 0) + " km";
    }

    public static String time(double s) {
        int min = (int) (s / 60 + 0.5);
        if (min < 60) return min + " min";
        return (min / 60) + " h " + (min % 60) + " min";
    }
}
