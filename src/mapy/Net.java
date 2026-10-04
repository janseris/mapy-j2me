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

    private static final Object lock = new Object();
    private static boolean locked;

    public static String progressText() {
        if (!busy) return "";
        long s = (System.currentTimeMillis() - lastActivity) / 1000;
        return what + ": " + phase + (bytes > 0 ? " " + (bytes / 1024) + " KB" : "") + (s > 1 ? " (" + s + " s)" : "")
            + (attempt > 1 ? ", pokus " + attempt : "");
    }

    public static Response get(String url, String label) throws IOException {
        return request(url, null, null, label);
    }

    public static Response post(String url, String contentType, byte[] body, String label) throws IOException {
        return request(url, contentType, body, label);
    }

    static Response request(String url, String contentType, byte[] body, String label) throws IOException {
        acquire();
        busy = true;
        what = label;
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
            long t0 = System.currentTimeMillis();
            HttpConnection c = null;
            InputStream in = null;
            OutputStream out = null;
            try {
                phase("připojování", 0);
                c = (HttpConnection) Connector.open(url);
                c.setRequestProperty("User-Agent", Settings.userAgent);
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
