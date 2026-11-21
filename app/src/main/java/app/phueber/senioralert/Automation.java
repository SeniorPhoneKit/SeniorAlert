package app.phueber.senioralert;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Commands that automation apps (Trigly, Tasker, ...) send to AutomationReceiver:
 * action and extra names, and lenient parsing of the extras. Automation apps can
 * only send string extras, so every value arrives as text. Malformed input
 * parses to "nothing" (-1 or null) and the command is ignored. Pure Java.
 */
public final class Automation {
    private static final String A = "app.phueber.senioralert.action.";
    /** Switch alarms ON, like pressing ON (extra time or id). */
    public static final String ALARM_ON = A + "ALARM_ON";
    /** Switch alarms OFF, like pressing OFF (extra time or id). */
    public static final String ALARM_OFF = A + "ALARM_OFF";
    /** Alarm at this time ON (or state off): switches existing ones, else adds one (extras time, state). */
    public static final String ALARM_SET = A + "ALARM_SET";
    /** Delete alarms (extra time or id). */
    public static final String ALARM_DELETE = A + "ALARM_DELETE";
    /** Stop ringing, like the Turn off button. */
    public static final String STOP = A + "STOP";
    /** Lock with a new PIN (extra pin); only without SeniorLauncher. */
    public static final String LOCK = A + "LOCK";
    /** Unlock with the PIN or the reset PIN (extra pin). */
    public static final String UNLOCK = A + "UNLOCK";

    public static final String EXTRA_TIME = "time";
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_STATE = "state";
    public static final String EXTRA_PIN = "pin";

    private Automation() {}

    /** "7:30", "07:30" (24 h) to minutes since midnight; -1 if malformed. */
    public static int parseTime(String s) {
        if (s == null) return -1;
        s = s.trim();
        int colon = s.indexOf(':');
        if (colon < 1 || colon > 2 || s.length() != colon + 3) return -1;
        int h = digits(s.substring(0, colon));
        int m = digits(s.substring(colon + 1));
        if (h < 0 || h > 23 || m < 0 || m > 59) return -1;
        return h * 60 + m;
    }

    /** A positive alarm id from digits; -1 if malformed. */
    public static int parseId(String s) {
        if (s == null) return -1;
        s = s.trim();
        if (s.isEmpty() || s.length() > 9) return -1;
        int id = digits(s);
        return id > 0 ? id : -1;
    }

    /**
     * on/true/1/yes and off/false/0/no, any case; blank or missing gives the
     * default; anything else null (ignore the command).
     */
    public static Boolean parseState(String s, boolean dflt) {
        if (s == null || s.trim().isEmpty()) return dflt;
        switch (s.trim().toLowerCase(Locale.ROOT)) {
            case "on": case "true": case "1": case "yes": return Boolean.TRUE;
            case "off": case "false": case "0": case "no": return Boolean.FALSE;
            default: return null;
        }
    }

    /**
     * The alarms a command means: by id when an id is given, otherwise every
     * alarm at the given time. A malformed id or time matches nothing; an id
     * given never falls back to the time.
     */
    public static List<Alarm> select(List<Alarm> alarms, String id, String time) {
        List<Alarm> out = new ArrayList<>();
        if (id != null && !id.trim().isEmpty()) {
            int want = parseId(id);
            for (Alarm a : alarms) if (a.id == want) out.add(a);
            return out;
        }
        int at = parseTime(time);
        if (at < 0) return out;
        for (Alarm a : alarms) if (a.minuteOfDay() == at) out.add(a);
        return out;
    }

    /** Non-negative number from ASCII digits only; -1 otherwise. */
    private static int digits(String s) {
        if (s.isEmpty()) return -1;
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') return -1;
            n = n * 10 + (c - '0');
        }
        return n;
    }
}
