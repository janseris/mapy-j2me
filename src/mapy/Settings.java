package mapy;

import java.io.*;
import javax.microedition.rms.*;

/** Settings and the last map view, in RMS. */
public class Settings {
    /** OSM's tile policy wants a User-Agent naming the app; we use our own everywhere. */
    public static final String DEFAULT_UA = "Mapy9300/3.8 (J2ME map app; Nokia 9300; SymbianOS/7.0s Series80/2.0; Profile/MIDP-2.0 Configuration/CLDC-1.1)";

    public static String pc = "192.168.137.1:8000";
    public static String userAgent = DEFAULT_UA;
    /** The Android phone sharing its GPS (GPS NMEA Tether). */
    public static final String DEFAULT_BT = "0C7165CF2E7E";
    public static String btAddress = DEFAULT_BT;
    /** Keep the map centred on the GPS position. */
    public static boolean follow = true;
    /** Load and show the road's speed limit (OSM) while moving. */
    public static boolean speedLimits = true;
    /**
     * The canvas has commands (the phone's Akce menu, side button labels). Off by default: the
     * Akce menu passes its arrow keys to the map, so the map has no commands and its own menu.
     */
    public static boolean akce = false;
    /** Last GPS position received (kept across runs): shown greyed until a new one comes. */
    public static double gpsLat, gpsLon;
    public static long gpsTime;
    /** Key codes of the four side buttons, top to bottom (learned once; 0 = unknown). */
    public static int[] sideKeys = new int[4];
    /** Map type (Layers) and the user's own Mapy.com API key (developer.mapy.com). */
    public static int layer = 0;
    public static String mapyKey = "";
    /** Try plain HTTP before HTTPS for public data (Net.get). */
    public static boolean httpFirst = true;
    /** Connect the Bluetooth GPS when the app starts (it turns itself off if the phone isn't there). */
    public static boolean gpsAuto = true;
    public static boolean pois = true;
    public static double lat = 50.0875, lon = 14.4213;   // Praha
    public static int zoom = 15;
    /** Width of the map's left info panel in pixels. */
    public static int panelWidth = 150;
    /** Disk (RMS) cache for tiles and photos, MB; 0 = off. */
    public static int cacheMB = 16;
    /** Show a thumbnail and rating when the cursor rests on an object. */
    public static boolean preview = true;

    public static void load() {
        try {
            RecordStore rs = RecordStore.openRecordStore("mapy", true);
            if (rs.getNumRecords() > 0) {
                DataInputStream in = new DataInputStream(new ByteArrayInputStream(rs.getRecord(1)));
                pc = in.readUTF();
                userAgent = in.readUTF();
                btAddress = in.readUTF();
                pois = in.readBoolean();
                lat = in.readDouble();
                lon = in.readDouble();
                zoom = in.readInt();
                try {
                    panelWidth = in.readInt();
                    cacheMB = in.readInt();
                    preview = in.readBoolean();
                    follow = in.readBoolean();
                    speedLimits = in.readBoolean();
                    akce = in.readBoolean();
                    gpsLat = in.readDouble();
                    gpsLon = in.readDouble();
                    gpsTime = in.readLong();
                    for (int i = 0; i < 4; i++) sideKeys[i] = in.readInt();
                    layer = in.readInt();
                    mapyKey = in.readUTF();
                    httpFirst = in.readBoolean();
                    gpsAuto = in.readBoolean();
                } catch (EOFException e) {}
            }
            rs.closeRecordStore();
            if (Gps.clean(btAddress).length() != 12) btAddress = DEFAULT_BT;
            if (userAgent.startsWith("Mapy9300/")) userAgent = DEFAULT_UA;     // old version's default
        } catch (Throwable e) {
            Log.add("settings load: " + e);
        }
    }

    public static void save() {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            DataOutputStream o = new DataOutputStream(bo);
            o.writeUTF(pc);
            o.writeUTF(userAgent);
            o.writeUTF(btAddress);
            o.writeBoolean(pois);
            o.writeDouble(lat);
            o.writeDouble(lon);
            o.writeInt(zoom);
            o.writeInt(panelWidth);
            o.writeInt(cacheMB);
            o.writeBoolean(preview);
            o.writeBoolean(follow);
            o.writeBoolean(speedLimits);
            o.writeBoolean(akce);
            o.writeDouble(gpsLat);
            o.writeDouble(gpsLon);
            o.writeLong(gpsTime);
            for (int i = 0; i < 4; i++) o.writeInt(sideKeys[i]);
            o.writeInt(layer);
            o.writeUTF(mapyKey);
            o.writeBoolean(httpFirst);
            o.writeBoolean(gpsAuto);
            byte[] b = bo.toByteArray();
            RecordStore rs = RecordStore.openRecordStore("mapy", true);
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length);
            else rs.setRecord(1, b, 0, b.length);
            rs.closeRecordStore();
        } catch (Throwable e) {
            Log.add("settings save: " + e);
        }
    }
}
