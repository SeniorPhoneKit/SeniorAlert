package app.phueber.senioralert;

import android.Manifest;
import android.app.Activity;
import android.app.Dialog;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.TimePicker;

import java.util.Calendar;
import java.util.List;

/**
 * The whole app: one screen, built in code and rebuilt from scratch on every change.
 * Deliberately plain buttons only — no Switch, no swipes, no long-press — so nothing
 * can be triggered by accident and there is nothing hidden to discover.
 */
public class MainActivity extends Activity {
    private static final int REQ_NOTIF = 1;
    private static final int REQ_SAVE = 2; // the file picker for "Save settings to a file"
    private static final int REQ_LOAD = 3; // and for "Load settings from a file"
    private static final String PREF_NOTIF_ASKED = "notif_asked";
    private static final long UNLOCK_IDLE_MS = 5_000L; // PIN prompt closes after 5 s without typing

    private final Handler handler = new Handler(Looper.getMainLooper());
    private Store store;
    private LinearLayout root;
    private boolean shownRinging;
    private String shownAlarms; // encoded list last rendered, to notice outside changes
    private boolean shownOwnLock; // own lock as last rendered; automation can flip it
    private Dialog dialog; // the one open dialog, so a starting alarm can close it

    // Polls so the stop banner appears/disappears on its own, and so a row flips to
    // OFF by itself when AlarmReceiver switches off an alarm that just rang.
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            boolean ringing = RingService.isRinging();
            // A dialog would cover the stop banner; ringing must always be stoppable.
            // Checked on every tick, so a dialog opened during ringing closes too.
            if (ringing) dismissDialog();
            // When ringing starts while this screen is open, show the ring screen
            // (we are in the foreground, so Android allows it). The system's heads-up
            // notification would otherwise cover the banner at the top, and a tap
            // meant for the banner would land on the notification instead.
            if (ringing && !shownRinging) openRingScreen();
            // The list also changes from outside: AlarmReceiver, and automation apps
            // (AutomationReceiver). The launcher's lock has its own observer.
            if (ringing != shownRinging || store.isOwnLocked() != shownOwnLock
                    || !AlarmCodec.encode(store.alarms()).equals(shownAlarms)) {
                // An open PIN or time dialog would act on the old state.
                if (store.isOwnLocked() != shownOwnLock) dismissDialog();
                rebuild();
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        // Keeps content clear of the status bar and the gesture bar; Android 15 draws
        // every app edge to edge, so without this Back sits under the gesture bar.
        page.setFitsSystemWindows(true);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, dp(12), p, dp(28));
        scroll.addView(root);
        page.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // Back is pinned below the list, always in view: many seniors do not know
        // the system back gesture. It leaves the app for the home screen.
        View back = backButton();
        NavBar.hideWhileOn(back); // SeniorNav's bar already has Back and Home
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        blp.setMargins(p, dp(8), p, dp(12));
        page.addView(back, blp);
        setContentView(page);
    }

    /**
     * Outlined like Lock, with a left arrow just before the word. A Button would pin
     * its icon to the far left edge, so arrow and label are grouped in the centre.
     */
    private View backButton() {
        int fg = getColor(R.color.text_black);
        LinearLayout b = new LinearLayout(this);
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(64));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setBackground(ripple(R.color.press_dark, getColor(R.color.card),
                getColor(R.color.outline), 32));
        b.setClickable(true);
        b.setFocusable(true);
        b.setContentDescription(getString(R.string.back));
        b.setOnClickListener(v -> goHome());

        TextView label = text(getString(R.string.back), 22);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        ImageView arrow = new ImageView(this);
        arrow.setImageResource(R.drawable.ic_arrow_back);
        arrow.setImageTintList(ColorStateList.valueOf(fg));
        arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(label.getTextSize() * 1.2f); // grows with the font setting
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(size, size);
        alp.setMarginEnd(dp(10));
        b.addView(arrow, alp);
        b.addView(label);
        return b;
    }

    private void goHome() {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(home);
        } catch (RuntimeException e) {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Self-healing: alarms are lost e.g. after a force-stop. Only arms what is
        // missing, so a pending (possibly late, inexact) alarm keeps its time.
        Scheduler.ensureArmed(this);
        // Same for the widget's midnight refresh; also redraws it, which is cheap.
        AlarmWidget.updateAll(this);
        maybeAutoRequestNotifications();
        try {
            getContentResolver().registerContentObserver(LauncherLock.STATE, false, launcherLock);
        } catch (RuntimeException ignored) {
            // no launcher: our own lock only
        }
        rebuild();
        // Opened (or returned to) while ringing: the ring screen goes on top.
        if (RingService.isRinging()) openRingScreen();
        handler.postDelayed(poll, 1000);
    }

    /** The helper locked or unlocked the home screen while this list is open. */
    private final ContentObserver launcherLock = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) {
            dismissDialog();
            rebuild();
        }
    };

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        getContentResolver().unregisterContentObserver(launcherLock);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        dismissDialog();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        rebuild();
    }

    // ---------------------------------------------------------------- screen

    private void rebuild() {
        root.removeAllViews();
        boolean locked = store.isLocked();
        shownOwnLock = store.isOwnLocked();

        shownRinging = RingService.isRinging();
        if (shownRinging) {
            Button stop = filled(getString(R.string.ringing_banner) + "\n"
                    + getString(R.string.turn_off), R.color.stop_red, 34, 140);
            stop.setOnClickListener(v -> { RingService.stop(this); rebuild(); });
            root.addView(stop, spaced());
        }

        addWarnings(locked);

        TextView title = text(getString(R.string.title_alarms), 36);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = spaced();
        tlp.topMargin = dp(20);
        tlp.bottomMargin = dp(4);
        root.addView(title, tlp);

        List<Alarm> alarms = store.alarms();
        shownAlarms = AlarmCodec.encode(alarms);
        if (alarms.isEmpty()) {
            TextView empty = text(getString(R.string.no_alarms), 24);
            empty.setTextColor(getColor(R.color.text_muted));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, dp(40), 0, dp(40));
            root.addView(empty, spaced());
        }
        for (Alarm a : alarms) addRow(a, locked);

        if (locked) {
            // Small and plain on purpose: it is for the caregiver, not the senior.
            Button unlock = new Button(this);
            unlock.setText(R.string.unlock);
            unlock.setAllCaps(false);
            unlock.setBackground(ripple(R.color.press_dark, Color.TRANSPARENT, 0, 0));
            unlock.setStateListAnimator(null);
            unlock.setTextColor(getColor(R.color.text_muted));
            unlock.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            int ph = dp(24);
            unlock.setPadding(ph, 0, ph, 0);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(40);
            lp.gravity = Gravity.CENTER_HORIZONTAL;
            // Long press only: a senior tapping around cannot open the PIN prompt by
            // accident; the helper, told by the label, holds it.
            // Locked with the home screen or on its own: the same PIN prompt;
            // Store.unlock lifts the home screen's lock with its PIN too.
            unlock.setOnLongClickListener(v -> {
                showUnlock();
                return true;
            });
            root.addView(unlock, lp);
        } else {
            LinearLayout.LayoutParams alp = spaced();
            alp.topMargin = dp(24);
            if (Alarm.listFull(alarms.size())) {
                // No Add button that would only fail: say why, and what to do.
                TextView full = text(getString(R.string.alarms_full, Alarm.MAX_COUNT), 20);
                full.setGravity(Gravity.CENTER);
                root.addView(full, alp);
            } else {
                Button add = filled(getString(R.string.add_alarm), R.color.on_green, 26, 80);
                add.setOnClickListener(v -> pickTime(12, 0, (h, m) -> {
                    // Null if automation filled the list while the picker was open;
                    // rebuild() then shows the note instead of the button.
                    Alarm a = store.add(h, m);
                    if (a != null) Scheduler.schedule(this, a);
                }));
                root.addView(add, alp);
            }
            if (LauncherLock.present(this)) {
                // One lock for the whole phone: set on the home screen, not here.
                TextView note = text(getString(R.string.lock_with_home), 18);
                note.setTextColor(getColor(R.color.text_muted));
                note.setGravity(Gravity.CENTER);
                root.addView(note, spaced());
            } else {
                Button lock = outlined(getString(R.string.lock), R.color.text_black,
                        R.color.outline, 22, 64);
                lock.setOnClickListener(v -> showLock());
                root.addView(lock, spaced());
            }
            addBackup();
        }
    }

    // ---------------------------------------------------------------- backup

    /**
     * For the helper, below everything else and only while unlocked: save the
     * alarms to a file and load them back (after a reset, on a new phone).
     * The file picker chooses where, so no storage permission is needed.
     */
    private void addBackup() {
        TextView heading = text(getString(R.string.backup_title), 26);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) heading.setAccessibilityHeading(true);
        LinearLayout.LayoutParams hlp = spaced();
        hlp.topMargin = dp(40);
        root.addView(heading, hlp);
        TextView explain = text(getString(R.string.backup_explain), 18);
        explain.setTextColor(getColor(R.color.text_muted));
        LinearLayout.LayoutParams elp = spaced();
        elp.topMargin = dp(4);
        root.addView(explain, elp);
        Button save = outlined(getString(R.string.backup_save), R.color.text_black, R.color.outline, 22, 64);
        save.setOnClickListener(v -> pickFile(BackupFiles.saveIntent(
                Backup.fileName("SeniorAlert", BackupFiles.today())), REQ_SAVE));
        root.addView(save, spaced());
        Button load = outlined(getString(R.string.backup_load), R.color.text_black, R.color.outline, 22, 64);
        load.setOnClickListener(v -> pickFile(BackupFiles.openIntent(), REQ_LOAD));
        root.addView(load, spaced());
    }

    private void pickFile(Intent picker, int request) {
        if (store.isLocked()) {
            rebuild();
            return;
        }
        try {
            startActivityForResult(picker, request);
        } catch (RuntimeException e) {
            message(getString(R.string.backup_no_picker));
        }
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != REQ_SAVE && request != REQ_LOAD) return;
        Uri uri = result == RESULT_OK && data != null ? data.getData() : null;
        // Cancelled, or locked meanwhile (the lock may change while the picker is open).
        if (uri == null || store.isLocked()) return;
        if (request == REQ_SAVE) {
            try {
                BackupFiles.write(this, uri, AlertSettingsBackup.export(this));
                message(getString(R.string.backup_saved));
            } catch (Exception e) {
                message(getString(R.string.backup_error_save));
            }
            return;
        }
        String json;
        try {
            json = BackupFiles.read(this, uri);
        } catch (Exception e) {
            message(getString(R.string.backup_error_read));
            return;
        }
        // Check the file before asking, so a bad one is refused at once.
        try {
            AlertBackup.fromSettings(Backup.settingsFor(json, getPackageName()));
        } catch (Backup.Failure f) {
            message(backupError(f.code));
            return;
        }
        dismissDialog();
        dialog = bigDialog(getString(R.string.backup_confirm), null, null,
                getString(R.string.backup_replace), R.color.on_green, v -> {
                    dismissDialog();
                    AlertSettingsBackup.Result r = AlertSettingsBackup.restore(this, json);
                    rebuild();
                    if (r.error != null) {
                        message(backupError(r.error));
                    } else if (r.skipped.length > 0) {
                        message(getString(R.string.backup_restored) + "\n"
                                + getString(R.string.backup_not_found, String.join(", ", r.skipped)));
                    } else {
                        message(getString(R.string.backup_restored));
                    }
                });
    }

    private String backupError(String code) {
        switch (code) {
            case Backup.ERROR_NEWER: return getString(R.string.backup_error_newer);
            case Backup.ERROR_NO_ENTRY:
                return getString(R.string.backup_error_no_entry, "SeniorAlert");
            case Backup.ERROR_LOCKED: return getString(R.string.backup_error_locked);
            default: return getString(R.string.backup_error_malformed);
        }
    }

    /** A short answer with one OK button. */
    private void message(String text) {
        dismissDialog();
        dialog = bigDialog(null, text, null, getString(R.string.ok), R.color.on_green,
                v -> dismissDialog(), false);
    }

    private void addRow(Alarm a, boolean locked) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        card.setPadding(p, dp(16), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card));
        bg.setStroke(dp(2), getColor(R.color.card_border));
        bg.setCornerRadius(dp(24));
        card.setBackground(bg);
        card.setElevation(dp(2)); // a soft shadow lifts the card off the page

        TextView time = new OneLineText(this, 58);
        time.setText(format(a));
        // OFF alarms fade back, so the ones that will ring stand out at a glance.
        time.setTextColor(getColor(a.enabled ? R.color.text_black : R.color.text_muted));
        card.addView(time);

        // Two-part switch, OFF | ON: both choices are always visible and you press the
        // one you want. A single button showing the state read as an action ("OFF"
        // pressed, alarm went ON).
        LinearLayout sw = new LinearLayout(this);
        sw.setPadding(dp(4), dp(4), dp(4), dp(4));
        GradientDrawable track = new GradientDrawable();
        track.setColor(getColor(R.color.off_bg));
        track.setCornerRadius(dp(40));
        sw.setBackground(track);
        sw.addView(switchHalf(a, false), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        sw.addView(switchHalf(a, true), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        LinearLayout.LayoutParams swlp = spaced();
        swlp.topMargin = dp(8);
        card.addView(sw, swlp);

        if (!locked) {
            // Secondary actions: outlined and side by side, so they don't compete with
            // ON/OFF or look like the red Turn off.
            LinearLayout actions = new LinearLayout(this);
            Button change = outlined(getString(R.string.change_time), R.color.text_black,
                    R.color.outline, 19, 56);
            change.setOnClickListener(v -> pickTime(a.hour, a.minute, (h, m) -> {
                Alarm changed = a.withTime(h, m).withEnabled(true); // picking a time means "ring then"
                store.update(changed);
                Scheduler.apply(this, changed);
            }));
            Button delete = outlined(getString(R.string.delete), R.color.red_text,
                    R.color.stop_red, 19, 56);
            delete.setOnClickListener(v -> confirmDelete(a));
            LinearLayout.LayoutParams left = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            left.rightMargin = dp(6);
            LinearLayout.LayoutParams right = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            right.leftMargin = dp(6);
            actions.addView(change, left);
            actions.addView(delete, right);
            LinearLayout.LayoutParams alp = spaced();
            alp.topMargin = dp(12);
            card.addView(actions, alp);
        }
        root.addView(card, spaced());
    }

    /**
     * One half of the OFF | ON switch. The half matching the alarm's state is filled.
     * A bell (crossed out for OFF) backs up the word: the sign phones already use
     * for "silent", and a second cue for anyone who reads the colour or word poorly.
     */
    private View switchHalf(Alarm a, boolean on) {
        boolean active = a.enabled == on;
        int fg = active ? Color.WHITE : getColor(R.color.off_text);
        LinearLayout half = new LinearLayout(this);
        half.setGravity(Gravity.CENTER);
        half.setMinimumHeight(dp(64));
        half.setPadding(dp(8), dp(6), dp(8), dp(6));
        int fill = !active ? Color.TRANSPARENT
                : getColor(on ? R.color.on_green : R.color.off_text);
        half.setBackground(ripple(active ? R.color.press_light : R.color.press_dark, fill, 0, 36));
        half.setSelected(active);
        String word = getString(on ? R.string.state_on : R.string.state_off);
        half.setContentDescription(word);
        half.setClickable(true);
        half.setOnClickListener(v -> {
            if (a.enabled == on) return; // already so: nothing to do
            Alarm changed = a.withEnabled(on);
            store.update(changed);
            Scheduler.apply(this, changed);
            rebuild();
        });

        TextView label = text(word, 26);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setTextColor(fg);
        label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        ImageView bell = new ImageView(this);
        bell.setImageResource(on ? R.drawable.ic_bell : R.drawable.ic_bell_off);
        bell.setImageTintList(ColorStateList.valueOf(fg));
        bell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        int size = Math.round(label.getTextSize() * 1.15f); // grows with the font setting
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(size, size);
        blp.setMarginEnd(dp(8));
        half.addView(bell, blp);
        half.addView(label);
        return half;
    }

    // ---------------------------------------------------------------- warnings

    /**
     * Setup problems, for the helper. Locked, only the ones that would stop the ring
     * screen and its Turn-off button from appearing are shown: those affect whether a
     * ringing alarm can be switched off, so they must not hide behind the PIN.
     */
    private void addWarnings(boolean locked) {
        String pkg = getPackageName();
        Uri pkgUri = Uri.parse("package:" + pkg);
        NotificationManager nm = getSystemService(NotificationManager.class);

        boolean fullScreenDenied = Build.VERSION.SDK_INT >= 34 && !nm.canUseFullScreenIntent();
        if (!notificationsAllowed() || (!fullScreenDenied && !RingService.ringScreenCanAppear(this))) {
            // Also covers the ringing channel turned down to silent/off in system settings.
            warning(R.string.warn_notifications, v -> fixNotifications(), R.string.steps_notif_title,
                    R.array.steps_notif, 0, R.string.steps_notif_action);
        }
        if (fullScreenDenied) {
            warning(R.string.warn_full_screen, v -> open(new Intent(
                    Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri), null),
                    R.string.steps_full_title, R.array.steps_full, 0, R.string.steps_full_action);
        }
        // Shown locked too: without exact alarms (API 31-32) an alarm rings late and
        // can only start through the ring screen's notification.
        if (!Scheduler.canScheduleExact(this) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            warning(R.string.warn_exact, v -> open(new Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri), null),
                    R.string.steps_exact_title, R.array.steps_exact, 0, R.string.steps_exact_action);
        }
        if (locked) return;

        // Without it Android shows only a small heads-up while the phone is in use,
        // instead of the ring screen (see RingService.launchScreen).
        if (!Settings.canDrawOverlays(this)) {
            warning(R.string.warn_overlay, v -> open(new Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkgUri),
                    new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)),
                    R.string.steps_overlay_title, R.array.steps_overlay, R.array.steps_overlay_notes,
                    R.string.steps_overlay_action);
        }
        if (!getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(pkg)) {
            warning(R.string.warn_battery, v -> open(
                    new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri),
                    new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)),
                    R.string.steps_battery_title, R.array.steps_battery, R.array.steps_battery_notes,
                    R.string.steps_battery_action);
        }
    }

    /**
     * A setup warning: one short line (tap to fix), and under it "How? Show the
     * steps" for the full instructions (Steps) with the same fix as its button.
     */
    private void warning(int textRes, View.OnClickListener onClick, int stepsTitle, int steps,
                         int notes, int actionLabel) {
        TextView w = text("⚠  " + getString(textRes), 19);
        int p = dp(18);
        w.setPadding(p, p, p, p);
        w.setMinHeight(dp(64));
        w.setBackground(ripple(R.color.press_dark, getColor(R.color.warn_yellow),
                getColor(R.color.warn_border), 20));
        w.setOnClickListener(onClick);
        root.addView(w, spaced());
        LinearLayout.LayoutParams lp = spaced();
        lp.topMargin = dp(8);
        root.addView(Steps.button(this, stepsTitle, steps, notes,
                new Steps.Action(actionLabel, () -> onClick.onClick(w))), lp);
    }

    private boolean notificationsAllowed() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) return false;
        return getSystemService(NotificationManager.class).areNotificationsEnabled();
    }

    private void fixNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            // Never asked, or the system still allows asking: show the system prompt.
            // Otherwise it was denied for good and only the settings page can fix it.
            if (!notifPrefs().getBoolean(PREF_NOTIF_ASKED, false)
                    || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                requestNotifications();
                return;
            }
        }
        open(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()), null);
    }

    /** Ask once on the first unlocked start; the warning box covers later cases. */
    private void maybeAutoRequestNotifications() {
        if (Build.VERSION.SDK_INT < 33 || store.isLocked()
                || notifPrefs().getBoolean(PREF_NOTIF_ASKED, false)) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) requestNotifications();
    }

    private void requestNotifications() {
        notifPrefs().edit().putBoolean(PREF_NOTIF_ASKED, true).apply();
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
    }

    private SharedPreferences notifPrefs() {
        return getPreferences(MODE_PRIVATE);
    }

    /** Opens a settings screen; some vendors lack some screens, hence the fallback. */
    private void open(Intent first, Intent fallback) {
        try {
            startActivity(first);
        } catch (RuntimeException e) {
            if (fallback == null) fallback = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
            try { startActivity(fallback); } catch (RuntimeException ignored) { }
        }
    }

    // ---------------------------------------------------------------- dialogs

    private interface TimeChosen { void chosen(int hour, int minute); }

    private void pickTime(int hour, int minute, TimeChosen then) {
        TimePicker picker = new TimePicker(this);
        picker.setIs24HourView(DateFormat.is24HourFormat(this));
        picker.setHour(hour);
        picker.setMinute(minute);
        dialog = bigDialog(null, null, picker, getString(R.string.ok), R.color.on_green, v -> {
            dismissDialog();
            then.chosen(picker.getHour(), picker.getMinute());
            rebuild();
        });
    }

    private void confirmDelete(Alarm a) {
        dialog = bigDialog(getString(R.string.delete_confirm, format(a)), null, null,
                getString(R.string.yes_delete), R.color.stop_red, v -> {
                    dismissDialog();
                    store.delete(a.id);
                    Scheduler.cancel(this, a.id);
                    rebuild();
                });
    }

    private void showUnlock() {
        LinearLayout box = dialogBox();
        EditText pin = pinField(R.string.pin_hint);
        TextView error = errorText();
        box.addView(pin);
        box.addView(error);
        // A wrong PIN keeps the dialog open.
        Dialog d = bigDialog(getString(R.string.unlock_title), getString(R.string.unlock_message),
                box, getString(R.string.ok), R.color.on_green, v -> {
                    if (store.unlock(pin.getText().toString())) {
                        dismissDialog();
                        rebuild();
                    } else {
                        pin.setText("");
                        error.setText(R.string.pin_wrong);
                    }
                });
        dialog = d;

        // If nobody types for UNLOCK_IDLE_MS, close the prompt and go back to the
        // locked list, so a PIN prompt opened by accident never lingers. Each digit
        // restarts the countdown, so a slow helper is not cut off mid-PIN.
        Runnable autoClose = d::dismiss;
        handler.postDelayed(autoClose, UNLOCK_IDLE_MS);
        pin.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(autoClose);
                handler.postDelayed(autoClose, UNLOCK_IDLE_MS);
            }
        });
        d.setOnDismissListener(x -> handler.removeCallbacks(autoClose));
    }

    private void showLock() {
        LinearLayout box = dialogBox();
        EditText pin = pinField(R.string.pin_hint);
        EditText repeat = pinField(R.string.pin_repeat_hint);
        TextView error = errorText();
        box.addView(pin);
        box.addView(repeat);
        box.addView(error);
        dialog = bigDialog(getString(R.string.lock_title), getString(R.string.lock_message),
                box, getString(R.string.lock), R.color.on_green, v -> {
                    String p1 = pin.getText().toString();
                    if (!Pin.isValid(p1)) {
                        error.setText(R.string.pin_invalid);
                    } else if (!p1.equals(repeat.getText().toString())) {
                        error.setText(R.string.pin_mismatch);
                    } else {
                        store.lock(p1);
                        dismissDialog();
                        rebuild();
                    }
                });
    }

    /**
     * A big dialog like the other Senior apps' (SeniorPhone, SeniorLauncher): a
     * white card with a bold title, an optional message and body, and two stacked
     * full-width buttons, the action on top and Cancel below. onOk decides whether
     * to close (a wrong PIN keeps it open). Scrolls, so at the largest font the
     * buttons stay reachable.
     */
    private Dialog bigDialog(String title, String message, View body, String okLabel,
                             int okColorRes, View.OnClickListener onOk) {
        return bigDialog(title, message, body, okLabel, okColorRes, onOk, true);
    }

    private Dialog bigDialog(String title, String message, View body, String okLabel,
                             int okColorRes, View.OnClickListener onOk, boolean withCancel) {
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(24);
        box.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(getColor(R.color.card));
        bg.setCornerRadius(dp(24));
        box.setBackground(bg);
        if (title != null) {
            TextView t = text(title, 26);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setGravity(Gravity.CENTER);
            box.addView(t);
        }
        if (message != null) {
            TextView m = text(message, 20);
            m.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams mlp = spaced();
            mlp.topMargin = dp(8);
            box.addView(m, mlp);
        }
        if (body != null) {
            LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            blp.gravity = Gravity.CENTER_HORIZONTAL;
            blp.topMargin = title == null && message == null ? 0 : dp(16);
            if (!(body instanceof TimePicker)) blp.width = LinearLayout.LayoutParams.MATCH_PARENT;
            box.addView(body, blp);
        }
        Button ok = filled(okLabel, okColorRes, 24, 72);
        ok.setOnClickListener(onOk);
        LinearLayout.LayoutParams olp = spaced();
        olp.topMargin = dp(24);
        box.addView(ok, olp);
        if (withCancel) {
            Button no = outlined(getString(R.string.cancel), R.color.text_black, R.color.outline, 24, 72);
            no.setOnClickListener(v -> d.dismiss());
            LinearLayout.LayoutParams nlp = spaced();
            nlp.topMargin = dp(12);
            box.addView(no, nlp);
        }

        ScrollView sv = new ScrollView(this);
        sv.addView(box);
        d.setContentView(sv);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(getResources().getDisplayMetrics().widthPixels - dp(32),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            // PIN prompts open with the keyboard up.
            boolean typing = body != null && !(body instanceof TimePicker);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | (typing ? WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE : 0));
        }
        d.show();
        return d;
    }

    private void openRingScreen() {
        try {
            startActivity(new Intent(this, RingActivity.class));
        } catch (RuntimeException e) {
            // The banner below stays as the way to stop.
        }
    }

    private void dismissDialog() {
        if (dialog != null && dialog.isShowing()) dialog.dismiss();
        dialog = null;
    }

    private LinearLayout dialogBox() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private EditText pinField(int hintRes) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        e.setHint(hintRes);
        e.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        e.setMinHeight(dp(64));
        e.setGravity(Gravity.CENTER);
        e.setTextColor(getColor(R.color.text_black));
        e.setHintTextColor(getColor(R.color.text_muted));
        return e;
    }

    private TextView errorText() {
        TextView t = text("", 20);
        t.setTextColor(getColor(R.color.red_text));
        return t;
    }

    // ---------------------------------------------------------------- helpers

    private String format(Alarm a) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, a.hour);
        c.set(Calendar.MINUTE, a.minute);
        return DateFormat.getTimeFormat(this).format(c.getTime());
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(getColor(R.color.text_black));
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return t;
    }

    /** Solid button with white text: the main actions (Add alarm, Turn off). */
    private Button filled(String label, int bgRes, int sp, int minHeightDp) {
        return pill(label, getColor(bgRes), Color.WHITE, 0, R.color.press_light, sp, minHeightDp);
    }

    /**
     * White button with an outline: secondary actions (Change time, Delete, Lock).
     * Navy words on a soft navy edge, or for Delete dark red words on a red edge.
     */
    private Button outlined(String label, int fgRes, int strokeRes, int sp, int minHeightDp) {
        return pill(label, getColor(R.color.card), getColor(fgRes), getColor(strokeRes),
                R.color.press_dark, sp, minHeightDp);
    }

    private Button pill(String label, int bg, int fg, int stroke, int pressRes, int sp, int minHeightDp) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        b.setMinHeight(dp(minHeightDp));
        b.setMinimumHeight(dp(minHeightDp));
        b.setStateListAnimator(null); // flat; the ripple alone shows the press
        b.setBackground(ripple(pressRes, bg, stroke, minHeightDp / 2));
        return b;
    }

    /**
     * Rounded background with the platform's ripple on press, so every tap gives a
     * visible "yes, that worked". stroke 0 = no outline.
     */
    private RippleDrawable ripple(int pressRes, int fill, int stroke, int radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radiusDp));
        if (stroke != 0) shape.setStroke(dp(2), stroke);
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(dp(radiusDp));
        return new RippleDrawable(ColorStateList.valueOf(getColor(pressRes)), shape, mask);
    }

    /** Full width with a gap above, so big targets never touch each other. */
    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(16);
        return lp;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
