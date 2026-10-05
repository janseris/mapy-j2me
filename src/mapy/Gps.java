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
    /** Diagnostics: bytes and NMEA lines received, lines with a bad checksum, last sentence. */
    public volatile int bytes, lines, badLines;
    public volatile String lastLine = "";
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

    // Reading the Bluetooth stream on the 9300 (see probe 1.7-2.1): read(byte[512]) crashed with
    // E32USER-CBase 40 and single-byte read() with KERN-EXEC 3 under a full NMEA stream. Reading
    // exactly what available() reports, with the GPS sending only GGA + RMC at 1 Hz, ran stable
    // (3.5 min on a bus, delay constant, max gap 1.3 s).
    InputStream src;
    final byte[] rb = new byte[256];
    int rpos, rlen, zeroAvail;
    boolean availBroken;

    int next() throws IOException {
        while (running) {
            if (rpos < rlen) return rb[rpos++] & 0xff;
            if (availBroken) return src.read();
            int av = src.available();
            if (av > 0) {
                int n = src.read(rb, 0, Math.min(av, rb.length));
                if (n < 0) return -1;
                rpos = 0;
                rlen = n;
                zeroAvail = 0;
                continue;
            }
            if (++zeroAvail > 60) {             // 3 s without data in available(): try a blocking read
                int c = src.read();
                if (c >= 0) { availBroken = true; Log.add("gps: available() always 0, single bytes"); }
                zeroAvail = 0;
                return c;
            }
            try { Thread.sleep(50); } catch (InterruptedException e) {}
        }
        return -1;
    }

    void read(InputStream in) throws IOException {
        src = in;
        rpos = rlen = zeroAvail = 0;
        availBroken = false;
        StringBuffer line = new StringBuffer();
        long lastUi = 0, started = System.currentTimeMillis(), lastDiag = started;
        bytes = lines = badLines = 0;
        lastLine = "";
        int ch, eofs = 0;
        while (running) {
            ch = next();
            if (ch < 0) {
                if (++eofs > 50) break;             // 5 s of "end": really ended
                try { Thread.sleep(100); } catch (InterruptedException e) {}
                continue;
            }
            eofs = 0;
            bytes++;
            long t = System.currentTimeMillis();
            if (!hasFix() && t - lastDiag > 2000) {
                // no position yet: say what arrives, so "waiting" can be told apart from "nothing comes"
                lastDiag = t;
                status = bytes + " B, " + lines + " vět" + (lines > 0 ? ", bez polohy: GPS v Androidu ještě nemá fix?" : "") + (lastLine.length() > 0 ? " (" + lastLine + ")" : "");
                if ((t - started) % 10000 < 2100) Log.add("gps diag: " + status + ", bad " + badLines);
                notifyListener();
            }
            if (ch == '\n' || ch == '\r') {
                if (line.length() == 0) continue;
                lines++;
                String ln = line.toString();
                lastLine = ln.length() > 6 ? ln.substring(0, 6) + (ln.indexOf(",A,") > 0 ? " A" : ln.indexOf(",V,") > 0 ? " V" : "") : ln;
                if (lines <= 5) Log.add("gps line: " + ln);
                if (Nmea.parse(ln)) {
                    lat = Nmea.lat;
                    lon = Nmea.lon;
                    speedKmh = Nmea.speedKmh;
                    course = Nmea.course;
                    lastFix = System.currentTimeMillis();
                    if (!fix) { fix = true; status = "poloha OK"; Log.add("gps first fix after " + bytes + " B, " + lines + " lines"); }
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 500) { lastUi = now; notifyListener(); }
                }
                line.setLength(0);
            } else if (line.length() < 200) {
                line.append((char) ch);
            }
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

    /** 0C7165CF2E7E -> 0C:71:65:CF:2E:7E */
    public static String pretty(String a) {
        a = clean(a);
        if (a.length() != 12) return a;
        StringBuffer b = new StringBuffer();
        for (int i = 0; i < 12; i += 2) { if (i > 0) b.append(':'); b.append(a.substring(i, i + 2)); }
        return b.toString();
    }
}
