package app.phueber.senioralert;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class AutomationTest {
    @Test public void times() {
        assertEquals(7 * 60 + 30, Automation.parseTime("07:30"));
        assertEquals(7 * 60 + 30, Automation.parseTime("7:30"));
        assertEquals(7 * 60 + 30, Automation.parseTime(" 07:30 "));
        assertEquals(0, Automation.parseTime("00:00"));
        assertEquals(23 * 60 + 59, Automation.parseTime("23:59"));
    }

    @Test public void badTimes() {
        for (String s : new String[] {null, "", "24:00", "12:60", "7:5", "730", ":30", "007:30",
                "07:30:00", "7.30", "a7:30", "-1:30", "07:3x", "７:30", "7:30 PM"}) {
            assertEquals(s, -1, Automation.parseTime(s));
        }
    }

    @Test public void ids() {
        assertEquals(3, Automation.parseId("3"));
        assertEquals(12, Automation.parseId(" 12 "));
        assertEquals(-1, Automation.parseId("0"));
        assertEquals(-1, Automation.parseId("-3"));
        assertEquals(-1, Automation.parseId("x"));
        assertEquals(-1, Automation.parseId(""));
        assertEquals(-1, Automation.parseId(null));
        assertEquals(-1, Automation.parseId("9999999999"));
    }

    @Test public void states() {
        assertEquals(Boolean.TRUE, Automation.parseState("ON", false));
        assertEquals(Boolean.TRUE, Automation.parseState(" true ", false));
        assertEquals(Boolean.TRUE, Automation.parseState("1", false));
        assertEquals(Boolean.FALSE, Automation.parseState("Off", true));
        assertEquals(Boolean.FALSE, Automation.parseState("false", true));
        assertEquals(Boolean.FALSE, Automation.parseState("0", true));
        assertEquals(Boolean.TRUE, Automation.parseState(null, true));
        assertEquals(Boolean.FALSE, Automation.parseState("  ", false));
        assertNull(Automation.parseState("maybe", true));
    }

    private static final List<Alarm> LIST = Arrays.asList(
            new Alarm(1, 7, 30, true), new Alarm(2, 7, 30, false), new Alarm(3, 20, 0, true));

    @Test public void selectByTime() {
        assertEquals(Arrays.asList(LIST.get(0), LIST.get(1)), Automation.select(LIST, null, "7:30"));
        assertTrue(Automation.select(LIST, null, "8:00").isEmpty());
        assertTrue(Automation.select(LIST, null, "junk").isEmpty());
        assertTrue(Automation.select(LIST, null, null).isEmpty());
    }

    @Test public void selectByIdWins() {
        assertEquals(Arrays.asList(LIST.get(2)), Automation.select(LIST, "3", "7:30"));
        assertTrue(Automation.select(LIST, "9", "7:30").isEmpty());
        // A malformed id never falls back to the time.
        assertTrue(Automation.select(LIST, "x", "7:30").isEmpty());
        // A blank id counts as absent.
        assertEquals(2, Automation.select(LIST, " ", "7:30").size());
    }
}
