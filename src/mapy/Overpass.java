package mapy;

import java.io.IOException;
import java.util.Vector;

/**
 * Points of interest for the map overlay, from OpenStreetMap through the Overpass API.
 * Named amenities, shops and tourism objects in a box, as compact CSV (easy on the 9300).
 * Light use only (one query per area, at street zoom), with our own User-Agent.
 */
public class Overpass {
    static final String URL = "https://overpass-api.de/api/interpreter?data=";
    public static final int MAX = 80;

    /** south, west, north, east */
    public static Vector pois(double s, double w, double n, double e) throws IOException {
        String box = Geo.fmt(s, 5) + "," + Geo.fmt(w, 5) + "," + Geo.fmt(n, 5) + "," + Geo.fmt(e, 5);
        String q = "[out:csv(::id,::lat,::lon,name,amenity,shop,tourism,cuisine,opening_hours,website,phone;false;\"|\")][timeout:20];"
            + "(node[name][amenity](" + box + ");node[name][shop](" + box + ");node[name][tourism](" + box + "););out " + MAX + ";";
        Net.Response r = Net.get(URL + Net.encode(q), "body zájmu (OSM)");
        if (r.code != 200) throw new IOException("Overpass HTTP " + r.code);
        return parse(Frpc.utf8Decode(r.body, 0, r.body.length));
    }

    static final String[] KEYS = { "name", "amenity", "shop", "tourism", "cuisine", "opening_hours", "website", "phone" };

    static Vector parse(String text) {
        Vector out = new Vector();
        int start = 0;
        while (start < text.length()) {
            int end = text.indexOf('\n', start);
            if (end < 0) end = text.length();
            String line = text.substring(start, end);
            start = end + 1;
            String[] f = split(line, '|');
            if (f.length < 4) continue;
            try {
                Place p = new Place();
                p.osm = true;
                p.id = Long.parseLong(f[0].trim());
                p.lat = Double.parseDouble(f[1]);
                p.lon = Double.parseDouble(f[2]);
                p.title = f[3];
                String kind = f.length > 4 && f[4].length() > 0 ? f[4] : f.length > 5 && f[5].length() > 0 ? f[5] : f.length > 6 ? f[6] : "";
                p.kind = kind;
                p.subtitle = Kinds.label(f.length > 4 ? f[4] : "", f.length > 5 ? f[5] : "", f.length > 6 ? f[6] : "");
                StringBuffer tags = new StringBuffer();
                for (int i = 3; i < f.length && i - 3 < KEYS.length; i++) {
                    if (f[i].length() > 0) tags.append(KEYS[i - 3]).append(" = ").append(f[i]).append('\n');
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
