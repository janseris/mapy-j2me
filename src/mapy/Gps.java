package mapy;

import java.io.*;
import java.util.Vector;
import javax.bluetooth.*;
import javax.microedition.io.*;

/**
 * Position from a Bluetooth GPS: a GPS receiver, or an Android phone running an app that shares its
 * GPS as NMEA over the Serial Port Profile (Share GPS, Bluetooth GPS Output...). Same steps as the
 * probe's Bluetooth test: SPP service search on the address, then RFCOMM channels 1-10 directly.
 * Note: while the 9300 has a Bluetooth link to a PC (PC Suite), it can't connect to anything else.
 */
public class Gps implements Runnable, DiscoveryListener {
    public interface Listener {
        void position();
    }

    public static final Gps instance = new Gps();
    static final UUID SPP = new UUID(0x1101);

    public volatile Listener listener;
    public volatile String status = "vypnuto";
    public volatile boolean running, fix;
    public volatile double lat, lon, speedKmh, course;
    public volatile long lastFix;
    StreamConnection conn;
    String serviceUrl;
    final Object searchDone = new Object();
    boolean searching;

    public boolean hasFix() {
        return fix && System.currentTimeMillis() - lastFix < 10000;
    }

    public void connect() {
        if (running) return;
        running = true;
        new Thread(this).start();
    }

    public void disconnect() {
        running = false;
        try { if (conn != null) conn.close(); } catch (Throwable e) {}   // our own BT stream, safe to close
        status = "vypnuto";
        fix = false;
        notifyListener();
    }

    void setStatus(String s) {
        status = s;
        Log.add("gps: " + s);
        notifyListener();
    }

    void notifyListener() {
        Listener l = listener;
        if (l != null) l.position();
    }

    public void run() {
        String addr = clean(Settings.btAddress);
        if (addr.length() != 12) {
            setStatus("zadej adresu Bluetooth GPS v Nastavení");
            running = false;
            return;
        }
        try {
            Vector urls = new Vector();
            setStatus("hledám službu GPS na " + addr + "...");
            serviceUrl = null;
            try {
                DiscoveryAgent agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
                synchronized (searchDone) {
                    searching = true;
                    agent.searchServices(null, new UUID[] { SPP }, new RemoteDevice(addr) {}, this);
                    long end = System.currentTimeMillis() + 30000;
                    while (searching && System.currentTimeMillis() < end) searchDone.wait(1000);
                }
            } catch (Throwable e) {
                Log.add("gps service search: " + e);
            }
            if (serviceUrl != null) urls.addElement(serviceUrl);
            for (int ch = 1; ch <= 10; ch++) urls.addElement("btspp://" + addr + ":" + ch + ";authenticate=false;encrypt=false;master=false");
            for (int i = 0; i < urls.size() && running && conn == null; i++) {
                String url = (String) urls.elementAt(i);
                setStatus("připojuji " + (i == 0 && serviceUrl != null ? "službu GPS" : "kanál " + url.substring(21, url.indexOf(';'))));
                try {
                    conn = (StreamConnection) Connector.open(url);
                } catch (IOException e) {
                    Log.add("gps " + url + ": " + e);
                }
            }
            if (conn == null) {
                setStatus("nepřipojeno: běží na Androidu sdílení GPS? Není 9300 připojená přes Bluetooth k PC?");
                running = false;
                return;
            }
            setStatus("připojeno, čekám na polohu");
            read(conn.openInputStream());
        } catch (Throwable e) {
            if (running) setStatus("chyba: " + e.getMessage());
        } finally {
            try { if (conn != null) conn.close(); } catch (Throwable e) {}
            conn = null;
            running = false;
            fix = false;
            notifyListener();
        }
    }

    void read(InputStream in) throws IOException {
        StringBuffer line = new StringBuffer();
        long lastUi = 0;
        byte[] buf = new byte[512];
        int n;
        while (running && (n = in.read(buf)) > 0) {          // blocks, not single bytes: keeps up in real time
            for (int k = 0; k < n; k++) {
                int ch = buf[k] & 0xff;
                if (ch == '\n' || ch == '\r') {
                    if (line.length() == 0) continue;
                    if (Nmea.parse(line.toString())) {
                        lat = Nmea.lat;
                        lon = Nmea.lon;
                        speedKmh = Nmea.speedKmh;
                        course = Nmea.course;
                        lastFix = System.currentTimeMillis();
                        if (!fix) { fix = true; status = "poloha OK"; }
                    }
                    line.setLength(0);
                } else if (line.length() < 200) {
                    line.append((char) ch);
                }
            }
            long now = System.currentTimeMillis();
            if (fix && now - lastUi > 500) { lastUi = now; notifyListener(); }
        }
        if (running) setStatus("spojení ukončeno");
    }

    public void servicesDiscovered(int t, ServiceRecord[] recs) {
        for (int i = 0; i < recs.length && serviceUrl == null; i++)
            serviceUrl = recs[i].getConnectionURL(ServiceRecord.NOAUTHENTICATE_NOENCRYPT, false);
    }

    public void serviceSearchCompleted(int t, int resp) {
        Log.add("gps service search done: " + resp + ", url " + serviceUrl);
        synchronized (searchDone) { searching = false; searchDone.notifyAll(); }
    }

    public void deviceDiscovered(RemoteDevice d, DeviceClass c) {}
    public void inquiryCompleted(int t) {}

    public static String clean(String a) {
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if ((c >= '0' && c <= '9') || (c >= 'A' && c <= 'F')) b.append(c);
            else if (c >= 'a' && c <= 'f') b.append((char) (c - 32));
        }
        return b.toString();
    }
}
