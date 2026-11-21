package app.phueber.senioralert;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;

import java.util.Locale;

/**
 * Whether SeniorNav's "Back & Home" bar is on screen. Its big Back and Home sit
 * at the bottom of every screen, so our own pinned Back button would only repeat
 * them: it is hidden while the bar shows and comes back when it does not.
 *
 * SeniorNav says so itself through a read-only provider
 * (content://app.phueber.seniornav.bar/state, column "visible"), which also
 * counts a bar paused by automation or not running as not there, and notifies
 * on every change, so the button follows live. An older SeniorNav without the
 * provider: the bar counts as there while its service is switched on in the
 * accessibility settings (re-checked whenever the window gets focus).
 */
final class NavBar {
    static final String PACKAGE = "app.phueber.seniornav";
    static final String AUTHORITY = "app.phueber.seniornav.bar";
    /** {@link #decide}'s "the provider gave no answer". */
    static final int UNKNOWN = -1;

    private NavBar() {}

    /** content://app.phueber.seniornav.bar/state (built on use: Uri is Android-only). */
    static Uri state() {
        return Uri.parse("content://" + AUTHORITY + "/state");
    }

    /** Pure: whether the enabled-services setting lists SeniorNav's bar service. */
    static boolean listed(String enabledServices) {
        if (enabledServices == null) return false;
        for (String entry : enabledServices.split(":")) {
            if (entry.trim().toLowerCase(Locale.ROOT).startsWith(PACKAGE + "/")) return true;
        }
        return false;
    }

    /** Pure: the provider's answer (1/0) if there is one, else the settings list. */
    static boolean decide(int visible, String enabledServices) {
        return visible == UNKNOWN ? listed(enabledServices) : visible == 1;
    }

    static boolean on(Context c) {
        int visible = providerVisible(c);
        String enabled = null;
        if (visible == UNKNOWN) {
            try {
                enabled = Settings.Secure.getString(c.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            } catch (RuntimeException e) {
                return false; // unreadable: keep our own Back
            }
        }
        return decide(visible, enabled);
    }

    /** 1 or 0 from SeniorNav's provider, UNKNOWN if it is missing, not SeniorNav's or fails. */
    private static int providerVisible(Context c) {
        try {
            ProviderInfo info = c.getPackageManager().resolveContentProvider(AUTHORITY, 0);
            if (info == null || !PACKAGE.equals(info.packageName)) return UNKNOWN;
            try (Cursor cur = c.getContentResolver().query(state(), null, null, null, null)) {
                if (cur == null || !cur.moveToFirst()) return UNKNOWN;
                int col = cur.getColumnIndex("visible");
                return col < 0 ? UNKNOWN : (cur.getInt(col) == 1 ? 1 : 0);
            }
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    /**
     * Hides {@code back} while the bar shows: follows SeniorNav's provider live
     * while the view is attached, and re-checks each time the window gets focus.
     */
    static void hideWhileOn(View back) {
        Runnable update = () -> back.setVisibility(on(back.getContext()) ? View.GONE : View.VISIBLE);
        update.run();
        back.getViewTreeObserver().addOnWindowFocusChangeListener(focused -> {
            if (focused) update.run();
        });
        ContentObserver observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) {
                update.run();
            }
        };
        back.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {
                try {
                    // notifyForDescendants: the provider may notify the authority or the row.
                    v.getContext().getContentResolver().registerContentObserver(state(), true, observer);
                } catch (RuntimeException ignored) {
                    // no SeniorNav provider: focus changes still update
                }
                update.run();
            }

            @Override public void onViewDetachedFromWindow(View v) {
                v.getContext().getContentResolver().unregisterContentObserver(observer);
            }
        });
    }
}
