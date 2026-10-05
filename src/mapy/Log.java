package mapy;

import javax.microedition.rms.RecordStore;

/**
 * App log (last ~24 KB), shown in the app and sent to the PC. Saved to the phone (RMS) every
 * 2 s and on exit, so a run that ended (closed app, crash, battery) is still sent next time:
 * the sent log is the previous run followed by this one.
 */
public class Log {
    private static final StringBuffer buf = new StringBuffer();
    private static String previous = "", previous2 = "";
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
        StringBuffer b = new StringBuffer();
        if (previous2.length() > 0) b.append("==== run before the previous one ====\n").append(previous2).append('\n');
        if (previous.length() > 0) b.append("==== previous run ====\n").append(previous).append('\n');
        if (b.length() == 0) return buf.toString();
        return b.append("==== this run ====\n").append(buf.toString()).toString();
    }

    /** Called at start: moves the last run's saved log to "previous", then saves every 10 s. */
    public static void start() {
        try {
            RecordStore rs = RecordStore.openRecordStore("mapy_log", true);
            if (rs.getNumRecords() > 0) {
                byte[] b = rs.getRecord(1);
                if (b != null) previous = new String(b, "UTF-8");
            }
            if (rs.getNumRecords() > 1) {
                byte[] b = rs.getRecord(2);
                if (b != null) previous2 = new String(b, "UTF-8");
            }
            // the previous run moves to record 2; record 1 is this run's
            byte[] p = previous.getBytes("UTF-8");
            if (rs.getNumRecords() < 2) {
                if (rs.getNumRecords() == 0) rs.addRecord(new byte[0], 0, 0);
                rs.addRecord(p, 0, p.length);
            } else rs.setRecord(2, p, 0, p.length);
            rs.closeRecordStore();
        } catch (Throwable e) {
            previous = "(previous log unreadable: " + e + ")";
        }
        new Thread() {
            public void run() {
                while (true) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) {}
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
