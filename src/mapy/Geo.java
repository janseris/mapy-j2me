package mapy;

/**
 * Web Mercator (the OSM / slippy-map tile grid) and the maths CLDC 1.1 lacks
 * (Math has no log, exp or atan).
 */
public class Geo {
    public static final int TILE = 256;
    static final double LN2 = 0.6931471805599453;

    /** World pixel x of a longitude at zoom. */
    public static double lonToX(double lon, int zoom) {
        return (lon + 180.0) / 360.0 * (double) (TILE << zoom);
    }

    /** World pixel y of a latitude at zoom. */
    public static double latToY(double lat, int zoom) {
        double r = Math.toRadians(lat);
        return (1.0 - ln(Math.tan(r) + 1.0 / Math.cos(r)) / Math.PI) / 2.0 * (double) (TILE << zoom);
    }

    public static double xToLon(double x, int zoom) {
        return x / (double) (TILE << zoom) * 360.0 - 180.0;
    }

    public static double yToLat(double y, int zoom) {
        double n = Math.PI * (1.0 - 2.0 * y / (double) (TILE << zoom));
        return Math.toDegrees(2.0 * atan(exp(n)) - Math.PI / 2.0);
    }

    /** Approximate distance in metres (equirectangular, fine for "nearby"). */
    public static double distance(double lon1, double lat1, double lon2, double lat2) {
        double x = Math.toRadians(lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = Math.toRadians(lat2 - lat1);
        return Math.sqrt(x * x + y * y) * 6371000.0;
    }

    public static double ln(double x) {
        if (x <= 0) return Double.NaN;
        int k = 0;
        while (x >= 2) { x /= 2; k++; }
        while (x < 1) { x *= 2; k--; }
        double y = (x - 1) / (x + 1), y2 = y * y, term = y, sum = 0;
        for (int i = 1; i < 60; i += 2) {
            sum += term / i;
            term *= y2;
        }
        return k * LN2 + 2 * sum;
    }

    public static double exp(double x) {
        int k = (int) Math.floor(x / LN2);
        double r = x - k * LN2, term = 1, sum = 1;
        for (int i = 1; i < 30; i++) {
            term *= r / i;
            sum += term;
        }
        while (k > 0) { sum *= 2; k--; }
        while (k < 0) { sum /= 2; k++; }
        return sum;
    }

    /** Compass bearing in degrees (0 = north, 90 = east) of a vector east dx, north dy. */
    public static double bearing(double dx, double dy) {
        double a;
        if (dy == 0) a = dx > 0 ? 90 : dx < 0 ? 270 : 0;
        else {
            a = Math.toDegrees(atan(dx / dy));
            if (dy < 0) a += 180;
        }
        return (a + 360) % 360;
    }

    public static double atan(double x) {
        if (x < 0) return -atan(-x);
        if (x > 1) return Math.PI / 2 - atan(1 / x);
        // halve the argument twice: atan(x) = 2 atan(x / (1 + sqrt(1 + x^2)))
        int halvings = 0;
        while (x > 0.2) {
            x = x / (1 + Math.sqrt(1 + x * x));
            halvings++;
        }
        double x2 = x * x, term = x, sum = 0;
        for (int i = 1; i < 40; i += 2) {
            sum += term / i;
            term *= -x2;
        }
        return sum * (1 << halvings);
    }

    /** "49.59258, 17.24960" */
    public static String format(double lat, double lon) {
        return fmt(lat, 5) + ", " + fmt(lon, 5);
    }

    public static String fmt(double v, int decimals) {
        long m = 1;
        for (int i = 0; i < decimals; i++) m *= 10;
        long x = (long) Math.floor(Math.abs(v) * m + 0.5);
        String s = Long.toString(x / m);
        if (decimals > 0) s = s + "." + Long.toString(x % m + m).substring(1);
        return v < 0 ? "-" + s : s;
    }
}
