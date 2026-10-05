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
    public static final int STALL_MS = 15000, ATTEMPTS = 3, PAUSE_MS = 250;

    public static class Response {
        public int code;
        public String type = "";
        public byte[] body = new byte[0];
        public long ms;
        /** "http" or "https": what the request really used. */
        public String scheme = "";
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
            + (attempt > 1 ? ", pokus " + attempt : "");
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
                    if (r.code < 300 || r.code == 404) {
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
        Log.add("NET start " + label + ": " + (url.length() > 90 ? url.substring(0, 90) + "..." : url));
        try {
            IOException last = null;
            for (attempt = 1; attempt <= ATTEMPTS; attempt++) {
                Attempt a = new Attempt(url, contentType, body, label);
                lastActivity = System.currentTimeMillis();
                a.start();
                while (!a.done) {
                    try { Thread.sleep(250); } catch (InterruptedException e) {}
                    notifyListener();
                    if (System.currentTimeMillis() - lastActivity > STALL_MS) {
                        Log.add(label + ": no progress for " + (STALL_MS / 1000) + " s in '" + phase + "', abandoned (attempt " + attempt + ")");
                        a.abandoned = true;
                        last = new IOException("server neodpovídá");
                        break;
                    }
                }
                if (a.done) {
                    if (a.response != null) return a.response;
                    last = a.error;
                }
            }
            throw last != null ? last : new IOException("chyba spojení");
        } finally {
            busy = false;
            phase = "";
            bytes = 0;
            notifyListener();
            try { Thread.sleep(PAUSE_MS); } catch (InterruptedException e) {}
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
        volatile boolean done, abandoned;
        Response response;
        IOException error;

        Attempt(String url, String contentType, byte[] body, String label) {
            this.url = url; this.contentType = contentType; this.body = body; this.label = label;
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
                phase("připojování", 0);
                c = (HttpConnection) Connector.open(url);
                if (!noUa.containsKey(host(url))) c.setRequestProperty("User-Agent", Settings.userAgent);
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
                phase("čekám na server", 0);
                Response r = new Response();
                r.scheme = url.startsWith("https") ? "https" : "http";
                r.code = c.getResponseCode();
                long tResp = System.currentTimeMillis();
                r.type = c.getType() == null ? "" : c.getType();
                int len = (int) c.getLength();
                in = c.openInputStream();
                ByteArrayOutputStream o = new ByteArrayOutputStream(len > 0 ? len : 8192);
                byte[] buf = new byte[2048];
                int n, total = 0;
                phase("stahování", 0);
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    total += n;
                    phase("stahování", total);
                    if (len > 0 && total >= len) break;
                }
                r.body = o.toByteArray();
                r.ms = System.currentTimeMillis() - t0;
                if (abandoned) return;
                Log.add(label + ": HTTP " + r.code + ", " + r.body.length + " B " + r.type + ", response " + (tResp - t0)
                    + " ms, total " + r.ms + " ms" + (attempt > 1 ? ", attempt " + attempt : ""));
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
