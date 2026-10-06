package mapy;

import java.io.IOException;
import java.util.Vector;

/**
 * The Mapy.com FastRPC calls captured from the Android app (see mapy/ANALYSIS.md):
 * POST https://vectmap.mapy.cz/rpc, Content-Type application/x-frpc, no key or login.
 * Coordinates are [lon, lat] arrays of doubles (WGS84).
 */
public class MapyApi {
    static final String RPC = "https://vectmap.mapy.cz/rpc";

    static Vector coord(double lon, double lat) {
        Vector v = new Vector(2);
        v.addElement(new Double(lon));
        v.addElement(new Double(lat));
        return v;
    }

    static Object call(String method, Object[] params, String label) throws IOException {
        byte[] body = Frpc.encodeCall(method, params);
        Net.Response r = Net.post(RPC, "application/x-frpc", body, label);
        if (r.code != 200) throw new IOException("HTTP " + r.code);
        try {
            Object o = Frpc.decode(r.body);
            if (o instanceof FrpcStruct) {
                FrpcStruct s = (FrpcStruct) o;
                int status = s.getInt("status", 200);
                if (status != 200) throw new IOException("Mapy.com " + status + ": " + s.getString("statusMessage", ""));
            }
            return o;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    /** Search suggestions for text, near the map view (same parameters as the app). */
    public static Vector suggest(String query, double lon, double lat, double[] view, int zoom) throws IOException {
        FrpcStruct p = new FrpcStruct()
            .put("appName", "mapy")
            .put("billingCountry", "cz")
            .put("categoriesOnly", false)
            .put("coordSystemDst", "wgs")
            .put("coordSystemSrc", "wgs")
            .put("count", 10)
            .put("includeNonEntityTypes", true)
            .put("lang", "en")
            .put("mapPoiTypeOnly", false)
            .put("position", coord(lon, lat))
            .put("stripHistoryQuery", false)
            .put("sysLang", "en");
        Vector vp = new Vector(2);
        vp.addElement(coord(view[0], view[1]));
        vp.addElement(coord(view[2], view[3]));
        p.put("viewPort", vp).put("withCategories", false).put("zoom", zoom);
        Object o = call("suggest", new Object[] { query, p }, "search \"" + query + "\"");
        Vector out = new Vector();
        if (!(o instanceof FrpcStruct)) return out;
        Vector res = ((FrpcStruct) o).getArray("results");
        for (int i = 0; i < res.size(); i++) {
            FrpcStruct r = FrpcStruct.structAt(res, i);
            if (r == null) continue;
            Vector mark = r.getArray("mark");
            if (mark.size() < 2) continue;      // categories etc. have no position
            Place pl = new Place();
            pl.title = r.getString("title", "");
            pl.subtitle = r.getString("subtitle", "");
            pl.source = r.getString("srcSource", r.getString("source", ""));
            pl.id = r.getLong("srcId", r.getLong("id", 0));
            pl.lon = num(mark.elementAt(0));
            pl.lat = num(mark.elementAt(1));
            pl.zoom = r.getInt("zoom", 16);
            out.addElement(pl);
        }
        return out;
    }

    static FrpcStruct detailParams() {
        return new FrpcStruct()
            .put("appName", "mapy")
            .put("coordSystemDst", "wgs")
            .put("coordSystemSrc", "wgs")
            .put("lang", "en")
            .put("poiStats", false)
            .put("stripHtml", true)
            .put("sysLang", "en")
            .put("version", 8);
    }

    /** Detail of a place found by suggest (source + id). */
    public static FrpcStruct detail(String source, long id) throws IOException {
        FrpcStruct p = detailParams().put("source", source).put("srcId", id);
        return detailOf(call("getDetail", new Object[] { p }, "detail " + source + "/" + id));
    }

    /** "What's here": detail of a map position (like a long press in the app). */
    public static FrpcStruct detailAt(double lon, double lat, int zoom) throws IOException {
        FrpcStruct p = detailParams().put("mark", coord(lon, lat)).put("zoom", zoom);
        return detailOf(call("getDetail", new Object[] { p }, "what's here"));
    }

    static FrpcStruct detailOf(Object o) throws IOException {
        FrpcStruct d = o instanceof FrpcStruct ? ((FrpcStruct) o).getStruct("detail") : null;
        if (d == null) throw new IOException("Mapy.com: no detail");
        return d;
    }

    static double num(Object o) {
        if (o instanceof Double) return ((Double) o).doubleValue();
        if (o instanceof Long) return ((Long) o).longValue();
        return 0;
    }
}
