package mapy;

import java.io.*;
import javax.microedition.rms.*;

/** Settings and the last map view, in RMS. */
public class Settings {
    /** OSM's tile policy wants a User-Agent naming the app; we use our own everywhere. */
    public static final String DEFAULT_UA = "Mapy9300/0.8 (+https://github.com/janseris/mapy-j2me)";

    public static String pc = "192.168.137.1:8000";
    public static String userAgent = DEFAULT_UA;
    public static String btAddress = "";
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
                } catch (EOFException e) {}
            }
            rs.closeRecordStore();
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
