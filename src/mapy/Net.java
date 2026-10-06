package mapy;

import java.io.*;
import javax.microedition.io.*;

/**
 * HTTP(S) the way pubtran-j2me does it on the Nokia 9300 (see LESSONS_NOKIA_9300.md):
 * - one request at a time (a global lock), so the TLS patch never runs two connections;
 * - each attempt on its own thread; an attempt without progress for STALL_MS is abandoned,
 *   NEVER closed from another thread (that crashed jes-dd-java-comms), up to ATTEMPTS tries;
 * - a short pause after each request, so the phone keeps answering the PC's USB keep-alive;
 * - live progress (phase, bytes) for the UI.
 */
public class Net {
    public static final int STALL_MS = 15000, ATTEMPTS = 3;
    /**
     * Pause after each request. pubtran used 250 ms for the PC's USB keep-alive; Probe 2.7 measured
     * that with no pause the next request answered in 160-190 ms instead of ~400 ms. 50 ms keeps a
     * short breath for the keep-alive.
     */
    public static final int PAUSE_MS = 50;

    /**
     * The pause is for internet over USB from a PC (IP passthrough: the PC's keep-alive must get
     * through); on GPRS it's not needed. Settings.link: 0 automatic, 1 USB, 2 GPRS. Automatic asks
     * Nokia's "com.nokia.network.access" property ("pd" = packet data / GPRS) when the phone has it;
     * without an answer it pauses, to be safe.
     */
    public static boolean usbLink() {
        if (Settings.link == 1) return true;
        if (Settings.link == 2) return false;
        return !"pd".equals(access());
    }

    static String access;

    static String access() {
        if (access == null) {
            try { access = System.getProperty("com.nokia.network.access"); } catch (Throwable e) {}
            if (access == null) access = "";
            Log.add("network access property: '" + access + "'");
        }
        return access;
    }

    public static class Response {
        public int code;
        public String type = "";
        public byte[] body = new byte[0];
        public long ms;
        /** "http" or "https": what the request really used. */
        public String scheme = "";
        /** Through Net Helper: its X-Helper header (conn=reused|new, timings); null when direct. */
        public String helper;
        /** Net Helper's own failure (X-Helper-Error), with its HTTP 502. */
        public String helperError;
    }

    /** Something to repaint when the progress changes. */
    public interface Listener {
        void progress();
    }

    public static volatile Listener listener;
    public static volatile String phase = "";
    public static volatile String what = "";
    public static volatile int bytes, attempt;
    public static volatile long lastActivity;
    public static volatile boolean busy;
    /**
     * Bluetooth (GPS) and HTTP must not be inside the Java comms layer at the same time (KERN-EXEC 3
     * in jes-...-java-comms). COMMS guards the check; "alive" counts request threads still running,
     * including abandoned (stalled) ones, which go on after the request has given up on them.
     */
    public static final Object COMMS = new Object();
    public static int alive;

    /** True while any HTTP work may be inside the comms layer. */
    public static boolean commsBusy() {
        return busy || alive > 0;
    }

    private static final Object lock = new Object();
    private static boolean locked;

    public static String progressText() {
        if (!busy) return "";
        long s = (System.currentTimeMillis() - lastActivity) / 1000;
        return what + ": " + phase + (bytes > 0 ? " " + (bytes / 1024) + " KB" : "") + (s > 1 ? " (" + s + " s)" : "")
            + (attempt > 1 ? ", attempt " + attempt : "");
    }

    /**
     * Plain HTTP where the server allows it: a request without TLS skips the TCP + TLS handshake
     * cost the phone pays on every HTTPS connection. Public data only (tiles, routes, POIs,
     * photos), never a URL with a key. Per host: the first GET tries http://; a redirect or an
     * error marks the host HTTPS-only for this run. Measured from a PC (2026-10): plain HTTP works
     * for tile.opentopomap.org, ags.cuzk.gov.cz, overpass-api.de and routing.openstreetmap.de;
     * tile.openstreetmap.org and Seznam's *.sdn.cz photos redirect to HTTPS.
     */
    static final java.util.Hashtable hostMode = new java.util.Hashtable();   // host -> "http" / "https"
    static {
        // known servers (tested from a PC, 2026-10), so they need no first try: plain HTTP works...
        hostMode.put("tile.opentopomap.org", "http");
        hostMode.put("ags.cuzk.gov.cz", "http");
        hostMode.put("overpass-api.de", "http");
        hostMode.put("routing.openstreetmap.de", "http");
        // ...or redirects to HTTPS
        hostMode.put("tile.openstreetmap.org", "https");
        hostMode.put("overpass.private.coffee", "https");       // http:// answered 404 (Probe 2.7)
        hostMode.put("overpass.kumi.systems", "https");
    }

    /**
     * Net Helper 9300: a native app on this phone (nethelper9300 repo). GET requests go through
     * http://127.0.0.1:8123/fetch?u=<URL> when it runs; it keeps its HTTP/HTTPS connections to the
     * servers open, so a tile costs ~100-300 ms there instead of a new TCP (+ TLS) connection each
     * time (Probe 3.2, 2026-10). Not running (connection refused): direct requests, and another
     * try after HELPER_RETRY_MS. Its own failure (502 + X-Helper-Error): that request again directly.
     */
    static final String HELPER = "http://127.0.0.1:8123/fetch?u=";
    static final long HELPER_RETRY_MS = 30000;
    static volatile int helperState;            // 0 not tried yet, 1 running, 2 not running
    static volatile long helperDownAt;
    public static volatile int helperOk, helperReused, helperFailed;

    /** Hosts the phone's own Java can't use (their HTTPS hangs): only through the helper. */
    static final java.util.Hashtable helperOnly = new java.util.Hashtable();
    static {
        helperOnly.put("overpass.private.coffee", Boolean.TRUE);
        helperOnly.put("overpass.kumi.systems", Boolean.TRUE);
    }

    static boolean useHelper(byte[] body) {
        if (Settings.helper != 0 || body != null) return false;
        return helperState != 2 || System.currentTimeMillis() - helperDownAt > HELPER_RETRY_MS;
    }

    public static final String HELPER_DOWN = "Net Helper is not running: start it on the phone for faster downloads (or turn it off in Settings)";

    /** Net Helper is to be used but didn't answer (connection refused): the panel shows a warning. */
    public static boolean helperDown() {
        return Settings.helper == 0 && helperState == 2;
    }

    public static boolean helperRunning() {
        return Settings.helper == 0 && helperState == 1;
    }

    public static String helperText() {
        if (helperState == 1) return "running, through it " + helperOk + ", on an open connection " + helperReused;
        if (helperState == 2) return "not running";
        return "not tried yet";
    }

    /** The start of a small text response (error pages), for the log. */
    public static String text(Response r) {
        if (r.body == null || r.body.length == 0 || r.type.indexOf("image") >= 0) return "";
        String t = Frpc.utf8Decode(r.body, 0, Math.min(r.body.length, 400));
        StringBuffer b = new StringBuffer();
        boolean tag = false;
        for (int i = 0; i < t.length(); i++) {          // without the HTML tags
            char c = t.charAt(i);
            if (c == '<') tag = true; else if (c == '>') { tag = false; b.append(' '); } else if (!tag && c >= ' ') b.append(c);
        }
        return b.toString().trim();
    }

    static String host(String url) {
        int s = url.indexOf("://") + 3, e = url.indexOf('/', s);
        return e < 0 ? url.substring(s) : url.substring(s, e);
    }

    public static Response get(String url, String label) throws IOException {
        if (Settings.httpFirst && url.startsWith("https://") && url.indexOf("apikey") < 0) {
            String h = host(url);
            if (h.endsWith(".sdn.cz")) hostMode.put(h, "https");   // Seznam's photo servers (d34-a, d48-a...)
            Object m = hostMode.get(h);
            if (!"https".equals(m)) {
                String plain = "http://" + url.substring(8);
                try {
                    Response r = request(plain, null, null, label + " (http)");
                    if (r.code >= 500) return r;            // server trouble, says nothing about http
                    if (r.code < 300) {
                        if (m == null) Log.add("http works for " + h + " (HTTP " + r.code + ")");
                        hostMode.put(h, "http");
                        return r;
                    }
                    // a redirect, or an error that only plain HTTP gets (ags.cuzk.gov.cz answered the
                    // phone's http:// requests with 400 while a PC's worked): HTTPS from now on
                    Log.add(h + ": HTTP " + r.code + " on http://, using https. " + text(r));
                } catch (IOException e) {
                    Log.add(h + ": http:// failed (" + e + "), using https");
                }
                hostMode.put(h, "https");
            }
        }
        return request(url, null, null, label);
    }

    public static Response post(String url, String contentType, byte[] body, String label) throws IOException {
        return request(url, contentType, body, label);
    }

    /**
     * The phone's Java adds its own second header "User-Agent: UNTRUSTED/1.0" after ours (MIDP's
     * mark for unsigned MIDlets; seen with ota_server's raw echo). Most servers take it; ČÚZK's IIS
     * answers 400 "Invalid Header". For such hosts we send no User-Agent of our own, so there's
     * only the phone's. Learned on such a 400 too, then the request is repeated once.
     */
    static final java.util.Hashtable noUa = new java.util.Hashtable();
    static { noUa.put("ags.cuzk.gov.cz", Boolean.TRUE); }

    static Response request(String url, String contentType, byte[] body, String label) throws IOException {
        Response r = request0(url, contentType, body, label);
        String h = host(url);
        if (r.code == 400 && !noUa.containsKey(h) && text(r).indexOf("Invalid Header") >= 0) {
            noUa.put(h, Boolean.TRUE);
            Log.add(h + ": 400 Invalid Header, again without our User-Agent (the phone adds a second one)");
            r = request0(url, contentType, body, label);
        }
        return r;
    }

    static Response request0(String url, String contentType, byte[] body, String label) throws IOException {
        acquire();
        synchronized (COMMS) { busy = true; }    // atomically with Gps' check (no new Bluetooth call from now)
        // a Bluetooth (GPS) call may be in progress: let it finish first (Gps.next)
        for (int i = 0; i < 600 && Gps.inCall; i++) {          // a Bluetooth connect can take seconds
            try { Thread.sleep(25); } catch (InterruptedException e) {}
        }
        what = label;
        String shown = masked(url);
        // our own helper on this phone (GPS polled every second): not through /fetch, not logged
        boolean local = url.startsWith("http://127.0.0.1");
        if (!local) Log.add("NET start " + label + ": " + (shown.length() > 90 ? shown.substring(0, 90) + "..." : shown));
        try {
            IOException last = null;
            boolean direct = false;
            // Overpass: one try with a longer wait. It answers slowly or with 504 when busy, and three
            // 15 s tries per server held every map tile back for minutes (one request at a time).
            boolean slow = host(url).indexOf("overpass") >= 0;
            int attempts = slow ? 1 : ATTEMPTS, stallMs = slow ? 30000 : STALL_MS;
            for (attempt = 1; attempt <= attempts; attempt++) {
                boolean viaHelper = !direct && !local && useHelper(body);
                Attempt a = new Attempt(url, contentType, body, label, viaHelper);
                lastActivity = System.currentTimeMillis();
                a.start();
                while (!a.done) {
                    try { Thread.sleep(250); } catch (InterruptedException e) {}
                    notifyListener();
                    if (System.currentTimeMillis() - lastActivity > stallMs) {
                        Log.add(label + ": no progress for " + (stallMs / 1000) + " s in '" + phase + "', abandoned (attempt " + attempt + ")");
                        a.abandoned = true;
                        last = new IOException("server not responding");
                        break;
                    }
                }
                if (a.done && viaHelper) {
                    if (a.response == null) {
                        // the helper isn't running (or broke): this request again directly, not counted
                        if (helperState != 2) Log.add("Net Helper not reachable (" + a.error + "), direct requests");
                        helperState = 2;
                        notifyListener();
                        helperDownAt = System.currentTimeMillis();
                        helperFailed++;
                        if (helperOnly.containsKey(host(url))) throw new IOException("needs Net Helper");
                        attempt--;
                        continue;
                    }
                    if (a.response.helperError != null) {
                        Log.add("Net Helper: " + a.response.helperError + ", once more directly");
                        helperFailed++;
                        direct = true;
                        attempt--;
                        continue;
                    }
                    if (helperState != 1) Log.add("Net Helper running, requests go through it");
                    helperState = 1;
                    helperOk++;
                    if (a.response.helper != null && a.response.helper.startsWith("conn=reused")) helperReused++;
                }
                if (a.done) {
                    if (a.response != null) return a.response;
                    last = a.error;
                }
            }
            throw last != null ? last : new IOException("connection error");
        } finally {
            busy = false;
            phase = "";
            bytes = 0;
            notifyListener();
            if (usbLink()) { try { Thread.sleep(PAUSE_MS); } catch (InterruptedException e) {} }
            release();
        }
    }

    static void notifyListener() {
        Listener l = listener;
        if (l != null) l.progress();
    }

    static void acquire() {
        synchronized (lock) {
            long end = System.currentTimeMillis() + STALL_MS * ATTEMPTS + 5000;
            while (locked && System.currentTimeMillis() < end) {
                try { lock.wait(500); } catch (InterruptedException e) {}
            }
            locked = true;
        }
    }

    static void release() {
        synchronized (lock) {
            locked = false;
            lock.notifyAll();
        }
    }

    static class Attempt extends Thread {
        final String url, contentType, label;
        final byte[] body;
        final boolean viaHelper;
        volatile boolean done, abandoned;
        Response response;
        IOException error;

        Attempt(String url, String contentType, byte[] body, String label, boolean viaHelper) {
            this.url = url; this.contentType = contentType; this.body = body; this.label = label; this.viaHelper = viaHelper;
        }

        void phase(String s, int n) {
            if (abandoned) return;
            phase = s;
            bytes = n;
            lastActivity = System.currentTimeMillis();
        }

        public void run() {
            synchronized (COMMS) { alive++; }
            long t0 = System.currentTimeMillis();
            HttpConnection c = null;
            InputStream in = null;
            OutputStream out = null;
            try {
                phase("connecting", 0);
                if (viaHelper) {
                    // the helper sends exactly one User-Agent (ours), so no duplicate as with the phone's
                    c = (HttpConnection) Connector.open(HELPER + encode(url));
                    c.setRequestProperty("X-Ua", Settings.userAgent);
                } else {
                    c = (HttpConnection) Connector.open(url);
                    if (!noUa.containsKey(host(url))) c.setRequestProperty("User-Agent", Settings.userAgent);
                }
                if (body != null) {
                    c.setRequestMethod(HttpConnection.POST);
                    c.setRequestProperty("Content-Type", contentType);
                    c.setRequestProperty("Accept", contentType);
                    c.setRequestProperty("Content-Length", "" + body.length);
                    out = c.openOutputStream();
                    out.write(body);
                    out.close();
                    out = null;
                }
                phase("waiting for the server", 0);
                Response r = new Response();
                r.scheme = url.startsWith("https") ? "https" : "http";
                r.code = c.getResponseCode();
                long tResp = System.currentTimeMillis();
                r.type = c.getType() == null ? "" : c.getType();
                if (viaHelper) {
                    r.helper = c.getHeaderField("X-Helper");
                    r.helperError = c.getHeaderField("X-Helper-Error");
                }
                int len = (int) c.getLength();
                in = c.openInputStream();
                ByteArrayOutputStream o = new ByteArrayOutputStream(len > 0 ? len : 8192);
                byte[] buf = new byte[2048];
                int n, total = 0;
                phase("downloading", 0);
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    total += n;
                    phase("downloading", total);
                    if (len > 0 && total >= len) break;
                }
                r.body = o.toByteArray();
                r.ms = System.currentTimeMillis() - t0;
                if (abandoned) return;
                if (!url.startsWith("http://127.0.0.1")) Log.add(label + ": HTTP " + r.code + ", " + r.body.length + " B " + r.type + ", response " + (tResp - t0)
                    + " ms, total " + r.ms + " ms" + (attempt > 1 ? ", attempt " + attempt : "")
                    + (viaHelper ? ", via helper: " + r.helper + (r.helperError != null ? " ERROR " + r.helperError : "") : ""));
                response = r;
            } catch (IOException e) {
                error = e;
                Log.add(label + " failed after " + (System.currentTimeMillis() - t0) + " ms in '" + phase + "': " + e);
            } catch (Throwable e) {
                error = new IOException(e.toString());
                Log.add(label + " failed: " + e);
            } finally {
                // closed only here, in the thread that opened it
                try { if (out != null) out.close(); } catch (Throwable e) {}
                try { if (in != null) in.close(); } catch (Throwable e) {}
                try { if (c != null) c.close(); } catch (Throwable e) {}
                synchronized (COMMS) { alive--; }
                done = true;
            }
        }
    }

    /** The URL for the log, with the API key's value replaced (the log is sent to the PC). */
    static String masked(String url) {
        int i = url.indexOf("apikey=");
        if (i < 0) return url;
        int e = url.indexOf('&', i);
        return url.substring(0, i + 7) + "***" + (e < 0 ? "" : url.substring(e));
    }

    /** URL-encodes a query parameter (UTF-8). */
    public static String encode(String s) {
        byte[] b = Frpc.utf8Encode(s);
        StringBuffer sb = new StringBuffer();
        for (int i = 0; i < b.length; i++) {
            int c = b[i] & 0xff;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.') {
                sb.append((char) c);
            } else {
                sb.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 15));
            }
        }
        return sb.toString();
    }
}
