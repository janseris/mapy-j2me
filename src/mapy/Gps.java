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
            // The SPP service isn't always listed at once (right after a disconnect the Android app
            // may need a moment to offer it again): search up to 4 times before giving up.
            for (int attempt = 1; attempt <= 4 && serviceUrl == null && running; attempt++) {
                searchResp = 0;
                if (attempt > 1) {
                    setStatus("služba GPS zatím nenalezena, zkouším znovu (" + attempt + "/4)...");
                    try { Thread.sleep(3000); } catch (InterruptedException e) {}
                }
                try {
                    while (running) {                       // not while HTTP runs (see next())
                        synchronized (Net.COMMS) { if (!Net.commsBusy()) { inCall = true; break; } }
                        Thread.sleep(100);
                    }
                    DiscoveryAgent agent = LocalDevice.getLocalDevice().getDiscoveryAgent();
                    synchronized (searchDone) {
                        searching = true;
                        agent.searchServices(null, new UUID[] { SPP }, new RemoteDevice(addr) {}, this);
                        long end = System.currentTimeMillis() + 30000;
                        while (searching && System.currentTimeMillis() < end) searchDone.wait(1000);
                    }
                } catch (Throwable e) {
                    Log.add("gps service search: " + e);
                } finally {
                    inCall = false;
                }
                if (searchResp != SERVICE_SEARCH_NO_RECORDS) break;     // found, or the phone isn't reachable
            }
            if (serviceUrl != null) urls.addElement(serviceUrl);
            else if (searchResp == SERVICE_SEARCH_NO_RECORDS) {
                // the phone answers but offers no serial port service; trying channels blindly only
                // connects to some other service that sends nothing (seen: channel 2)
                setStatus("Android nenabízí sdílení GPS: zkontrolujte GPS NMEA Tether (zapnout znovu)");
                running = false;
                return;
            }
            for (int ch = 1; ch <= 10; ch++) urls.addElement("btspp://" + addr + ":" + ch + ";authenticate=false;encrypt=false;master=false");
            for (int i = 0; i < urls.size() && running && conn == null; i++) {
                String url = (String) urls.elementAt(i);
                setStatus("připojuji " + (i == 0 && serviceUrl != null ? "službu GPS" : "kanál " + url.substring(21, url.indexOf(';'))));
                try {
                    while (running) {                                   // not while HTTP runs (see next())
                        synchronized (Net.COMMS) { if (!Net.commsBusy()) { inCall = true; break; } }
                        Thread.sleep(100);
                    }
                    try { conn = (StreamConnection) Connector.open(url); } finally { inCall = false; }
                } catch (InterruptedException e) {
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
    byte[] rb = new byte[2048];
    int rpos, rlen, zeroAvail;
    boolean availBroken;
    /** True during a Bluetooth read; Net waits for it before starting a request. */
    public static volatile boolean inCall;
    static final long MAX_PAUSE = 3000;
    long pausedSince;
    /** For the log: the biggest Bluetooth backlog seen and reads done during a long request. */
    int maxAvail, overlapReads;
    int searchResp;

    int next() throws IOException {
        while (running) {
            if (rpos < rlen) return rb[rpos++] & 0xff;
            // KERN-EXEC 3 in jes-...-java-comms while tiles were downloading: Bluetooth and HTTP
            // calls into the Java comms layer at the same moment. While a request runs, leave the
            // data in the Bluetooth buffer (150 B/s) and read it in the pauses between requests.
            // But a long pause (Overpass takes 10 s+) lets kilobytes pile up in the Bluetooth
            // buffer, and big backlogs are the other suspect for the comms crashes (probe 2.0 crashed
            // when it read slower than the data came). So: wait at most MAX_PAUSE, then read anyway.
            boolean free;
            long now = System.currentTimeMillis();
            synchronized (Net.COMMS) {
                free = !Net.commsBusy() || (pausedSince > 0 && now - pausedSince > MAX_PAUSE);
                if (free) inCall = true;
            }
            if (!free) {
                if (pausedSince == 0) pausedSince = now;
                try { Thread.sleep(50); } catch (InterruptedException e) {}
                continue;
            }
            if (pausedSince > 0 && now - pausedSince > MAX_PAUSE) overlapReads++;
            pausedSince = 0;
            int av, n = 0;
            try {
                av = src.available();
                if (av > 0) // read EXACTLY what available() says: a read of a different length (more: probe 1.7,
                // less: after a backlog bigger than the buffer) crashed with E32USER-CBase 40
                if (av > rb.length) rb = new byte[av + 512];
                n = src.read(rb, 0, av);
            } finally {
                inCall = false;
            }
            if (av > maxAvail) maxAvail = av;
            if (av > 1024) Log.add("gps: " + av + " B waiting in the Bluetooth buffer");
            if (av > 0) {
                if (n < 0) return -1;
                rpos = 0;
                rlen = n;
                zeroAvail = 0;
                continue;
            }
            // No data: keep polling. Never fall back to blocking single-byte reads: when the
            // Android had no fix it sent nothing for minutes, the fallback took that for a broken
            // available() and the single-byte reads then crashed (KERN-EXEC 3, as in probe 1.8/1.9).
            if (++zeroAvail % 200 == 0) Log.add("gps: no data for " + zeroAvail / 20 + " s");
            try { Thread.sleep(50); } catch (InterruptedException e) {}
        }
        return -1;
    }

    void read(InputStream in) throws IOException {
        src = in;
        rpos = rlen = zeroAvail = 0;
        availBroken = false;
        StringBuffer line = new StringBuffer();
        long lastUi = 0, lastTrack = 0, started = System.currentTimeMillis(), lastDiag = started;
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
                    Settings.gpsLat = lat;
                    Settings.gpsLon = lon;
                    Settings.gpsTime = lastFix;
                    if (!fix) { fix = true; status = "poloha OK"; Log.add("gps first fix after " + bytes + " B, " + lines + " lines"); }
                    long now = System.currentTimeMillis();
                    if (now - lastUi > 500) { lastUi = now; notifyListener(); }
                    if (now - lastTrack > 30000) {      // a track point in the log every 30 s
                        lastTrack = now;
                        Log.add("gps " + Geo.format(lat, lon) + " " + (int) speedKmh + " km/h " + (int) course + "° sats " + Nmea.sats + " hdop " + Nmea.hdop + ", " + lines + " lines, " + bytes / 1024 + " KB, max backlog " + maxAvail + " B, reads during requests " + overlapReads);
                        maxAvail = 0;
                    }
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
        searchResp = resp;
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
