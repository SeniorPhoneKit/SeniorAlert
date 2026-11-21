package app.phueber.senioralert;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.Log;
import android.util.SizeF;
import android.view.View;
import android.widget.RemoteViews;

import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * The home-screen widget. One cell: a bell, the next alarm's time and a word
 * (Today / Tomorrow / No alarm). Wider: the alarm list, next alarm first, ON rows
 * in green. Read-only on purpose: a tap opens the app, so the lock keeps its
 * meaning and nothing is switched by accident. What to show comes from
 * {@link AlarmOverview}; this class only draws it.
 *
 * Redrawn whenever Store writes alarms (every tap, automation command and fired
 * alarm goes through Store), from BootReceiver (boot, time, zone, 24-hour format),
 * on a language change, and once just after midnight via an inexact AlarmManager
 * window, so "Tomorrow" turns into "Today". No polling. Reads Store, which is
 * device-protected, so it also works before the first unlock.
 */
public final class AlarmWidget extends AppWidgetProvider {
    private static final String TAG = "AlarmWidget";
    static final String ACTION_REFRESH = "app.phueber.senioralert.WIDGET_REFRESH";
    /** The midnight refresh may come this late; it saves battery and needs no exact alarm. */
    private static final long MIDNIGHT_WINDOW_MS = 10 * 60 * 1000L;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        String a = intent.getAction();
        if (ACTION_REFRESH.equals(a) || Intent.ACTION_LOCALE_CHANGED.equals(a)) {
            updateAll(ctx);
            return;
        }
        super.onReceive(ctx, intent);
    }

    @Override
    public void onUpdate(Context ctx, AppWidgetManager mgr, int[] ids) {
        draw(ctx, mgr, ids);
    }

    @Override
    public void onAppWidgetOptionsChanged(Context ctx, AppWidgetManager mgr, int id, Bundle options) {
        draw(ctx, mgr, new int[] {id});
    }

    @Override
    public void onDisabled(Context ctx) {
        ctx.getSystemService(AlarmManager.class).cancel(refreshIntent(ctx));
    }

    /** Redraws every placed widget. Never throws: callers sit on the alarm path. */
    static void updateAll(Context ctx) {
        try {
            AppWidgetManager mgr = AppWidgetManager.getInstance(ctx);
            if (mgr == null) return;
            int[] ids = mgr.getAppWidgetIds(new ComponentName(ctx, AlarmWidget.class));
            if (ids != null && ids.length > 0) draw(ctx, mgr, ids);
        } catch (RuntimeException e) {
            Log.w(TAG, "widget update failed", e);
        }
    }

    private static void draw(Context ctx, AppWidgetManager mgr, int[] ids) {
        List<Alarm> alarms = new Store(ctx).alarms();
        long now = System.currentTimeMillis();
        TimeZone tz = TimeZone.getDefault();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // One view per size band; the home screen picks the one that fits.
            Map<SizeF, RemoteViews> bySize = new HashMap<>();
            bySize.put(new SizeF(40, 40), small(ctx, alarms, now, tz));
            bySize.put(new SizeF(AlarmOverview.WIDE_MIN_DP, 40), list(ctx, alarms, now, tz, 1));
            for (int n = 2; n <= AlarmOverview.MAX_ROWS; n++) {
                bySize.put(new SizeF(AlarmOverview.WIDE_MIN_DP, AlarmOverview.heightFor(n)),
                        list(ctx, alarms, now, tz, n));
            }
            mgr.updateAppWidget(ids, new RemoteViews(bySize));
        } else {
            for (int id : ids) {
                // Portrait size: the smallest width and the largest height.
                Bundle o = mgr.getAppWidgetOptions(id);
                int w = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH);
                int h = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT);
                mgr.updateAppWidget(id, AlarmOverview.isWide(w)
                        ? list(ctx, alarms, now, tz, AlarmOverview.rows(h))
                        : small(ctx, alarms, now, tz));
            }
        }
        scheduleMidnight(ctx, now, tz);
    }

    private static RemoteViews small(Context ctx, List<Alarm> alarms, long now, TimeZone tz) {
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_small);
        Alarm next = AlarmOverview.next(alarms, now, tz);
        String day = dayWord(ctx, AlarmOverview.day(next, now, tz));
        rv.setViewVisibility(R.id.bell, next != null ? View.VISIBLE : View.GONE);
        rv.setViewVisibility(R.id.bell_off, next != null ? View.GONE : View.VISIBLE);
        if (next != null) {
            String time = format(ctx, next);
            rv.setTextViewText(R.id.time, time);
            rv.setTextViewText(R.id.day, day);
            rv.setContentDescription(R.id.widget_root, ctx.getString(R.string.widget_next, time, day));
        } else {
            rv.setTextViewText(R.id.time, ctx.getString(R.string.state_off));
            rv.setTextViewText(R.id.day, day);
            rv.setContentDescription(R.id.widget_root, day);
        }
        rv.setOnClickPendingIntent(R.id.widget_root, openApp(ctx));
        return rv;
    }

    /** The alarm list with {@code slots} rows; with no alarms at all, the small face. */
    private static RemoteViews list(Context ctx, List<Alarm> alarms, long now, TimeZone tz, int slots) {
        if (alarms.isEmpty()) return small(ctx, alarms, now, tz);
        RemoteViews rv = new RemoteViews(ctx.getPackageName(), R.layout.widget_list);
        PendingIntent open = openApp(ctx);
        List<Alarm> ordered = AlarmOverview.ordered(alarms, now, tz);
        int shown = AlarmOverview.shown(ordered.size(), slots);
        int more = AlarmOverview.more(ordered.size(), slots);
        // Hosts re-apply every action to the existing view (reapply), so without
        // this an update would append a second set of rows below the first.
        rv.removeAllViews(R.id.widget_root);
        for (int i = 0; i < slots; i++) {
            if (more > 0 && i == slots - 1) {
                RemoteViews line = new RemoteViews(ctx.getPackageName(), R.layout.widget_more);
                line.setTextViewText(R.id.more,
                        ctx.getResources().getQuantityString(R.plurals.widget_more, more, more));
                line.setOnClickPendingIntent(R.id.more, open);
                rv.addView(R.id.widget_root, line);
                continue;
            }
            RemoteViews row = new RemoteViews(ctx.getPackageName(), R.layout.widget_row);
            if (i < shown) {
                fillRow(ctx, row, ordered.get(i), now, tz);
                // The root opens the app too; on the row as well so a row that a host
                // or TalkBack treats as its own target still opens it.
                row.setOnClickPendingIntent(R.id.row, open);
            } else {
                row.setViewVisibility(R.id.row, View.INVISIBLE); // keeps rows the same height
            }
            rv.addView(R.id.widget_root, row);
        }
        Alarm next = AlarmOverview.next(alarms, now, tz);
        rv.setContentDescription(R.id.widget_root, next == null
                ? ctx.getString(R.string.widget_none)
                : ctx.getString(R.string.widget_next, format(ctx, next),
                        dayWord(ctx, AlarmOverview.day(next, now, tz))));
        rv.setOnClickPendingIntent(R.id.widget_root, open);
        return rv;
    }

    private static void fillRow(Context ctx, RemoteViews row, Alarm a, long now, TimeZone tz) {
        String time = format(ctx, a);
        row.setTextViewText(R.id.row_time, time);
        if (a.enabled) {
            String day = dayWord(ctx, AlarmOverview.day(a, now, tz));
            String on = ctx.getString(R.string.state_on);
            row.setInt(R.id.row, "setBackgroundResource", R.drawable.widget_row_on);
            row.setTextViewText(R.id.row_state, on);
            row.setTextViewText(R.id.row_day, day);
            // White on the app's green, the same in day and night colours, so it can be
            // resolved here rather than by the home screen.
            int white = ctx.getColor(R.color.widget_on_text);
            row.setTextColor(R.id.row_time, white);
            row.setTextColor(R.id.row_state, white);
            row.setTextColor(R.id.row_day, white);
            row.setContentDescription(R.id.row, time + ", " + on + ", " + day);
        } else {
            String off = ctx.getString(R.string.state_off);
            row.setTextViewText(R.id.row_state, off);
            row.setViewVisibility(R.id.row_day, View.INVISIBLE); // keeps the columns aligned
            row.setContentDescription(R.id.row, time + ", " + off);
        }
    }

    private static String dayWord(Context ctx, AlarmOverview.Day d) {
        switch (d) {
            case TODAY: return ctx.getString(R.string.widget_today);
            case TOMORROW: return ctx.getString(R.string.widget_tomorrow);
            default: return ctx.getString(R.string.widget_none);
        }
    }

    /** Same format as the list in MainActivity (12- or 24-hour, as the phone is set). */
    private static String format(Context ctx, Alarm a) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, a.hour);
        c.set(Calendar.MINUTE, a.minute);
        return DateFormat.getTimeFormat(ctx).format(c.getTime());
    }

    /**
     * Every tap opens the app. One intent for every widget id and view: request
     * code 0 and identical extras-free intents, so ids cannot clash and
     * FLAG_UPDATE_CURRENT only refreshes the same token. Immutable, as required
     * from Android 12.
     */
    private static PendingIntent openApp(Context ctx) {
        return PendingIntent.getActivity(ctx, 0,
                new Intent(ctx, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** Wakes nothing: an inexact, non-wakeup window just after the next local midnight. */
    private static void scheduleMidnight(Context ctx, long now, TimeZone tz) {
        long at = AlarmOverview.nextMidnight(now, tz) + 5_000L;
        ctx.getSystemService(AlarmManager.class)
                .setWindow(AlarmManager.RTC, at, MIDNIGHT_WINDOW_MS, refreshIntent(ctx));
    }

    private static PendingIntent refreshIntent(Context ctx) {
        return PendingIntent.getBroadcast(ctx, 0,
                new Intent(ctx, AlarmWidget.class).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
}
