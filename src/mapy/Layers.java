package mapy;

/**
 * Raster map types. All are 256 px Web Mercator tiles; each one's terms ask for light use, a
 * User-Agent naming the app and the credit shown in the panel.
 * - OpenStreetMap: the standard OSM map.
 * - OpenTopoMap: topographic (contours, hill shading, paths), CC-BY-SA, max zoom 17.
 * - ČÚZK ZTM: the Czech state topographic map (Základní topografická mapa), open data, CZ only.
 * - ČÚZK ortofoto: the Czech state aerial photos, open data, CZ only.
 * - Mapy.com outdoor / aerial / standard: through the official Mapy.com API with the user's own API key
 *   (free registration at developer.mapy.com); hidden without a key.
 */
public class Layers {
    public static final String[] NAMES = {
        "OpenStreetMap (standard)",
        "OpenTopoMap (hiking, contours)",
        "ČÚZK base map (Czechia)",
        "ČÚZK aerial (Czechia)",
        "Mapy.com outdoor (API key)",
        "Mapy.com aerial (API key)",
        "Mapy.com standard (API key)",
    };
    /** Short names for the log. */
    public static final String[] SHORT = { "osm", "otm", "cuzk-ztm", "cuzk-orto", "mapy-outdoor", "mapy-aerial", "mapy-basic" };
    static final String[] URLS = {
        "https://tile.openstreetmap.org/{z}/{x}/{y}.png",
        "https://tile.opentopomap.org/{z}/{x}/{y}.png",
        "https://ags.cuzk.gov.cz/arcgis1/rest/services/ZTM_WM/MapServer/tile/{z}/{y}/{x}",
        "https://ags.cuzk.gov.cz/arcgis1/rest/services/ORTOFOTO_WM/MapServer/tile/{z}/{y}/{x}",
        "https://api.mapy.com/v1/maptiles/outdoor/256/{z}/{x}/{y}?apikey={key}",
        "https://api.mapy.com/v1/maptiles/aerial/256/{z}/{x}/{y}?apikey={key}",
        "https://api.mapy.com/v1/maptiles/basic/256/{z}/{x}/{y}?apikey={key}",
    };
    static final String[] CREDITS = {
        "© OpenStreetMap|",
        "© OpenTopoMap|© OpenStreetMap",
        "© ČÚZK|",
        "© ČÚZK|",
        "© Seznam.cz|© OpenStreetMap",
        "© Seznam.cz|© OpenStreetMap",
        "© Seznam.cz|© OpenStreetMap",
    };
    /** Deepest zoom each server has its own tiles for; the app zooms to MAX, enlarging beyond. */
    static final int[] NATIVE_ZOOM = { 19, 17, 19, 20, 18, 18, 19 };
    public static final int MAX = 20;

    /**
     * Measured network time per tile (ms) on the Nokia 9300, without the first tile: Probe 3.4
     * "Map servers" test, 4 runs on 2026-10-06, internet over USB from a PC (IP passthrough).
     * Direct = Java HttpConnection, a new connection (and TLS handshake for HTTPS) per tile;
     * helper = through Net Helper 9300, which keeps the connection open. Decoding comes on top
     * (JPEG ~200 ms, PNG 300-500 ms).
     */
    static final int[] MS_DIRECT = { 680, 510, 630, 590, 1650, 1590, 1550 };
    static final int[] MS_HELPER = { 230, 210, 430, 410, 340, 270, 320 };

    static String sec(int ms) { int t = (ms + 50) / 100; return (t / 10) + "." + (t % 10) + " s"; }

    /** For the map type list: the measured speed, the one that applies now first. */
    public static String speed(int l) {
        return Net.helperRunning()
            ? sec(MS_HELPER[l]) + " per tile (without Net Helper " + sec(MS_DIRECT[l]) + ")"
            : sec(MS_DIRECT[l]) + " per tile (with Net Helper " + sec(MS_HELPER[l]) + ")";
    }

    public static int current() {
        int l = Settings.layer;
        if (l < 0 || l >= URLS.length || (needsKey(l) && Settings.mapyKey.length() == 0)) return 0;
        return l;
    }

    public static boolean needsKey(int l) { return URLS[l].indexOf("{key}") >= 0; }

    public static String url(int z, int x, int y) {
        String u = URLS[current()];
        u = put(u, "{z}", "" + z);
        u = put(u, "{x}", "" + x);
        u = put(u, "{y}", "" + y);
        return put(u, "{key}", Net.encode(Settings.mapyKey));
    }

    public static String credit(int line) {
        String c = CREDITS[current()];
        int i = c.indexOf('|');
        return line == 0 ? c.substring(0, i) : c.substring(i + 1);
    }

    public static int maxZoom() { return MAX; }

    public static int nativeZoom() { return NATIVE_ZOOM[current()]; }

    static String put(String s, String a, String b) {
        int i = s.indexOf(a);
        return i < 0 ? s : s.substring(0, i) + b + s.substring(i + a.length());
    }
}
