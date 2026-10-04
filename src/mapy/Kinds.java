package mapy;

/** Czech labels and marker colours for the commonest OSM POI kinds. */
public class Kinds {
    static final String[] A = {
        "restaurant", "restaurace", "cafe", "kavárna", "fast_food", "rychlé občerstvení", "pub", "hospoda", "bar", "bar",
        "pharmacy", "lékárna", "hospital", "nemocnice", "doctors", "lékař", "dentist", "zubař", "bank", "banka",
        "atm", "bankomat", "post_office", "pošta", "school", "škola", "kindergarten", "školka", "university", "univerzita",
        "library", "knihovna", "theatre", "divadlo", "cinema", "kino", "place_of_worship", "kostel", "townhall", "radnice",
        "police", "policie", "fuel", "čerpací stanice", "parking", "parkoviště", "toilets", "WC", "ice_cream", "zmrzlina",
        "bicycle_rental", "půjčovna kol", "car_rental", "půjčovna aut", "marketplace", "tržnice", "post_box", "poštovní schránka"
    };
    static final String[] S = {
        "supermarket", "supermarket", "convenience", "večerka", "bakery", "pekárna", "butcher", "řeznictví", "clothes", "oblečení",
        "shoes", "obuv", "hairdresser", "kadeřnictví", "florist", "květinářství", "books", "knihkupectví", "electronics", "elektronika",
        "mobile_phone", "mobily", "optician", "optika", "kiosk", "trafika", "chemist", "drogerie", "hardware", "železářství",
        "bicycle", "kola", "jewelry", "klenoty", "gift", "dárky", "alcohol", "alkohol", "beauty", "kosmetika", "car_repair", "autoservis"
    };
    static final String[] T = {
        "hotel", "hotel", "hostel", "hostel", "guest_house", "penzion", "museum", "muzeum", "gallery", "galerie",
        "attraction", "atrakce", "viewpoint", "vyhlídka", "information", "informace", "artwork", "umělecké dílo", "zoo", "zoo",
        "apartment", "apartmán", "camp_site", "kemp", "picnic_site", "piknik"
    };

    static String find(String[] t, String k) {
        for (int i = 0; i < t.length; i += 2) if (t[i].equals(k)) return t[i + 1];
        return k.replace('_', ' ');
    }

    public static String label(String amenity, String shop, String tourism) {
        if (amenity.length() > 0) return find(A, amenity);
        if (shop.length() > 0) return "obchod: " + find(S, shop);
        if (tourism.length() > 0) return find(T, tourism);
        return "";
    }

    /** Marker colour by group: food, health, shop, tourism, transport/services. */
    public static int color(Place p) {
        String k = p.kind;
        if (k.equals("restaurant") || k.equals("cafe") || k.equals("fast_food") || k.equals("pub") || k.equals("bar") || k.equals("ice_cream")) return 0xE8833A;
        if (k.equals("pharmacy") || k.equals("hospital") || k.equals("doctors") || k.equals("dentist")) return 0xE04848;
        if (k.equals("hotel") || k.equals("hostel") || k.equals("guest_house") || k.equals("museum") || k.equals("gallery")
            || k.equals("attraction") || k.equals("viewpoint") || k.equals("information")) return 0x3A8EE8;
        if (p.tags.indexOf("shop = ") >= 0 || p.subtitle.startsWith("obchod")) return 0x9B59B6;
        return 0x2E9E5B;
    }
}
