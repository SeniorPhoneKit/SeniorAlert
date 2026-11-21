package app.phueber.senioralert;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Fired by AlarmManager at an alarm's time (see Scheduler). One-shot: switches the alarm off, then rings. */
public final class AlarmReceiver extends BroadcastReceiver {
    private static final String TAG = "AlarmReceiver";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!Scheduler.ACTION_FIRE.equals(intent.getAction())) return;
        int id = intent.getIntExtra(Scheduler.EXTRA_ALARM_ID, -1);
        Store store = new Store(ctx);
        Alarm a = store.find(id);
        // Deleted or switched off after it was armed: a stale fire, stay silent.
        if (a == null || !a.enabled) return;

        // Alarms ring once and must be switched on again by hand. Persist OFF BEFORE
        // ringing, so a crash or kill while ringing can never leave a stale ON alarm
        // that the list shows as armed but AlarmManager no longer holds.
        try {
            store.update(a.withEnabled(false));
        } catch (RuntimeException e) {
            Log.e(TAG, "could not switch alarm off", e); // still ring now
        }
        // The alarm's own wake lock ends when onReceive returns; keep the CPU awake
        // until the service has started ringing (released there, 30 s at most).
        RingService.holdCpuUntilRinging(ctx);
        try {
            RingService.start(ctx, id);
        } catch (RuntimeException e) {
            // e.g. ForegroundServiceStartNotAllowedException: on API 31-32 with exact
            // alarms denied, the inexact fallback alarm does not allow the start. Ring
            // through the ring screen instead: its full-screen notification is allowed,
            // and the screen, once in front, starts the service. Nothing plays until
            // then, so the stop invariant is untouched.
            Log.e(TAG, "could not start ringing", e);
            if (RingService.ringViaScreen(ctx, id)) return;
            // Not even the notification can appear: the alarm did not ring. Do not let
            // it look as if it had: put it back ON (armed for its next occurrence), so
            // the list stays truthful instead of silently showing OFF.
            try {
                store.update(a);
                Scheduler.schedule(ctx, a);
            } catch (RuntimeException e2) {
                Log.e(TAG, "could not re-arm", e2);
            }
        }
    }
}
