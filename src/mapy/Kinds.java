package mapy;

import javax.microedition.lcdui.*;

/**
 * POI kinds: OSM "key=value" -> Mapy.com map icon (16 px strip /poi_icons16.png, made from the
 * icons the Mapy.com app loads from api.mapy.cz/poiimg/icon) and a Czech label.
 */
public class Kinds {
    /** key=value (or just key as a fallback), icon index in the strip. */
    static final String[] ICON = {
        "amenity=restaurant", "0",
        "amenity=cafe", "1",
        "amenity=fast_food", "2",
        "amenity=pub", "3",
        "amenity=biergarten", "3",
        "amenity=bar", "4",
        "amenity=wine_bar", "5",
        "shop=wine", "5",
        "amenity=ice_cream", "6",
        "amenity=food_court", "7",
        "tourism=hotel", "8",
        "tourism=hostel", "8",
        "tourism=guest_house", "8",
        "tourism=apartment", "8",
        "tourism=motel", "8",
        "amenity=parking", "9",
        "amenity=drinking_water", "10",
        "leisure=playground", "11",
        "historic=memorial", "12",
        "historic=monument", "12",
        "tourism=artwork", "12",
        "amenity=place_of_worship", "13",
        "historic=church", "13",
        "historic=wayside_cross", "14",
        "historic=wayside_shrine", "14",
        "tourism=museum", "15",
        "tourism=gallery", "16",
        "amenity=arts_centre", "16",
        "amenity=theatre", "17",
        "amenity=cinema", "18",
        "amenity=shelter", "19",
        "historic=fort", "20",
        "military=bunker", "20",
        "historic=castle", "21",
        "historic=city_gate", "22",
        "historic=building", "23",
        "historic=ruins", "21",
        "leisure=park", "24",
        "leisure=garden", "24",
        "amenity=pharmacy", "25",
        "amenity=hospital", "26",
        "amenity=clinic", "27",
        "amenity=doctors", "27",
        "amenity=dentist", "28",
        "amenity=bank", "29",
        "amenity=atm", "30",
        "amenity=post_office", "31",
        "amenity=fuel", "32",
        "amenity=charging_station", "33",
        "shop=supermarket", "34",
        "shop=convenience", "35",
        "shop=bakery", "36",
        "amenity=school", "37",
        "amenity=university", "37",
        "amenity=college", "37",
        "amenity=kindergarten", "38",
        "amenity=library", "39",
        "amenity=toilets", "40",
        "tourism=information", "41",
        "tourism=viewpoint", "42",
        "tourism=attraction", "43",
        "tourism=zoo", "44",
        "amenity=police", "45",
        "amenity=townhall", "46",
        "amenity=bicycle_rental", "47",
        "amenity=car_rental", "48",
        "shop=hairdresser", "49",
        "shop=clothes", "50",
        "shop=shoes", "51",
        "shop=florist", "52",
        "shop=books", "53",
        "shop=electronics", "54",
        "shop=mobile_phone", "55",
        "shop=optician", "56",
        "shop=kiosk", "57",
        "shop=chemist", "58",
        "shop=hardware", "59",
        "shop=doityourself", "59",
        "shop=bicycle", "47",
        "shop=jewelry", "60",
        "shop=gift", "61",
        "shop=alcohol", "5",
        "shop=beverages", "5",
        "shop=beauty", "62",
        "shop=car_repair", "63",
        "amenity=marketplace", "64",
        "tourism=camp_site", "65",
        "tourism=picnic_site", "66",
        "amenity=bench", "67",
        "leisure=sports_centre", "68",
        "leisure=fitness_centre", "68",
        "leisure=swimming_pool", "69",
        "amenity=taxi", "70",
        "amenity=bus_station", "71",
        "shop=butcher", "72",
        "shop=greengrocer", "73",
        "shop=pastry", "74",
        "shop=confectionery", "74",
        "shop=toys", "75",
        "shop=sports", "76",
        "shop=pet", "77",
        "amenity=veterinary", "78",
        "amenity=nightclub", "4",
        "amenity=community_centre", "23",
        "shop", "79",
        "amenity", "80",
        "tourism", "43",
        "historic", "23",
        "leisure", "24",
    };

    static final String[] LABEL = {
        "amenity=restaurant", "restaurant", "amenity=cafe", "café", "amenity=fast_food", "fast food",
        "amenity=pub", "pub", "amenity=biergarten", "beer garden", "amenity=bar", "bar", "amenity=wine_bar", "wine bar",
        "amenity=ice_cream", "ice cream", "amenity=food_court", "food court", "tourism=hotel", "hotel", "tourism=hostel", "hostel",
        "tourism=guest_house", "guest house", "tourism=apartment", "apartment", "tourism=motel", "motel",
        "amenity=parking", "parking", "amenity=drinking_water", "drinking water", "leisure=playground", "playground",
        "historic=memorial", "memorial", "historic=monument", "monument", "tourism=artwork", "artwork",
        "amenity=place_of_worship", "place of worship", "historic=church", "church", "historic=wayside_cross", "wayside cross",
        "historic=wayside_shrine", "wayside shrine", "tourism=museum", "museum", "tourism=gallery", "gallery",
        "amenity=arts_centre", "arts centre", "amenity=theatre", "theatre", "amenity=cinema", "cinema",
        "amenity=shelter", "shelter", "historic=fort", "fort", "military=bunker", "bunker", "historic=castle", "castle",
        "historic=city_gate", "city gate", "historic=building", "historic building", "historic=ruins", "ruins",
        "leisure=park", "park", "leisure=garden", "garden", "amenity=pharmacy", "pharmacy", "amenity=hospital", "hospital",
        "amenity=clinic", "clinic", "amenity=doctors", "doctor", "amenity=dentist", "dentist", "amenity=bank", "bank",
        "amenity=atm", "ATM", "amenity=post_office", "post office", "amenity=fuel", "fuel",
        "amenity=charging_station", "charging station", "shop=supermarket", "supermarket", "shop=convenience", "convenience store",
        "shop=bakery", "bakery", "amenity=school", "school", "amenity=university", "university", "amenity=college", "college",
        "amenity=kindergarten", "kindergarten", "amenity=library", "library", "amenity=toilets", "WC",
        "tourism=information", "information", "tourism=viewpoint", "viewpoint", "tourism=attraction", "attraction",
        "tourism=zoo", "zoo", "amenity=police", "police", "amenity=townhall", "town hall", "amenity=bicycle_rental", "bicycle rental",
        "amenity=car_rental", "car rental", "shop=hairdresser", "hairdresser", "shop=clothes", "clothes", "shop=shoes", "shoes",
        "shop=florist", "florist", "shop=books", "books", "shop=electronics", "electronics", "shop=mobile_phone", "mobile phones",
        "shop=optician", "optician", "shop=kiosk", "kiosk", "shop=chemist", "chemist", "shop=hardware", "hardware",
        "shop=doityourself", "DIY store", "shop=bicycle", "bicycles", "shop=jewelry", "jewellery", "shop=gift", "gifts",
        "shop=alcohol", "alcohol", "shop=wine", "wine", "shop=beverages", "beverages", "shop=beauty", "beauty",
        "shop=car_repair", "car repair", "amenity=marketplace", "marketplace", "tourism=camp_site", "camp site", "tourism=picnic_site", "picnic site",
        "amenity=bench", "bench", "leisure=sports_centre", "sports centre", "leisure=fitness_centre", "fitness",
        "leisure=swimming_pool", "swimming pool", "amenity=taxi", "taxi", "amenity=bus_station", "bus station",
        "shop=butcher", "butcher", "shop=greengrocer", "greengrocer", "shop=pastry", "pastry", "shop=confectionery", "confectionery",
        "shop=toys", "toys", "shop=sports", "sport", "shop=pet", "pet shop", "amenity=veterinary", "veterinary",
        "amenity=nightclub", "nightclub", "amenity=community_centre", "community centre"
    };

    private static Image strip;
    private static boolean tried;

    static String find(String[] t, String key) {
        for (int i = 0; i < t.length; i += 2) if (t[i].equals(key)) return t[i + 1];
        return null;
    }

    /** Icon index for a kind ("amenity=restaurant"), falling back to the key ("amenity"), or -1. */
    public static int icon(String kind) {
        String v = find(ICON, kind);
        if (v == null) {
            int eq = kind.indexOf('=');
            v = find(ICON, eq > 0 ? kind.substring(0, eq) : kind);
        }
        return v == null ? -1 : Integer.parseInt(v);
    }

    public static String label(String kind) {
        String l = find(LABEL, kind);
        if (l != null) return l;
        int eq = kind.indexOf('=');
        String v = eq > 0 ? kind.substring(eq + 1) : kind;
        if (kind.startsWith("shop=")) return "shop: " + v.replace('_', ' ');
        return v.replace('_', ' ');
    }

    public static final int SIZE = 16;

    /** Draws the kind's icon centred at x, y; a plain dot when there's no icon. */
    public static void draw(Graphics g, String kind, int x, int y) {
        if (!tried) {
            tried = true;
            try { strip = Image.createImage("/poi_icons16.png"); } catch (Throwable e) { strip = null; }
        }
        int i = icon(kind);
        if (strip == null || i < 0) {
            g.setColor(0xFFFFFF);
            g.fillArc(x - 5, y - 5, 10, 10, 0, 360);
            g.setColor(0x2E9E5B);
            g.fillArc(x - 4, y - 4, 8, 8, 0, 360);
            return;
        }
        int cx = g.getClipX(), cy = g.getClipY(), cw = g.getClipWidth(), ch = g.getClipHeight();
        g.clipRect(x - SIZE / 2, y - SIZE / 2, SIZE, SIZE);
        g.drawImage(strip, x - SIZE / 2 - i * SIZE, y - SIZE / 2, Graphics.TOP | Graphics.LEFT);
        g.setClip(cx, cy, cw, ch);
    }
}
