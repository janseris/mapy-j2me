package mapy;

/** Minimal NMEA 0183 parser: RMC (position, speed, course, time) and GGA (fix, satellites, accuracy, altitude). */
class Nmea {
    static double lat, lon, speedKmh, course, hdop, alt;
    static int sats, fixQuality;
    static boolean valid;
    static String time = "";

    /** Returns true when the sentence carried a valid position. Accepts any talker ($GP, $GN, $GL...). */
    static boolean parse(String s) {
        if (s.length() < 7 || s.charAt(0) != '$') return false;
        int star = s.indexOf('*');
        if (star > 0) {
            if (!checksumOk(s, star)) return false;
            s = s.substring(0, star);
        }
        String[] f = split(s);
        String type = f[0].length() >= 6 ? f[0].substring(3) : "";
        try {
            if (type.equals("RMC") && f.length > 8) {
                time = f[1];
                valid = f[2].equals("A");
                if (!valid) return false;
                lat = coord(f[3], f[4]);
                lon = coord(f[5], f[6]);
                speedKmh = f[7].length() > 0 ? Double.parseDouble(f[7]) * 1.852 : 0;
                course = f[8].length() > 0 ? Double.parseDouble(f[8]) : 0;
                return true;
            }
            if (type.equals("GGA") && f.length > 9) {
                fixQuality = f[6].length() > 0 ? Integer.parseInt(f[6]) : 0;
                sats = f[7].length() > 0 ? Integer.parseInt(f[7]) : 0;
                hdop = f[8].length() > 0 ? Double.parseDouble(f[8]) : 0;
                alt = f[9].length() > 0 ? Double.parseDouble(f[9]) : 0;
                if (fixQuality == 0) return false;
                lat = coord(f[2], f[3]);
                lon = coord(f[4], f[5]);
                return true;
            }
        } catch (Throwable e) {
            return false;
        }
        return false;
    }

    static boolean checksumOk(String s, int star) {
        int sum = 0;
        for (int i = 1; i < star; i++) sum ^= s.charAt(i);
        try {
            return Integer.parseInt(s.substring(star + 1, Math.min(s.length(), star + 3)), 16) == sum;
        } catch (Throwable e) {
            return false;
        }
    }

    /** ddmm.mmmm + hemisphere -> degrees */
    static double coord(String v, String hemi) {
        int dot = v.indexOf('.');
        int degLen = (dot < 0 ? v.length() : dot) - 2;
        double deg = Integer.parseInt(v.substring(0, degLen));
        double min = Double.parseDouble(v.substring(degLen));
        double d = deg + min / 60.0;
        return (hemi.equals("S") || hemi.equals("W")) ? -d : d;
    }

    static String[] split(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) == ',') n++;
        String[] out = new String[n];
        int start = 0, k = 0;
        for (int i = 0; i <= s.length(); i++) {
            if (i == s.length() || s.charAt(i) == ',') {
                out[k++] = s.substring(start, i);
                start = i + 1;
            }
        }
        return out;
    }

    static String describe() {
        if (lat == 0 && lon == 0) return "bez polohy (fix " + fixQuality + ", satelity " + sats + ")";
        return fmt(lat, 6) + ", " + fmt(lon, 6) + "  " + fmt(speedKmh, 1) + " km/h  kurz " + fmt(course, 0)
            + "\nsatelity " + sats + ", HDOP " + fmt(hdop, 1) + ", výška " + fmt(alt, 0) + " m, čas " + time
            + (valid ? "" : " (RMC neplatné)");
    }

    static String fmt(double v, int decimals) {
        long m = 1;
        for (int i = 0; i < decimals; i++) m *= 10;
        long x = (long) Math.floor(Math.abs(v) * m + 0.5);
        String s = Long.toString(x / m);
        if (decimals > 0) {
            String frac = Long.toString(x % m + m).substring(1);
            s = s + "." + frac;
        }
        return v < 0 ? "-" + s : s;
    }
}
