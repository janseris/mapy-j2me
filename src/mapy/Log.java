package mapy;

/** In-memory request log (last ~24 KB), shown in the app and sent to the PC. */
public class Log {
    private static final StringBuffer buf = new StringBuffer();

    public static synchronized void add(String s) {
        long t = System.currentTimeMillis() % 86400000L;
        buf.append(two(t / 3600000)).append(':').append(two(t / 60000 % 60)).append(':').append(two(t / 1000 % 60))
           .append(' ').append(s).append('\n');
        if (buf.length() > 30000) buf.delete(0, buf.length() - 24000);
    }

    public static synchronized String text() {
        return buf.toString();
    }

    private static String two(long v) {
        return v < 10 ? "0" + v : "" + v;
    }

    public static void mem(String label) {
        Runtime r = Runtime.getRuntime();
        add("mem " + label + ": total " + r.totalMemory() + " free " + r.freeMemory());
    }
}
