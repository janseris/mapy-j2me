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
    /** "Mapy.com" or "OSRM". */
    public String source = "OSRM";

    public static Route plan(double fromLon, double fromLat, double toLon, double toLat, boolean car) throws IOException {
        return plan(fromLon, fromLat, toLon, toLat, car, false);
    }

    /**
     * noToll: avoid paid roads. In Czechia that's the motorways (vignette), elsewhere toll=yes roads;
     * OSRM's "exclude" works only for classes the server's profile defines, so we try
     * motorway+toll, then motorway, then toll, and say what was used.
     */
    public static Route plan(double fromLon, double fromLat, double toLon, double toLat, boolean car, boolean noToll) throws IOException {
        // with the user's own Mapy.com API key: Mapy.com's routing (current traffic for cars in Czechia);
        // if that fails for any reason, the OSRM route below as before
        if (Settings.mapyKey.length() > 0) {
            try {
                return mapy(fromLon, fromLat, toLon, toLat, car, noToll);
            } catch (Throwable e) {
                Log.add("Mapy.com route failed (" + e + "), trying OSRM");
            }
        }
        return osrm(fromLon, fromLat, toLon, toLat, car, noToll);
    }

    /**
     * Mapy.com REST API routing (developer.mapy.com, 4 credits per route): GET api.mapy.com/v1/routing/route
     * with start/end "lon,lat", routeType car_fast_traffic / foot_fast, avoidToll, format=geojson.
     * The answer has length (m), duration (s) and the line, but no turn instructions: those are
     * made from the line's bends (turns without street names).
     */
    static Route mapy(double fromLon, double fromLat, double toLon, double toLat, boolean car, boolean noToll) throws IOException {
        String url = "https://api.mapy.com/v1/routing/route?apikey=" + Net.encode(Settings.mapyKey) + "&lang=en"
            + "&start=" + Geo.fmt(fromLon, 6) + "," + Geo.fmt(fromLat, 6) + "&end=" + Geo.fmt(toLon, 6) + "," + Geo.fmt(toLat, 6)
            + "&routeType=" + (car ? "car_fast_traffic" : "foot_fast") + "&format=geojson" + (car && noToll ? "&avoidToll=true" : "");
        Net.Response r = Net.get(url, car ? "car route (Mapy.com)" : "walking route (Mapy.com)");
        String text = Frpc.utf8Decode(r.body, 0, r.body.length);
        if (r.code != 200) {
            Log.add("Mapy.com route HTTP " + r.code + ": " + (text.length() > 300 ? text.substring(0, 300) : text));
            throw new IOException("HTTP " + r.code);
        }
        Object o = Json.parse(text);
        Hashtable g = Json.obj(o, "geometry");
        if (g != null && Json.obj(g, "geometry") != null) g = Json.obj(g, "geometry");     // a GeoJSON Feature
        Vector c = Json.arr(g, "coordinates");
        if (c.size() < 2) {
            Log.add("Mapy.com route without a line: " + (text.length() > 300 ? text.substring(0, 300) : text));
            throw new IOException("no line in the answer");
        }
        Route route = new Route();
        route.source = "Mapy.com";
        route.car = car;
        route.distance = Json.num(o, "length");
        route.duration = Json.num(o, "duration");
        if (car && noToll) route.tollNote = "avoiding toll roads (Mapy.com)";
        int n = c.size();
        route.lon = new double[n];
        route.lat = new double[n];
        route.along = new double[n];
        for (int k = 0; k < n; k++) {
            Vector p = (Vector) c.elementAt(k);
            route.lon[k] = ((Double) p.elementAt(0)).doubleValue();
            route.lat[k] = ((Double) p.elementAt(1)).doubleValue();
            if (k > 0) route.along[k] = route.along[k - 1] + Geo.distance(route.lon[k - 1], route.lat[k - 1], route.lon[k], route.lat[k]);
        }
        if (route.distance <= 0) route.distance = route.along[n - 1];
        route.bends();
        Log.add("route " + (car ? "car" : "foot") + " (Mapy.com" + (car ? ", traffic" : "") + "): " + (int) route.distance + " m, "
            + (int) route.duration + " s, " + n + " points, " + route.steps.size() + " steps from bends, " + r.body.length + " B");
        return route;
    }

    /** Turn steps from the line itself: where the direction changes by 30+ degrees (measured 20 m before and after). */
    void bends() {
        int n = lon.length;
        Step d = new Step();
        d.type = "depart";
        d.lon = lon[0]; d.lat = lat[0];
        d.text = text(d, car);
        steps.addElement(d);
        double lastAt = -1000;
        for (int i = 1; i + 1 < n; i++) {
            int a = i, b = i;
            while (a > 0 && along[i] - along[a] < 20) a--;
            while (b < n - 1 && along[b] - along[i] < 20) b++;
            if (a == i || b == i) continue;
            double in = bearingOf(a, i), out = bearingOf(i, b);
            double delta = out - in;
            while (delta > 180) delta -= 360;
            while (delta < -180) delta += 360;
            double ad = Math.abs(delta);
            if (ad < 30 || along[i] - lastAt < 30) continue;
            // the sharpest point of this bend within the next 15 m
            int best = i;
            double bestD = ad;
            for (int j = i + 1; j + 1 < n && along[j] - along[i] < 15; j++) {
                int aj = j, bj = j;
                while (aj > 0 && along[j] - along[aj] < 20) aj--;
                while (bj < n - 1 && along[bj] - along[j] < 20) bj++;
                if (aj == j || bj == j) continue;
                double dj = bearingOf(j, bj) - bearingOf(aj, j);
                while (dj > 180) dj -= 360;
                while (dj < -180) dj += 360;
                if (Math.abs(dj) > bestD) { bestD = Math.abs(dj); best = j; delta = dj; }
            }
            Step s = new Step();
            s.type = "turn";
            String side = delta < 0 ? "left" : "right";
            s.modifier = bestD > 135 ? "sharp " + side : bestD < 55 ? "slight " + side : side;
            s.index = best;
            s.lon = lon[best]; s.lat = lat[best];
            s.text = text(s, car);
            steps.addElement(s);
            lastAt = along[best];
            i = best;
        }
        Step e = new Step();
        e.type = "arrive";
        e.index = n - 1;
        e.lon = lon[n - 1]; e.lat = lat[n - 1];
        e.text = text(e, car);
        steps.addElement(e);
    }

    double bearingOf(int a, int b) {
        double kx = Math.cos(Math.toRadians(lat[a])) * 111320;
        return Geo.bearing((lon[b] - lon[a]) * kx, (lat[b] - lat[a]) * 110540);
    }

    static Route osrm(double fromLon, double fromLat, double toLon, double toLat, boolean car, boolean noToll) throws IOException {
        String url = BASE + (car ? "routed-car" : "routed-foot") + "/route/v1/driving/"
            + Geo.fmt(fromLon, 6) + "," + Geo.fmt(fromLat, 6) + "%3B" + Geo.fmt(toLon, 6) + "," + Geo.fmt(toLat, 6)
            + "?overview=full&geometries=polyline&steps=true";
        Net.Response r = null;
        String note = "";
        if (car && noToll) {
            String[] ex = { "motorway,toll", "motorway", "toll" };
            String[] names = { "avoiding motorways and toll roads", "avoiding motorways (vignette)", "avoiding toll roads (motorways possible)" };
            for (int i = 0; i < ex.length && (r == null || r.code != 200); i++) {
                r = Net.get(url + "&exclude=" + Net.encode(ex[i]), "car route without tolls");
                Log.add("route exclude=" + ex[i] + ": HTTP " + r.code);
                if (r.code == 200) note = names[i];
            }
            if (r.code != 200) note = "the server can't avoid toll roads: the route may use motorways";
        }
        if (r == null || r.code != 200) r = Net.get(url, car ? "car route" : "walking route");
        String text = Frpc.utf8Decode(r.body, 0, r.body.length);
        if (r.code != 200) {
            Log.add("route HTTP " + r.code + ": " + (text.length() > 300 ? text.substring(0, 300) : text));
            throw new IOException("route: HTTP " + r.code + " " + (text.length() > 120 ? text.substring(0, 120) : text));
        }
        Object o;
        try {
            o = Json.parse(text);
        } catch (RuntimeException e) {
            Log.add("route response unreadable (" + e + "): " + (text.length() > 300 ? text.substring(0, 300) : text));
            throw new IOException("route: unreadable response (" + e + ")");
        }
        if (!"Ok".equals(Json.str(o, "code"))) throw new IOException("route: " + Json.str(o, "code") + " " + Json.str(o, "message"));
        Vector routes = Json.arr(o, "routes");
        if (routes.size() == 0) throw new IOException("route not found");
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
        if (m.equals("left")) return "left";
        if (m.equals("right")) return "right";
        if (m.equals("slight left")) return "slightly left";
        if (m.equals("slight right")) return "slightly right";
        if (m.equals("sharp left")) return "sharp left";
        if (m.equals("sharp right")) return "sharp right";
        if (m.equals("uturn")) return "make a U-turn";
        return "straight on";
    }

    /** English instruction from an OSRM step. */
    static String text(Step s, boolean car) {
        String on = s.name.length() > 0 ? " onto " + s.name : "";
        String t = s.type;
        if (t.equals("depart")) return (car ? "Head off" : "Head off") + (s.name.length() > 0 ? " on " + s.name : "");
        if (t.equals("arrive")) return "Arrive";
        if (t.equals("roundabout") || t.equals("rotary") || t.equals("roundabout turn"))
            return "At the roundabout " + (s.exit > 0 ? "take exit " + s.exit : "exit") + on;
        if (t.equals("exit roundabout") || t.equals("exit rotary")) return "Exit the roundabout" + on;
        if (t.equals("merge")) return "Merge " + dir(s.modifier) + on;
        if (t.equals("on ramp")) return "Take the ramp " + dir(s.modifier) + on;
        if (t.equals("off ramp")) return "Take the exit " + dir(s.modifier) + on;
        if (t.equals("fork")) return "At the fork keep " + dir(s.modifier) + on;
        if (t.equals("end of road")) return "At the end of the road turn " + dir(s.modifier) + on;
        if (s.modifier.equals("straight")) return "Continue straight on" + on;
        if (s.modifier.equals("uturn")) return "Make a U-turn" + on;
        if (t.equals("new name") || t.equals("continue")) return "Continue" + (s.modifier.length() > 0 && !s.modifier.equals("straight") ? " " + dir(s.modifier) : "") + on;
        return "Turn " + dir(s.modifier) + on;
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
