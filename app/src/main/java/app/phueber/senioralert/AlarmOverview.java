package app.phueber.senioralert;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Comparator;
import java.util.List;
import java.util.TimeZone;

/**
 * What the home-screen widget shows, worked out without Android: the next alarm,
 * whether it rings today or tomorrow, the list order (next alarm first), how
 * many rows fit and whether the last one says "+N more". Pure Java, unit tested; {@link AlarmWidget} only draws it.
 */
public final class AlarmOverview {
    /** The word under the next alarm's time. */
    public enum Day { TODAY, TOMORROW, NONE }

    /** Narrower than this (dp) the widget is the small one-cell face. */
    public static final int WIDE_MIN_DP = 150;
    /** Smallest height (dp) of one list row; rows share the height evenly. */
    public static final int ROW_DP = 64;
    /** At most this many rows, however tall the widget. */
    public static final int MAX_ROWS = 8;
    /** Padding (dp) around the list, top and bottom together. */
    public static final int PAD_DP = 8;

    private AlarmOverview() {}

    /** The enabled alarm that rings first, or null if none is ON. Ties: the lower id. */
    public static Alarm next(List<Alarm> alarms, long now, TimeZone tz) {
        Alarm best = null;
        long bestAt = Long.MAX_VALUE;
        for (Alarm a : alarms) {
            if (!a.enabled) continue;
            long at = NextTrigger.next(a.hour, a.minute, now, tz);
            if (at < bestAt || (at == bestAt && a.id < best.id)) {
                best = a;
                bestAt = at;
            }
        }
        return best;
    }

    /** TODAY or TOMORROW for when {@code a} rings next, NONE for no (or an OFF) alarm. */
    public static Day day(Alarm a, long now, TimeZone tz) {
        if (a == null || !a.enabled) return Day.NONE;
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        int today = c.get(Calendar.DAY_OF_YEAR);
        int year = c.get(Calendar.YEAR);
        c.setTimeInMillis(NextTrigger.next(a.hour, a.minute, now, tz));
        return c.get(Calendar.DAY_OF_YEAR) == today && c.get(Calendar.YEAR) == year
                ? Day.TODAY : Day.TOMORROW;
    }

    /** ON alarms in the order they will ring (next first), then OFF ones by time of day. */
    public static List<Alarm> ordered(List<Alarm> alarms, long now, TimeZone tz) {
        List<Alarm> on = new ArrayList<>();
        List<Alarm> off = new ArrayList<>();
        for (Alarm a : alarms) (a.enabled ? on : off).add(a);
        on.sort(Comparator.<Alarm>comparingLong(a -> NextTrigger.next(a.hour, a.minute, now, tz))
                .thenComparingInt(a -> a.id));
        off.sort(Comparator.comparingInt(Alarm::minuteOfDay).thenComparingInt(a -> a.id));
        on.addAll(off);
        return on;
    }

    /** Start of the next local day: when "Tomorrow" becomes "Today". */
    public static long nextMidnight(long now, TimeZone tz) {
        Calendar c = Calendar.getInstance(tz);
        c.setTimeInMillis(now);
        c.add(Calendar.DAY_OF_YEAR, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        return c.getTimeInMillis();
    }

    /** Whether a widget this wide (dp) shows the list instead of the small face. */
    public static boolean isWide(float widthDp) {
        return widthDp >= WIDE_MIN_DP;
    }

    /** How many list rows fit in this height (dp): at least 1, at most MAX_ROWS. */
    public static int rows(float heightDp) {
        int n = (int) ((heightDp - PAD_DP) / ROW_DP);
        return Math.max(1, Math.min(MAX_ROWS, n));
    }

    /**
     * How many alarms get a row of their own when {@code alarms} alarms meet
     * {@code slots} rows. All of them if they fit; otherwise the last row is given
     * up for the "+N more" line, so a senior knows there are more. With one row
     * there is no room for both, and the next alarm matters more than the count.
     */
    public static int shown(int alarms, int slots) {
        if (alarms <= slots) return Math.max(0, alarms);
        return slots <= 1 ? Math.max(0, slots) : slots - 1;
    }

    /** How many alarms the "+N more" line stands for; 0 means no such line. */
    public static int more(int alarms, int slots) {
        if (slots <= 1) return 0;
        return Math.max(0, alarms - shown(alarms, slots));
    }

    /** Smallest height (dp) that fits {@code n} rows; inverse of {@link #rows}. */
    public static int heightFor(int n) {
        return n * ROW_DP + PAD_DP;
    }
}
