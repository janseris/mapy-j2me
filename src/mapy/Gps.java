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
    public volatile String status = "off";
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
        Thread t = new Thread(this);
        t.setPriority(Thread.MAX_PRIORITY);     // keep up with the Bluetooth data (see next())
        t.start();
    }

    public void disconnect() {
        running = false;
        try { if (conn != null) conn.close(); } catch (Throwable e) {}   // our own BT stream, safe to close
        status = "off";
        fix = false;
        notifyListener();
    }

    public static final String BT_OFF = "Bluetooth is off: switch it on in the phone (Control panel → Bluetooth), then G";

    /**
     * Bluetooth switched off: the 9300 throws BluetoothStateException or an IOException with
     * Symbian error -44 (KErrHardwareNotAvailable, "hardware není k dispozici").
     */
    public static boolean btOff(Throwable e) {
        if (e instanceof javax.bluetooth.BluetoothStateException) return true;
        String m = String.valueOf(e.getMessage()).toLowerCase();
        return m.indexOf("-44") >= 0 || m.indexOf("hardware") >= 0;
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
            setStatus("enter the Bluetooth GPS address in Settings");
            running = false;
            return;
        }
        // Net Helper reads the Bluetooth GPS natively when it runs: Java's own Bluetooth reading
        // slowed every download to seconds and crashed jes-java-comms (Probe 3.5)
        if (Settings.helper == 0 && viaHelper(addr)) {
            running = false;
            fix = false;
            notifyListener();
            return;
        }
        try {
          // The Android app's serial port service changes its channel when it restarts (seen 5, 6, 7,
          // 27...), so a found channel can already be gone (-34): then search again. No blind channel
          // tries: they connected to other services that send nothing, and each failed Bluetooth
          // connect blocked the Java comms layer (and every map download) for about a minute.
          for (int round = 1; round <= 3 && conn == null && running; round++) {
            if (round > 1) {
                setStatus("the GPS service didn't answer, searching again (" + round + "/3)...");
                try { Thread.sleep(2000); } catch (InterruptedException e) {}
            }
            setStatus("looking for the GPS service on " + addr + "...");
            serviceUrl = null;
            // The SPP service isn't always listed at once (right after a disconnect the Android app
            // may need a moment to offer it again): search up to 4 times before giving up.
            for (int attempt = 1; attempt <= 4 && serviceUrl == null && running; attempt++) {
                searchResp = 0;
                if (attempt > 1) {
                    setStatus("GPS service not found yet, trying again (" + attempt + "/4)...");
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
                    if (btOff(e)) {
                        setStatus(BT_OFF);
                        running = false;
                        return;
                    }
                } finally {
                    inCall = false;
                }
                if (searchResp != SERVICE_SEARCH_NO_RECORDS) break;     // found, or the phone isn't reachable
            }
            if (serviceUrl == null) {
                setStatus(searchResp == SERVICE_SEARCH_NO_RECORDS
                    ? "The Android phone offers no GPS sharing: check GPS NMEA Tether (switch it on again)"
                    : "not connected: is GPS sharing running on the Android phone? Is the 9300 connected to a PC over Bluetooth?");
                running = false;
                return;
            }
            setStatus("connecting the GPS service");
            try {
                while (running) {                                   // not while HTTP runs (see next())
                    synchronized (Net.COMMS) { if (!Net.commsBusy()) { inCall = true; break; } }
                    Thread.sleep(100);
                }
                try { conn = (StreamConnection) Connector.open(serviceUrl); } finally { inCall = false; }
            } catch (InterruptedException e) {
            } catch (IOException e) {
                Log.add("gps " + serviceUrl + ": " + e);
                if (btOff(e)) {
                    setStatus(BT_OFF);
                    running = false;
                    return;
                }
            }
          }
            if (conn == null) {
                if (running) setStatus("not connected: the GPS service didn't answer. Switch GPS sharing on the Android phone off and on, then G");
                running = false;
                return;
            }
            setStatus("connected, waiting for a position");
            read(conn.openInputStream());
        } catch (Throwable e) {
            if (running) setStatus(btOff(e) ? BT_OFF : "error: " + e.getMessage());
        } finally {
            try { if (conn != null) conn.close(); } catch (Throwable e) {}
            conn = null;
            running = false;
            fix = false;
            notifyListener();
        }
    }

    /** True while the GPS comes from Net Helper (Java does no Bluetooth then). */
    public volatile boolean fromHelper;

    /**
     * Polls Net Helper's /gps about once a second and feeds the new NMEA sentences in. False when
     * Net Helper doesn't answer at the start (then Java reads the GPS itself as before).
     */
    boolean viaHelper(String addr) {
        // 8124: Net Helper 0.6+ answers the GPS there at once (on 8123 it waited behind tile downloads)
        String url = "http://127.0.0.1:8124/gps?addr=" + addr;
        boolean tried8123 = false;
        String lastGga = "", lastRmc = "", lastState = "";
        int fails = 0;
        boolean answered = false;
        bytes = lines = badLines = 0;
        lastLine = "";
        while (running) {
            long t0 = System.currentTimeMillis();
            try {
                Net.Response r = Net.get(url, "gps");
                if (r.code != 200) throw new IOException("HTTP " + r.code);
                if (!answered) { answered = true; fromHelper = true; Log.add("gps: read by Net Helper (" + url.substring(0, 21) + ")"); }
                fails = 0;
                String text = Frpc.utf8Decode(r.body, 0, r.body.length);
                String state = "", info = "";
                int age = -1, st = 0;
                while (st < text.length()) {
                    int e = text.indexOf('\n', st);
                    if (e < 0) e = text.length();
                    String ln = text.substring(st, e).trim();
                    st = e + 1;
                    if (ln.startsWith("state=")) state = ln.substring(6);
                    else if (ln.startsWith("info=")) info = ln.substring(5);
                    else if (ln.startsWith("age=")) { try { age = Integer.parseInt(ln.substring(4)); } catch (Throwable x) {} }
                    else if (ln.startsWith("$")) {
                        boolean gga = ln.indexOf("GGA,") == 3;
                        if (gga ? ln.equals(lastGga) : ln.equals(lastRmc)) continue;   // nothing new
                        if (gga) lastGga = ln; else lastRmc = ln;
                        bytes += ln.length() + 2;
                        if (age >= 0 && age < 10000) line(ln);
                    }
                }
                if (!state.equals(lastState)) { lastState = state; Log.add("gps (Net Helper): " + state + ", " + info); }
                if (state.equals("btoff")) status = BT_OFF;
                else if (!hasFix()) status = "Net Helper: " + info + (age >= 0 ? ", last data " + age / 1000 + " s ago" : "");
                notifyListener();
            } catch (Throwable e) {
                if (!answered && !tried8123) { tried8123 = true; url = "http://127.0.0.1:8123/gps?addr=" + addr; continue; }   // older Net Helper
                if (!answered) { Log.add("gps: Net Helper not answering (" + e + "), Java reads the GPS"); return false; }
                if (++fails >= 5) setStatus("Net Helper stopped answering: " + e.getMessage());
            }
            long wait = Settings.GPS_RATE_MS[Math.max(0, Math.min(2, Settings.gpsRate))] - (System.currentTimeMillis() - t0);
            if (wait > 50) try { Thread.sleep(wait); } catch (InterruptedException e) {}
        }
        try { Net.get(url.substring(0, url.indexOf("?")) + "?stop=1", "gps stop"); } catch (Throwable e) {}
        fromHelper = false;
        return true;
    }

    long lastUi, lastTrack;

    /** One NMEA sentence (from Bluetooth or Net Helper). */
    void line(String ln) {
        lines++;
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
            if (!fix) { fix = true; status = "position OK"; Log.add("gps first fix after " + bytes + " B, " + lines + " lines"); }
            long now = System.currentTimeMillis();
            if (now - lastUi > 500) { lastUi = now; notifyListener(); }
            if (now - lastTrack > 30000) {      // a track point in the log every 30 s
                lastTrack = now;
                Log.add("gps " + Geo.format(lat, lon) + " " + (int) speedKmh + " km/h " + (int) course + "° sats " + Nmea.sats + " hdop " + Nmea.hdop + ", " + lines + " lines, " + bytes / 1024 + " KB" + (fromHelper ? ", via Net Helper" : ", max backlog " + maxAvail + " B"));
                maxAvail = 0;
            }
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
            // Read continuously, also while HTTP requests run. Probe 2.6's test settled it: 20 s
            // without reading (data piling up in the Bluetooth buffer) crashes the comms thread
            // with E32USER-CBase 40. Mapy 3.0-4.0 paused reading during requests and crashed that
            // way; earlier crashes during downloads were most likely the GPS thread falling behind
            // too. So: never pause, and run at the highest priority.
            inCall = true;
            int av, n = 0;
            try {
                av = src.available();
                if (av > 0) {
                    // read EXACTLY what available() says: a read of a different length (more: probe
                    // 1.7, less: after a backlog bigger than the buffer) crashed with E32USER-CBase 40
                    if (av > rb.length) rb = new byte[av + 512];
                    n = src.read(rb, 0, av);
                }
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
        long started = System.currentTimeMillis(), lastDiag = started;
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
                status = bytes + " B, " + lines + " sentences" + (lines > 0 ? ", no position: the Android GPS has no fix yet?" : "") + (lastLine.length() > 0 ? " (" + lastLine + ")" : "");
                if ((t - started) % 10000 < 2100) Log.add("gps diag: " + status + ", bad " + badLines);
                notifyListener();
            }
            if (ch == '\n' || ch == '\r') {
                if (line.length() == 0) continue;
                line(line.toString());
                line.setLength(0);
            } else if (line.length() < 200) {
                line.append((char) ch);
            }
        }
        if (running) setStatus("connection closed");
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
