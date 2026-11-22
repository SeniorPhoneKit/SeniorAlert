# Architecture

Package `app.phueber.senioralert`. Two screens: `MainActivity` (alarm list,
lock) and `RingActivity` (the ringing screen). Everything else is plumbing.
Plain Java, platform APIs only. Keep this file current with every change.

## Files

| File | Role |
| --- | --- |
| `Alarm` | Immutable one-shot alarm (id > 0, hour, minute, enabled). Pure Java, validated. `MAX_COUNT` (50) is the most alarms the list holds (`listFull`), far below AlarmManager's ~500 alarms per app. |
| `AlarmCodec` | List of alarms to/from one string `id:h:m:e;...`. Skips malformed entries, drops duplicate ids, sorts by time then id. Pure. |
| `NextTrigger` | Next fire time for hour:minute in a given `TimeZone`, via `Calendar`, so 07:00 stays 07:00 across DST. Used when an alarm is switched ON: the next occurrence of its time; "now" (same minute) means tomorrow. `rearm` decides a re-arm from scratch: an ON alarm last armed for a time at most 30 minutes ago (`LATE_GRACE_MS`) has not rung yet, so it keeps that past time and rings at once instead of tomorrow. Pure. |
| `Pin` | PIN validation (4-8 ASCII digits), salted SHA-256 hash, constant-time compare, and the fixed reset PIN (`RESET`, same in every Senior app; public on purpose). Pure. |
| `Store` | All persistent state (alarms, next id, lock flag, PIN salt/hash, the time each alarm was last armed for) in device-protected SharedPreferences. `isLocked()` is the launcher's lock or the own PIN lock. Every alarm write (add/update/delete) redraws the widget. `add` refuses a 51st alarm (returns null); the list then shows a note to delete one instead of the Add button, and automation logs and ignores it. |
| `LauncherLock` | Reads SeniorLauncher's lock (`content://app.phueber.seniorlauncher.lock/state`) and lifts it with its PIN (`call(..., "unlock", pin)`). With the launcher installed, the home screen's lock is the lock: the Lock/PIN button gives way to a note, and "Hold to unlock" asks for the home screen's PIN right here. Without it, the PIN lock works as before. Only the alarm list uses it, never the ringing path. |
| `Scheduler` | Arms/cancels one `AlarmManager.setAlarmClock` per enabled alarm (inexact `setAndAllowWhileIdle` only if exact alarms are denied). Remembers each armed time in `Store`. `rescheduleAll` re-arms everything from scratch (boot, time or zone change, update, permission change), but an alarm due up to 30 minutes ago that has not rung keeps its time and rings now (`NextTrigger.rearm`); `ensureArmed` arms only what AlarmManager lost (app opened), so a pending, possibly late alarm is never pushed to tomorrow. Both catch a failure per alarm, so one alarm AlarmManager refuses neither stops the rest nor crashes the app on open. |
| `AlarmReceiver` | Receives a fired alarm: marks it OFF in `Store` first (alarms do not repeat), takes a 30-second wake lock (`RingService.holdCpuUntilRinging`, released in `begin`), then starts `RingService`. If Android refuses the start (API 31-32, inexact fallback alarm), it rings through the ring screen instead (`RingService.ringViaScreen`); only if that notification cannot appear either does it put the alarm back ON (armed for the next occurrence), so a missed ring never shows as OFF. |
| `Automation` | Commands for automation apps (Trigly, Tasker): action and extra names, lenient parsing of the string extras (`time` HH:MM, `id`, `state`), and which alarms a command means (`select`: by id, else all at that time). Pure. |
| `AutomationReceiver` | Exported receiver for those commands (ALARM_ON/OFF/SET/DELETE, STOP, LOCK, UNLOCK). Calls `Store` and `Scheduler` exactly as the taps in `MainActivity` do; STOP calls `RingService.stop`. |
| `BootReceiver` | On boot, package replace, time or time-zone change and exact-alarm permission change (sent only when granted; a revoke stops the app and drops its alarms, and the next app start's `ensureArmed` re-arms them inexactly): `Scheduler.rescheduleAll`, then redraws the widget. |
| `AlarmOverview` | What the widget shows: the next alarm (earliest `NextTrigger` among ON alarms), Today/Tomorrow/None for it, the list order (ON by ring time, then OFF by time of day), the next local midnight, the size rules (wide from 150dp, rows of at least 64dp, at most 8) and how many alarms get a row before the last one becomes "+N more". Pure. |
| `AlarmWidget` | Home-screen widget (`AppWidgetProvider`, `res/xml/alarm_widget.xml`, layouts `widget_small`, `widget_list`, `widget_row`, `widget_more`, `widget_preview`). Draws `AlarmOverview`; read-only, a tap opens `MainActivity`. See "Widget". |
| `RingService` | Foreground service (mediaPlayback): sound, vibration, full-screen notification, 2-minute hard cap from the first START (a second alarm joins the ring, it does not extend it), schedules the backup stop (StopReceiver). Sound is the platform's `DEFAULT_ALARM_ALERT_URI` (works before first unlock), ringtone second, a plain tone last; it asks for transient audio focus. During a call (audio mode IN_CALL / IN_COMMUNICATION) it plays only a quiet double beep on the voice-call stream every 4 s instead, and does not open the ring screen over the call. Owns `start`/`stop`/`isRinging`; teardown is total and exception-safe. If `startForeground` fails it shuts down silently rather than ring with no way to stop it. A stop while a START is unanswered silences at once and lets `begin` end the service right after `startForeground` (see "Stop-path invariant"). Opens `RingActivity` directly when ringing starts (`openScreen`), since a full-screen intent is only a heads-up while the phone is in use; Android allows that only with "Display over other apps". `ringViaScreen` posts the ringing notification alone, for when the service may not start. |
| `RingActivity` | Ringing screen over the lock screen: one big Turn-off button, volume keys also stop. Left while ringing (Home, SeniorNav's Home), it brings itself back after 1 s, unless the screen is off or a call is going on. Opened from `ringViaScreen`'s notification (extras alarm id and a deadline), it starts `RingService` itself once resumed, where a foreground-service start is allowed. |
| `OneLineText` | A `TextView` that stays on one line and shrinks its text only as far as needed to fit its width. Used for both clock times, so a very large phone font never wraps a time ("10:30" / "AM"). The platform autosize needs a fixed height; this needs only the width. |
| `StopReceiver` | Stops ringing: target of the notification action and of the backup AlarmManager stop. |
| `MainActivity` | Alarm list, add/edit/delete, an OFF | ON switch per alarm (two halves with bells, `ic_bell.xml`/`ic_bell_off.xml`; the active half is filled and `selected`, pressing the active half does nothing), PIN set/lock/unlock (Unlock needs a long press; the PIN prompt closes after 10 s without typing), setup warnings, banner with stop while ringing, and a Back button (left arrow, `ic_arrow_back.xml`) pinned below the scrolling list that opens the home screen (`CATEGORY_HOME`), because many seniors do not know the back gesture. Opens `RingActivity` itself when ringing starts or it is opened while ringing (the system heads-up would otherwise cover the banner). Warnings that affect whether the ring screen can appear (notifications, ringing channel importance, full-screen) and exact alarms (API 31-32) also show when locked; "Display over other apps" only unlocked. Dialogs (time picker, delete, PIN) are `bigDialog`: a white card with a bold title and stacked full-width OK and Cancel buttons, like SeniorPhone's and SeniorLauncher's `Ui.confirm`. |
| `NavBar` | Whether SeniorNav's Back & Home bar is on screen: SeniorNav's provider `content://app.phueber.seniornav.bar/state` (column `visible`, observed live while the Back view is attached) if it resolves to SeniorNav, else whether the enabled accessibility services list it. While it is, the pinned Back button is hidden, since the bar already has Back and Home; also re-checked whenever a screen gets focus. Pure part unit tested. |

Pure classes have JVM tests in `app/src/test`; Android-facing classes have none.

## Firing flow

1. `Scheduler` calls `setAlarmClock(NextTrigger.next(...))` with a PendingIntent to `AlarmReceiver`.
2. `AlarmReceiver` runs at the time. It first switches the alarm OFF, so it rings once and a crash later cannot make it ring again. Disabled or deleted alarms are ignored. Switching it ON again (in `MainActivity`) re-arms it for the next occurrence.
3. It takes a short wake lock and starts `RingService` in the foreground.
4. The service posts a full-screen notification, plays the sound (or the quiet in-call beep) and starts the 2-minute timer and the backup stop.
5. The system launches `RingActivity` from the full-screen intent while the screen is off or locked; in use, the service opens it directly (needs "Display over other apps"), else the heads-up stays and a tap opens it.

On API 31-32 with exact alarms denied, the inexact fallback alarm does not let
the receiver start a foreground service. Step 3 then posts the same notification
without the service; `RingActivity`, once in front, starts the service, and only
then does it ring. While the phone is in use that needs a tap on the heads-up.

## Stop-path invariant

Ringing can always be stopped without the PIN, from at least five independent
places: the big button, the volume keys, the notification action, the banner in
`MainActivity`, and the time limit (service timer, plus a backup `AlarmManager`
broadcast to `StopReceiver` that does not depend on the service's own timer).
All paths end in the same stop routine. Do not add any step (PIN, confirmation)
to any of them.

Android crashes an app that stops a service started with
`startForegroundService` before it called `startForeground`. So the routine
counts unanswered STARTs: a stop in that window silences everything at once and
sets `stopPending`; `begin` answers the START with `startForeground` and then
shuts down. No stop waits for this; only the end of the service does.

Bringing the ring screen back (Home while ringing, ringing starting while the
phone is in use) is only an extra way to reach the button; no stop path
depends on it.

The visible paths depend on the notification. If notifications, the ringing
channel or full-screen intents are turned off, the sound still plays but only
the banner (after unlocking the phone) and the time limit remain. That is why
those settings are warned about even when the app is locked.

## Automation

Automation apps (Trigly, Tasker) can only send an action plus a few string
extras, so `AutomationReceiver` takes explicit broadcasts (no screen needs to
appear, and broadcasts have no background-start limit) and `Automation` parses
every extra leniently; malformed commands are ignored with a log line, nothing
is reported back. Each command repeats the matching tap: Store first, then
Scheduler, and an alarm already in the wanted state is left alone, because
re-arming would push a late pending alarm to tomorrow (see `ensureArmed`).
`ALARM_SET` switches the alarms at a time or adds one, so it never makes a
duplicate. The open list picks up outside changes through its 1-second poll
(the alarm list and the own lock flag), the same way it notices `AlarmReceiver`.

The receiver runs on the main thread, like `AlarmReceiver` and the list, so
Store's read-modify-write is never interleaved with a tap. It is
`directBootAware` because Store is device-protected.

The lock is a guard rail, not security: automation acts for the helper, so
setup commands work while locked, and no command needs a permission the
sender lacks. `LOCK` is ignored when SeniorLauncher is present (its lock is the
one lock; a second own PIN could disagree with it). `UNLOCK` goes through
`Store.unlock`, so the launcher's PIN or the reset PIN works. `STOP` ends in the
same stop routine as every other stop path and adds no step to them.

## Widget

`AlarmWidget` shows the next alarm in one cell (bell, time, Today/Tomorrow/No
alarm) and the alarm list when wider, next alarm first, ON rows filled green
(white text), OFF rows grey, each with ON/OFF in words. Its provider asks for
1x1 target cells and 40dp minimum (so SeniorLauncher puts it in one tile), is
resizable both ways down to 40dp and up to 640x720dp, so the launcher's helper
can pick Small, 1 row, 2 rows or Full page.

- Size: on Android 12+ one `RemoteViews(Map<SizeF, RemoteViews>)`: the small
  face at 40x40, the list from 150dp wide with 1 to 8 rows at the heights
  `AlarmOverview.heightFor(n)`; the home screen picks the one that fits. Below
  12 `onAppWidgetOptionsChanged` redraws from the portrait size (min width, max
  height). Rows share the height evenly; empty slots are invisible rows, so
  few alarms do not make huge rows. Texts autosize, so big fonts still fit.
- Too many alarms: when they do not all fit, the last row is a grey
  "+N more alarms – tap to see all" line (`widget_more`, plurals
  `widget_more`) instead of the alarms silently missing. `AlarmOverview.shown`
  and `more` decide; the line always stands for at least 2 (it takes the place
  of one alarm). The 1-row size keeps the next alarm and has no such line.
- Taps: `openApp` is one immutable `PendingIntent.getActivity` (request code 0,
  `FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`, explicit `MainActivity`, NEW_TASK),
  the same for every widget id. It is set on `widget_root` of the small face
  and of each size's list, so the whole widget (padding, invisible slots,
  non-clickable bell and texts) opens the app, and also on each alarm row and
  the "more" line, which are added with `addView` (not a collection, so no
  fill-in intents are needed).
- The list calls `removeAllViews` before adding rows: hosts re-apply all
  actions to the existing view, and `addView` would otherwise append a second set.
- Read-only on purpose: toggling from the home screen would bypass the lock
  and invite accidental changes. A tap opens the app.
- Updates without polling: Store's alarm writes (so every tap, automation
  command, fired alarm and the AlarmReceiver fallback), `BootReceiver` (boot,
  time, zone, 12/24-hour switch, app update), `LOCALE_CHANGED`, `MainActivity.onResume`
  (re-arms the midnight refresh after a force-stop), and a non-wakeup
  `AlarmManager.setWindow(RTC)` just after the next local midnight (10-minute
  window) so Tomorrow becomes Today. `onDisabled` cancels it. `updateAll`
  never throws, because it runs inside Store on the alarm path.
- Colours: `res/values/widget.xml` with a `values-night` variant, since
  SeniorLauncher switches widget apps to night colours in its dark themes; the
  app itself stays light. Colours set from code are only the ones equal in
  both (white on green); the rest come from resources resolved by the host.
- Direct boot: the receiver is `directBootAware` and reads device-protected
  Store, so the widget is right before the first unlock.
- Picker preview: `previewLayout` (`widget_preview`, sample 07:30 Tomorrow) on
  Android 12+, the app icon as `previewImage` before.

## Look

Plain platform views built in code, with no layout XML and no UI library
(the widget's layouts are XML because `RemoteViews` needs them). Colours live in
`res/values/styles.xml`. `AppTheme` is a warm off-white page with dark system-bar
icons (`values-v27` adds dark navigation-bar icons). `RingTheme` is all red.
Both screens use `fitsSystemWindows`, because Android 15 draws apps edge to edge.
Buttons are rounded `GradientDrawable`s inside the platform `RippleDrawable`,
so every tap shows feedback. Filled means a main action and outlined means a
secondary action. ON/OFF is a two-part switch rather than one toggle button:
a single button labelled with the state read as an action (pressing "OFF"
turned the alarm on), while with both halves visible you press the word you
want. The ring screen's button is a clickable
`FrameLayout` that centres the Material alarm icon (`ic_alarm.xml`, a vector)
with the label. The clock sits below the button, because the system's heads-up
notification covers the top of the screen. Times use `OneLineText`, so the
largest font setting with 12-hour times still fits one line.

## Storage

One SharedPreferences file `senioralert` opened through
`createDeviceProtectedStorageContext()`. Reason: with direct boot, alarms must
be re-armed and ring after a reboot before the first unlock, when
credential-protected storage is unreadable. Receivers, service and
`RingActivity` are `directBootAware`. The data is not sensitive. The PIN is only
a hash and only guards against accidents, not attackers. `allowBackup` is off.

## Permissions

| Permission | Why |
| --- | --- |
| `USE_EXACT_ALARM` | Exact alarms for an alarm-clock app (auto-granted API 33+). |
| `SCHEDULE_EXACT_ALARM` (max SDK 32) | Same for API 31-32. |
| `RECEIVE_BOOT_COMPLETED` | Re-arm still-ON alarms after reboot and similar events. |
| `POST_NOTIFICATIONS` | Ringing notification (API 33+). |
| `USE_FULL_SCREEN_INTENT` | Show `RingActivity` over the lock screen. |
| `SYSTEM_ALERT_WINDOW` | "Display over other apps", granted by the helper: lets the service open `RingActivity` while the phone is in use and bring it back after Home. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | `RingService` plays the alarm sound. |
| `WAKE_LOCK` | Keep the device awake while ringing, and from `AlarmReceiver` until ringing starts. |
| `VIBRATE` | Vibration while ringing. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Let the app ask for a battery exemption so alarms are not delayed. |

## Deliberate non-features

- No snooze: an alarm rings once and then switches itself OFF.
- No labels, no weekday selection: every alarm is one time, ringing once at its next occurrence. No repeating alarms.
- No dependencies: no AndroidX, Kotlin or libraries; fewer things to break and to update.
- PIN recovery is the fixed reset PIN `Pin.RESET` (506614); it is public, since the lock is a guard rail, not security.
