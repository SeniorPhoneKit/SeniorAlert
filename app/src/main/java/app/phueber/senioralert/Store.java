package app.phueber.senioralert;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.List;

/**
 * All persistent state: the alarm list, the lock flag and the PIN hash.
 *
 * Lives in device-protected storage so that after a reboot the alarms can be
 * re-armed (and ring) before the phone is unlocked for the first time
 * (LOCKED_BOOT_COMPLETED / direct boot). The data is not sensitive.
 *
 * Every alarm write redraws the home-screen widget (AlarmWidget), so each
 * caller (taps, automation, a fired alarm) keeps it current without knowing it.
 *
 * Methods are synchronized: receivers, the service and the activity all run on
 * the main thread today, but nothing should depend on that.
 */
public final class Store {
    private static final String FILE = "senioralert";
    private static final String K_ALARMS = "alarms";
    private static final String K_NEXT_ID = "next_id";
    private static final String K_LOCKED = "locked";
    private static final String K_SALT = "pin_salt";
    private static final String K_HASH = "pin_hash";
    private static final String K_ARMED_PREFIX = "armed_"; // + id: last armed trigger time

    private final SharedPreferences prefs;
    private final Context app;

    public Store(Context context) {
        app = context.getApplicationContext();
        Context dp = app.createDeviceProtectedStorageContext();
        prefs = dp.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** Sorted by time of day. */
    public synchronized List<Alarm> alarms() {
        return AlarmCodec.decode(prefs.getString(K_ALARMS, ""));
    }

    public synchronized Alarm find(int id) {
        for (Alarm a : alarms()) if (a.id == id) return a;
        return null;
    }

    /**
     * Adds a new, enabled alarm and returns it, or null if the list is full
     * (Alarm.MAX_COUNT). Caller must schedule it.
     */
    public synchronized Alarm add(int hour, int minute) {
        return add(hour, minute, true);
    }

    /**
     * Adds a new alarm, ON or OFF, and returns it, or null if the list is full
     * (Alarm.MAX_COUNT). Caller must schedule it if ON.
     */
    public synchronized Alarm add(int hour, int minute, boolean enabled) {
        List<Alarm> list = alarms();
        if (Alarm.listFull(list.size())) return null;
        int id = prefs.getInt(K_NEXT_ID, 1);
        Alarm a = new Alarm(id, hour, minute, enabled);
        list.add(a);
        AlarmCodec.sort(list);
        prefs.edit().putInt(K_NEXT_ID, id + 1).putString(K_ALARMS, AlarmCodec.encode(list)).commit();
        AlarmWidget.updateAll(app);
        return a;
    }

    /** Replaces the alarm with the same id. Caller must (re)schedule or cancel it. */
    public synchronized void update(Alarm alarm) {
        List<Alarm> list = alarms();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id == alarm.id) list.set(i, alarm);
        }
        AlarmCodec.sort(list);
        prefs.edit().putString(K_ALARMS, AlarmCodec.encode(list)).commit();
        AlarmWidget.updateAll(app);
    }

    /** Caller must cancel the scheduled alarm. */
    public synchronized void delete(int id) {
        List<Alarm> list = alarms();
        list.removeIf(a -> a.id == id);
        prefs.edit().putString(K_ALARMS, AlarmCodec.encode(list)).remove(K_ARMED_PREFIX + id).commit();
        AlarmWidget.updateAll(app);
    }

    /** Remembers when the alarm was last armed for (Scheduler), for re-arming later. */
    public synchronized void setArmedAt(int id, long at) {
        prefs.edit().putLong(K_ARMED_PREFIX + id, at).commit();
    }

    /** The time the alarm was last armed for, 0 if unknown. */
    public synchronized long armedAt(int id) {
        return prefs.getLong(K_ARMED_PREFIX + id, 0L);
    }

    /**
     * Locked for editing: the helper locked SeniorLauncher's home screen, or
     * (without the launcher) this app's own PIN lock is on.
     */
    public boolean isLocked() {
        return isLockedByLauncher() || isOwnLocked();
    }

    /** The home screen is locked; its PIN unlocks it, here or there. */
    public boolean isLockedByLauncher() {
        return LauncherLock.locked(app);
    }

    /** This app's own PIN lock, used when SeniorLauncher is not installed. */
    public synchronized boolean isOwnLocked() {
        return prefs.getBoolean(K_LOCKED, false);
    }

    /** Locks with a new PIN. Caller has checked Pin.isValid(pin). */
    public synchronized void lock(String pin) {
        String salt = Pin.newSalt();
        prefs.edit().putString(K_SALT, salt).putString(K_HASH, Pin.hash(salt, pin))
                .putBoolean(K_LOCKED, true).commit();
    }

    /**
     * Lifts every lock that holds this app, the home screen's and its own, if
     * the PIN is right; returns whether nothing is locked any more.
     */
    public boolean unlock(String pin) {
        if (isLockedByLauncher() && !LauncherLock.unlock(app, pin)) return false;
        synchronized (this) {
            if (!prefs.getBoolean(K_LOCKED, false)) return true;
            if (!Pin.matches(prefs.getString(K_SALT, null), prefs.getString(K_HASH, null), pin)) {
                return false;
            }
            prefs.edit().putBoolean(K_LOCKED, false).remove(K_SALT).remove(K_HASH).commit();
            return true;
        }
    }
}
