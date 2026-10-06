package mapy;

import java.io.IOException;
import java.util.Vector;

/**
 * Points of interest for the map overlay, from OpenStreetMap through the Overpass API.
 * Named amenities, shops and tourism objects in a box, as compact CSV (easy on the 9300).
 * Light use only (one query per area, at street zoom), with our own User-Agent.
 */
public class Overpass {
    /**
     * Public Overpass servers. The main one (overpass-api.de) often answers 504 when it's busy;
     * then the next one is tried. The one that last worked is used first.
     */
    static final String[] SERVERS = {
        "https://overpass-api.de/api/interpreter?data=",
        // overpass.private.coffee (HTTP 404, HTTPS hangs) and maps.mail.ru (HTTP 301, HTTPS hangs)
        // don't work from the 9300 (Probe 2.7 big test): only the main server here
    };
    static int good;

    /** GET an Overpass query, trying the other servers when one is busy (5xx, 429) or unreachable. */
    public static Net.Response query(String q, String label) throws IOException {
        IOException last = null;
        Net.Response r = null;
        for (int i = 0; i < SERVERS.length; i++) {
            int s = (good + i) % SERVERS.length;
            try {
                r = Net.get(SERVERS[s] + Net.encode(q), label);
                if (r.code == 200) {
                    if (s != good) Log.add("Overpass: using " + Net.host(SERVERS[s]));
                    good = s;
                    return r;
                }
                if (r.code == 400) return r;                     // our query's fault: no point elsewhere
                Log.add("Overpass " + Net.host(SERVERS[s]) + ": HTTP " + r.code + ", trying the next server");
            } catch (IOException e) {
                last = e;
                Log.add("Overpass " + Net.host(SERVERS[s]) + ": " + e + ", trying the next server");
            }
        }
        if (r != null) return r;
        throw last;
    }
    public static final int MAX = 150;

    /** Columns after ::type, ::id, ::lat, ::lon. The first KIND_TAGS of them decide the kind. */
    static final String[] TAGS = { "name", "amenity", "shop", "tourism", "historic", "leisure", "military",
        "cuisine", "opening_hours", "website", "phone" };
    static final int KIND_FROM = 1, KIND_TO = 6;

    /** south, west, north, east */
    public static Vector pois(double s, double w, double n, double e) throws IOException {
        String box = "(" + Geo.fmt(s, 5) + "," + Geo.fmt(w, 5) + "," + Geo.fmt(n, 5) + "," + Geo.fmt(e, 5) + ")";
        StringBuffer cols = new StringBuffer("::type,::id,::lat,::lon");
        for (int i = 0; i < TAGS.length; i++) cols.append(',').append(TAGS[i]);
        // what the Mapy.com app shows on the map: businesses, sights, services, parks, water taps, parking...
        String q = "[out:csv(" + cols + ";false;\"|\")][timeout:25];("
            + "nwr[name][amenity]" + box + ";nwr[name][shop]" + box + ";nwr[name][tourism]" + box + ";"
            + "nwr[historic]" + box + ";nwr[leisure~\"^(park|playground|garden)$\"]" + box + ";nwr[military=bunker]" + box + ";"
            + "node[amenity~\"^(drinking_water|parking|shelter|toilets|atm|charging_station)$\"]" + box + ";"
            + "way[amenity=parking]" + box + ";);out center " + MAX + ";";
        Net.Response r = query(q, "places of interest (OSM)");
        if (r.code != 200) throw new IOException("Overpass HTTP " + r.code);
        return parse(Frpc.utf8Decode(r.body, 0, r.body.length));
    }

    static Vector parse(String text) {
        Vector out = new Vector();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            String line = text.substring(start, end);
            start = end + 1;
            String[] f = split(line, '|');
            if (f.length < 5) continue;
            try {
                Place p = new Place();
                p.osm = true;
                p.id = Long.parseLong(f[1].trim());
                p.lat = Double.parseDouble(f[2]);
                p.lon = Double.parseDouble(f[3]);
                String[] t = new String[TAGS.length];
                for (int i = 0; i < TAGS.length; i++) t[i] = 4 + i < f.length ? f[4 + i] : "";
                for (int i = KIND_FROM; i <= KIND_TO && p.kind.length() == 0; i++) {
                    if (t[i].length() > 0) p.kind = TAGS[i] + "=" + t[i];
                }
                p.subtitle = Kinds.label(p.kind);
                p.title = t[0].length() > 0 ? t[0] : p.subtitle;
                StringBuffer tags = new StringBuffer();
                for (int i = 0; i < TAGS.length; i++) {
                    if (t[i].length() > 0) tags.append(TAGS[i]).append(" = ").append(t[i]).append('\n');
                }
                p.tags = tags.toString();
                out.addElement(p);
            } catch (Throwable ex) {
                // header or broken line
            }
        }
        return out;
    }

    static String[] split(String s, char c) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == c) n++;
        String[] out = new String[n];
        int st = 0, k = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == c) {
                out[k++] = s.substring(st, i);
                st = i + 1;
            }
        }
        return out;
    }
}
