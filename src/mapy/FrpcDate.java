package mapy;


/**
 * A FastRPC DATETIME value. The backend only needs it in one place (gettripinfos, the
 * departure time of a ride), and it always comes from an ISO string like
 * "2026-09-30T20:45:00+02:00" that the backend itself sent earlier - so all the date
 * maths is done here by hand, without java.util.Calendar (whose time zone support on
 * old phones is unreliable).
 */
public class FrpcDate {
    public long unixSeconds;
    /** Offset from UTC in quarter-hours (+02:00 = 8). */
    public int zone;
    public int year, month, day, hour, minute, second;
    /** 0 = Sunday ... 6 = Saturday. */
    public int weekday;

    /** Parses "YYYY-MM-DDTHH:MM:SS+HH:MM" (or "...Z"). */
    public static FrpcDate fromIso(String iso) {
        FrpcDate d = new FrpcDate();
        d.year = Integer.parseInt(iso.substring(0, 4));
        d.month = Integer.parseInt(iso.substring(5, 7));
        d.day = Integer.parseInt(iso.substring(8, 10));
        d.hour = Integer.parseInt(iso.substring(11, 13));
        d.minute = Integer.parseInt(iso.substring(14, 16));
        d.second = iso.length() >= 19 ? Integer.parseInt(iso.substring(17, 19)) : 0;

        int offsetMin = 0;
        if (iso.length() >= 25) {
            char sign = iso.charAt(19);
            int oh = Integer.parseInt(iso.substring(20, 22));
            int om = Integer.parseInt(iso.substring(23, 25));
            offsetMin = oh * 60 + om;
            if (sign == '-') offsetMin = -offsetMin;
        }
        d.zone = offsetMin / 15;

        long days = daysFromCivil(d.year, d.month, d.day);
        d.weekday = (int) ((days % 7 + 11) % 7); // 1970-01-01 was a Thursday (4)
        d.unixSeconds = days * 86400L + d.hour * 3600L + d.minute * 60L + d.second - offsetMin * 60L;
        return d;
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (Howard Hinnant's algorithm). */
    static long daysFromCivil(int y, int m, int d) {
        y -= (m <= 2) ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153 * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    /** The 5 packed wall-clock bytes FastRPC stores after the timestamp. */
    long packedFields() {
        return (long) weekday
            | ((long) second << 3)
            | ((long) minute << 9)
            | ((long) hour << 15)
            | ((long) day << 20)
            | ((long) month << 25)
            | ((long) (year - 1600) << 29);
    }

    public String toString() {
        return "FrpcDate(" + unixSeconds + ", zone " + zone + ")";
    }
}
