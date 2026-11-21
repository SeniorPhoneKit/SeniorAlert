package app.phueber.senioralert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;

/**
 * Commands from automation apps (Trigly, Tasker, ...), see {@link Automation}.
 * Each does exactly what the same tap in MainActivity does (Store, then
 * Scheduler), so the list, the status-bar alarm icon and the next alarm follow
 * as if the user had done it; an open list notices the change on its next poll.
 *
 * Runs on the main thread on purpose, like AlarmReceiver and MainActivity: Store's
 * read-modify-write is then never interleaved with a tap in the list. The work is
 * a few small preference writes, far inside the receiver time limit.
 *
 * The lock is a guard rail, not security: automation acts for the helper, so
 * setup changes work while locked. STOP uses the one stop routine every other
 * stop path uses and adds no step to it.
 */
public final class AutomationReceiver extends BroadcastReceiver {
    private static final String TAG = "AutomationReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        try {
            handle(ctx, action, intent);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not run " + action, e);
        }
    }

    private static void handle(Context ctx, String action, Intent intent) {
        if (Automation.STOP.equals(action)) {
            RingService.stop(ctx);
            return;
        }
        Store store = new Store(ctx);
        String id = intent.getStringExtra(Automation.EXTRA_ID);
        String time = intent.getStringExtra(Automation.EXTRA_TIME);
        String pin = intent.getStringExtra(Automation.EXTRA_PIN);
        switch (action) {
            case Automation.ALARM_ON:
                switchTo(ctx, store, Automation.select(store.alarms(), id, time), true);
                break;
            case Automation.ALARM_OFF:
                switchTo(ctx, store, Automation.select(store.alarms(), id, time), false);
                break;
            case Automation.ALARM_SET:
                set(ctx, store, time, intent.getStringExtra(Automation.EXTRA_STATE));
                break;
            case Automation.ALARM_DELETE:
                for (Alarm a : Automation.select(store.alarms(), id, time)) {
                    store.delete(a.id);
                    Scheduler.cancel(ctx, a.id);
                }
                break;
            case Automation.LOCK:
                // With SeniorLauncher the home screen's lock is the one lock (the list
                // hides its Lock button); a second, own PIN here could disagree with it.
                if (LauncherLock.present(ctx)) Log.w(TAG, "LOCK ignored: lock SeniorLauncher instead");
                else if (Pin.isValid(pin)) store.lock(pin);
                else Log.w(TAG, "LOCK ignored: PIN must be 4-8 digits");
                break;
            case Automation.UNLOCK:
                if (pin == null || !store.unlock(pin.trim())) Log.w(TAG, "UNLOCK: wrong PIN");
                break;
            default:
                break;
        }
    }

    /** Like pressing ON or OFF: an alarm already in that state is left alone. */
    private static void switchTo(Context ctx, Store store, List<Alarm> alarms, boolean on) {
        for (Alarm a : alarms) {
            if (a.enabled == on) continue; // re-arming would move a late pending alarm to tomorrow
            Alarm changed = a.withEnabled(on);
            store.update(changed);
            Scheduler.apply(ctx, changed);
        }
    }

    /** Switches the alarms at this time, or adds one there if there is none. */
    private static void set(Context ctx, Store store, String time, String stateExtra) {
        int at = Automation.parseTime(time);
        Boolean on = Automation.parseState(stateExtra, true);
        if (at < 0 || on == null) {
            Log.w(TAG, "ALARM_SET ignored: time HH:MM, state on/off");
            return;
        }
        List<Alarm> existing = Automation.select(store.alarms(), null, time);
        if (!existing.isEmpty()) {
            switchTo(ctx, store, existing, on);
            return;
        }
        Alarm a = store.add(at / 60, at % 60, on);
        if (on) Scheduler.schedule(ctx, a);
    }
}
