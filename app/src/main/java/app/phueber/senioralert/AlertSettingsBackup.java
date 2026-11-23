package app.phueber.senioralert;

import android.content.Context;

import java.util.List;

/**
 * Saves and restores SeniorAlert's settings (its alarms) as a backup file,
 * for the Backup buttons in MainActivity and for SeniorLauncher's "all Senior
 * apps" backup through BackupProvider. The content is AlertBackup's, the file
 * Backup's.
 */
final class AlertSettingsBackup {
    private AlertSettingsBackup() {}

    /** The outcome of a restore: error is null when restored. */
    static final class Result {
        final String error;
        final String[] skipped;

        Result(String error, String[] skipped) {
            this.error = error;
            this.skipped = skipped;
        }
    }

    /** This app's one-entry backup file. */
    static String export(Context c) {
        return Backup.writeOne(BackupFiles.now(), c.getPackageName(), BackupFiles.appVersion(c),
                AlertBackup.toSettings(new Store(c).alarms()));
    }

    /**
     * Replaces the alarms with the file's (this app's entry of a one-app or
     * all-apps file) and arms the ON ones for their next occurrence, as if the
     * helper had just switched them ON: a time already past today rings
     * tomorrow, never at once. Refused while locked; a file that cannot be read
     * changes nothing. Call on the main thread, like every other alarm change.
     */
    static Result restore(Context c, String json) {
        Store store = new Store(c);
        if (store.isLocked()) return new Result(Backup.ERROR_LOCKED, null);
        List<AlertBackup.Item> items;
        try {
            items = AlertBackup.fromSettings(Backup.settingsFor(json, c.getPackageName()));
        } catch (Backup.Failure f) {
            return new Result(f.code, null);
        }
        for (Alarm a : store.replaceAll(items)) Scheduler.cancel(c, a.id);
        for (Alarm a : store.alarms()) {
            try {
                if (a.enabled) Scheduler.schedule(c, a);
            } catch (RuntimeException e) {
                // Like ensureArmed: one alarm AlarmManager refuses must not stop the rest;
                // the next app open arms it.
                android.util.Log.e("AlertSettingsBackup", "could not arm " + a, e);
            }
        }
        return new Result(null, new String[0]);
    }
}
