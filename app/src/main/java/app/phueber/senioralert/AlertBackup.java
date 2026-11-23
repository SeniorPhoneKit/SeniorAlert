package app.phueber.senioralert;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SeniorAlert's part of the settings backup (see Backup): the alarms, each a
 * time and ON/OFF. Pure (no android.* imports), unit tested.
 *
 * <pre>{"alarms": [{"hour": 8, "minute": 0, "on": true}, {"hour": 19, "minute": 0, "on": false}]}</pre>
 *
 * Ids are not saved: they are this phone's keys for AlarmManager, and a
 * restore gives every alarm a new one. The lock and its PIN are not part of
 * a backup. Reading is all or nothing: one bad alarm refuses the whole file.
 */
final class AlertBackup {
    private AlertBackup() {}

    /** One alarm as saved: no id. */
    static final class Item {
        final int hour;
        final int minute;
        final boolean on;

        Item(int hour, int minute, boolean on) {
            this.hour = hour;
            this.minute = minute;
            this.on = on;
        }

        @Override public boolean equals(Object o) {
            if (!(o instanceof Item)) return false;
            Item i = (Item) o;
            return hour == i.hour && minute == i.minute && on == i.on;
        }

        @Override public int hashCode() {
            return (hour * 60 + minute) * 2 + (on ? 1 : 0);
        }

        @Override public String toString() {
            return hour + ":" + minute + (on ? " on" : " off");
        }
    }

    static Map<String, Object> toSettings(List<Alarm> alarms) {
        List<Object> list = new ArrayList<>();
        for (Alarm a : alarms) {
            Map<String, Object> one = new LinkedHashMap<>();
            one.put("hour", (long) a.hour);
            one.put("minute", (long) a.minute);
            one.put("on", a.enabled);
            list.add(one);
        }
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("alarms", list);
        return s;
    }

    /**
     * The alarms of a settings object. No "alarms" is an empty list; an alarm
     * without "on" is OFF (nothing rings that the file does not say should).
     * A missing or out-of-range time, a wrong type or more than
     * Alarm.MAX_COUNT alarms make the file malformed.
     */
    static List<Item> fromSettings(Map<String, Object> s) throws Backup.Failure {
        List<Object> raw = Backup.list(s, "alarms");
        if (raw.size() > Alarm.MAX_COUNT) {
            throw new Backup.Failure(Backup.ERROR_MALFORMED, raw.size() + " alarms");
        }
        List<Item> out = new ArrayList<>();
        for (int n = 0; n < raw.size(); n++) {
            Map<String, Object> a = Backup.object(raw.get(n), "alarm " + n);
            if (a.get("hour") == null || a.get("minute") == null) {
                throw new Backup.Failure(Backup.ERROR_MALFORMED, "alarm " + n + " has no time");
            }
            out.add(new Item(Backup.number(a, "hour", 0, 0, 23), Backup.number(a, "minute", 0, 0, 59),
                    Backup.bool(a, "on", false)));
        }
        return out;
    }
}
