package app.phueber.senioralert;

import static org.junit.Assert.*;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import org.junit.Test;

public class AlarmOverviewTest {
    private static final TimeZone BERLIN = TimeZone.getTimeZone("Europe/Berlin");

    private static long at(int y, int mo, int d, int h, int mi) {
        Calendar c = Calendar.getInstance(BERLIN);
        c.clear();
        c.set(y, mo - 1, d, h, mi, 0);
        return c.getTimeInMillis();
    }

    private static final long MORNING = at(2026, 6, 10, 6, 0);
    private static final long EVENING = at(2026, 6, 10, 22, 0);

    @Test public void noAlarmsMeansNone() {
        assertNull(AlarmOverview.next(Collections.emptyList(), MORNING, BERLIN));
        assertEquals(AlarmOverview.Day.NONE, AlarmOverview.day(null, MORNING, BERLIN));
    }

    @Test public void offAlarmsAreNeverNext() {
        List<Alarm> l = Arrays.asList(new Alarm(1, 7, 0, false), new Alarm(2, 8, 0, false));
        assertNull(AlarmOverview.next(l, MORNING, BERLIN));
        assertEquals(AlarmOverview.Day.NONE, AlarmOverview.day(l.get(0), MORNING, BERLIN));
    }

    @Test public void nextIsTheEarliestToRingNotTheEarliestTime() {
        // At 22:00, 23:00 today rings before 06:30 tomorrow.
        Alarm early = new Alarm(1, 6, 30, true);
        Alarm late = new Alarm(2, 23, 0, true);
        List<Alarm> l = Arrays.asList(early, late);
        assertEquals(late, AlarmOverview.next(l, EVENING, BERLIN));
        assertEquals(early, AlarmOverview.next(l, MORNING, BERLIN));
    }

    @Test public void tieGoesToLowerId() {
        Alarm a = new Alarm(5, 7, 0, true);
        Alarm b = new Alarm(3, 7, 0, true);
        assertEquals(b, AlarmOverview.next(Arrays.asList(a, b), MORNING, BERLIN));
    }

    @Test public void todayAndTomorrow() {
        Alarm seven = new Alarm(1, 7, 0, true);
        assertEquals(AlarmOverview.Day.TODAY, AlarmOverview.day(seven, MORNING, BERLIN));
        assertEquals(AlarmOverview.Day.TOMORROW, AlarmOverview.day(seven, EVENING, BERLIN));
    }

    @Test public void sameMinuteMeansTomorrow() {
        Alarm six = new Alarm(1, 6, 0, true);
        assertEquals(AlarmOverview.Day.TOMORROW, AlarmOverview.day(six, MORNING, BERLIN));
    }

    @Test public void tomorrowAcrossNewYear() {
        Alarm a = new Alarm(1, 7, 0, true);
        assertEquals(AlarmOverview.Day.TOMORROW,
                AlarmOverview.day(a, at(2026, 12, 31, 23, 0), BERLIN));
        assertEquals(AlarmOverview.Day.TODAY,
                AlarmOverview.day(a, at(2027, 1, 1, 0, 5), BERLIN));
    }

    @Test public void orderedPutsOnByRingTimeThenOffByTime() {
        Alarm offEarly = new Alarm(1, 5, 0, false);
        Alarm onMorning = new Alarm(2, 6, 30, true);
        Alarm onNight = new Alarm(3, 23, 0, true);
        Alarm offNoon = new Alarm(4, 12, 0, false);
        List<Alarm> l = Arrays.asList(offEarly, onMorning, offNoon, onNight);
        assertEquals(Arrays.asList(onNight, onMorning, offEarly, offNoon),
                AlarmOverview.ordered(l, EVENING, BERLIN));
        assertEquals(Arrays.asList(onMorning, onNight, offEarly, offNoon),
                AlarmOverview.ordered(l, MORNING, BERLIN));
    }

    @Test public void orderedDoesNotChangeInput() {
        List<Alarm> l = Arrays.asList(new Alarm(1, 5, 0, false), new Alarm(2, 6, 0, true));
        AlarmOverview.ordered(l, MORNING, BERLIN);
        assertEquals(1, l.get(0).id);
    }

    @Test public void nextMidnightIsStartOfNextLocalDay() {
        assertEquals(at(2026, 6, 11, 0, 0), AlarmOverview.nextMidnight(MORNING, BERLIN));
        assertEquals(at(2026, 6, 11, 0, 0), AlarmOverview.nextMidnight(at(2026, 6, 10, 0, 0), BERLIN));
        // Day of the DST switch (29 March 2026) has 23 hours.
        assertEquals(at(2026, 3, 30, 0, 0), AlarmOverview.nextMidnight(at(2026, 3, 29, 12, 0), BERLIN));
    }

    @Test public void wideThreshold() {
        assertFalse(AlarmOverview.isWide(57));
        assertFalse(AlarmOverview.isWide(149));
        assertTrue(AlarmOverview.isWide(150));
        assertTrue(AlarmOverview.isWide(400));
    }

    @Test public void rowsFitHeight() {
        assertEquals(1, AlarmOverview.rows(0));
        assertEquals(1, AlarmOverview.rows(80));
        assertEquals(2, AlarmOverview.rows(AlarmOverview.heightFor(2)));
        assertEquals(1, AlarmOverview.rows(AlarmOverview.heightFor(2) - 1));
        assertEquals(AlarmOverview.MAX_ROWS, AlarmOverview.rows(5000));
        for (int n = 1; n <= AlarmOverview.MAX_ROWS; n++) {
            assertEquals(n, AlarmOverview.rows(AlarmOverview.heightFor(n)));
        }
    }

    @Test public void allAlarmsShownWhenTheyFit() {
        assertEquals(0, AlarmOverview.shown(0, 3));
        assertEquals(2, AlarmOverview.shown(2, 3));
        assertEquals(3, AlarmOverview.shown(3, 3));
        assertEquals(0, AlarmOverview.more(3, 3));
        assertEquals(0, AlarmOverview.more(0, 3));
    }

    @Test public void lastRowSaysMoreWhenTooMany() {
        // 5 alarms, 3 rows: 2 alarms and "+3 more".
        assertEquals(2, AlarmOverview.shown(5, 3));
        assertEquals(3, AlarmOverview.more(5, 3));
        // One too many: the line stands for 2, never "+1" next to a free row.
        assertEquals(1, AlarmOverview.shown(3, 2));
        assertEquals(2, AlarmOverview.more(3, 2));
    }

    @Test public void shownPlusMoreAccountsForEveryAlarm() {
        for (int slots = 2; slots <= AlarmOverview.MAX_ROWS; slots++) {
            for (int n = 0; n <= 20; n++) {
                int shown = AlarmOverview.shown(n, slots);
                int more = AlarmOverview.more(n, slots);
                assertEquals(n, shown + more);
                assertTrue(shown + (more > 0 ? 1 : 0) <= slots);
                assertTrue(more == 0 || more >= 2);
            }
        }
    }

    @Test public void oneRowKeepsTheNextAlarm() {
        assertEquals(1, AlarmOverview.shown(5, 1));
        assertEquals(0, AlarmOverview.more(5, 1));
        assertEquals(1, AlarmOverview.shown(1, 1));
    }
}
