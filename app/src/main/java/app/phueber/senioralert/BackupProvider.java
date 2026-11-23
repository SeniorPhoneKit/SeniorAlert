package app.phueber.senioralert;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Lets SeniorLauncher back up and restore this app's settings together with
 * the other Senior apps' (its "Save all Senior apps' settings").
 *
 * content://app.phueber.senioralert.backup
 * call("export") -> Bundle with String "json": this app's one-entry file (Backup).
 * call("import", json) -> Bundle with boolean "ok"; when restored String[]
 * "skipped" (always empty here), else String "error", a Backup.ERROR_* code
 * ("locked" while the lock is on; nothing changes then).
 *
 * Only SeniorLauncher and this app itself are answered (Backup.callerAllowed,
 * a check of the calling package; "not_allowed" otherwise): a sanity check,
 * not security, like the lock it respects.
 */
public final class BackupProvider extends ContentProvider {
    @Override public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        Context c = getContext();
        Bundle out = new Bundle();
        if (c == null) throw new IllegalStateException("not attached");
        if (!Backup.METHOD_EXPORT.equals(method) && !Backup.METHOD_IMPORT.equals(method)) {
            throw new UnsupportedOperationException(method);
        }
        if (!Backup.callerAllowed(getCallingPackage(), c.getPackageName())) {
            out.putBoolean(Backup.EXTRA_OK, false);
            out.putString(Backup.EXTRA_ERROR, Backup.ERROR_NOT_ALLOWED);
            return out;
        }
        if (Backup.METHOD_EXPORT.equals(method)) {
            out.putString(Backup.EXTRA_JSON, AlertSettingsBackup.export(c));
            return out;
        }
        AlertSettingsBackup.Result r = onMain(() -> AlertSettingsBackup.restore(c, arg == null ? "" : arg));
        out.putBoolean(Backup.EXTRA_OK, r.error == null);
        if (r.error == null) out.putStringArray(Backup.EXTRA_SKIPPED, r.skipped);
        else out.putString(Backup.EXTRA_ERROR, r.error);
        return out;
    }

    /**
     * Runs the restore on the main thread, where every other alarm change runs
     * (taps, AlarmReceiver, automation), so Store's read-modify-write is never
     * interleaved with one of them.
     */
    private static AlertSettingsBackup.Result onMain(java.util.function.Supplier<AlertSettingsBackup.Result> job) {
        if (Looper.myLooper() == Looper.getMainLooper()) return job.get();
        AlertSettingsBackup.Result[] box = new AlertSettingsBackup.Result[1];
        RuntimeException[] failed = new RuntimeException[1];
        CountDownLatch done = new CountDownLatch(1);
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                box[0] = job.get();
            } catch (RuntimeException e) {
                failed[0] = e;
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("restore timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted");
        }
        if (failed[0] != null) throw failed[0];
        return box[0];
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        throw new UnsupportedOperationException("call only");
    }

    @Override public String getType(Uri uri) {
        return null;
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("call only");
    }

    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException("call only");
    }

    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("call only");
    }
}
