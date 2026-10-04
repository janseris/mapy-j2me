package mapy;

/** A search result, a POI from the map overlay, or a detail: something with a position. */
public class Place {
    public String title = "", subtitle = "", source = "", kind = "";
    public long id;
    public double lon, lat;
    public int zoom = 16;
    /** OSM tags (POI overlay), "key=value" lines; empty for Mapy.com places. */
    public String tags = "";
    /** true for POIs from the OSM overlay (no Mapy.com id yet). */
    public boolean osm;

    public String toString() {
        return title + (subtitle.length() > 0 ? " - " + subtitle : "");
    }
}
