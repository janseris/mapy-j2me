package mapy;

import javax.microedition.rms.RecordStore;

/**
 * App log (last ~24 KB), shown in the app and sent to the PC. Saved to the phone (RMS) every
 * 10 s and on exit, so a run that ended (closed app, crash, battery) is still sent next time:
 * the sent log is the previous run followed by this one.
 */
public class Log {
    private static final StringBuffer buf = new StringBuffer();
    private static String previous = "";
    private static boolean dirty;

    public static synchronized void add(String s) {
        long t = System.currentTimeMillis() % 86400000L;
        buf.append(two(t / 3600000)).append(':').append(two(t / 60000 % 60)).append(':').append(two(t / 1000 % 60))
           .append(' ').append(s).append('\n');
        if (buf.length() > 30000) buf.delete(0, buf.length() - 24000);
        dirty = true;
    }

    public static synchronized String text() {
        return buf.toString();
    }

    /** What goes to the PC: the previous run's log, then this run's. */
    public static synchronized String all() {
        if (previous.length() == 0) return buf.toString();
        return "==== previous run ====\n" + previous + "\n==== this run ====\n" + buf.toString();
    }

    /** Called at start: moves the last run's saved log to "previous", then saves every 10 s. */
    public static void start() {
        try {
            RecordStore rs = RecordStore.openRecordStore("mapy_log", true);
            if (rs.getNumRecords() > 0) {
                byte[] b = rs.getRecord(1);
                if (b != null) previous = new String(b, "UTF-8");
            }
            rs.closeRecordStore();
        } catch (Throwable e) {
            previous = "(previous log unreadable: " + e + ")";
        }
        new Thread() {
            public void run() {
                while (true) {
                    try { Thread.sleep(10000); } catch (InterruptedException e) {}
                    if (dirty) save();
                }
            }
        }.start();
    }

    public static void save() {
        byte[] b;
        synchronized (Log.class) {
            dirty = false;
            String s = buf.toString();
            if (s.length() > 20000) s = s.substring(s.length() - 20000);
            try { b = s.getBytes("UTF-8"); } catch (Throwable e) { b = s.getBytes(); }
        }
        try {
            RecordStore rs = RecordStore.openRecordStore("mapy_log", true);
            if (rs.getNumRecords() == 0) rs.addRecord(b, 0, b.length);
            else rs.setRecord(1, b, 0, b.length);
            rs.closeRecordStore();
        } catch (Throwable e) {}
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : "" + v;
    }

    public static void mem(String label) {
        Runtime r = Runtime.getRuntime();
        add("mem " + label + ": total " + r.totalMemory() + " free " + r.freeMemory());
    }
}
