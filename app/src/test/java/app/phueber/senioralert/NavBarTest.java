package app.phueber.senioralert;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class NavBarTest {
    @Test public void findsTheBarService() {
        assertTrue(NavBar.listed("app.phueber.seniornav/.NavBarService"));
        assertTrue(NavBar.listed("com.other/.A:app.phueber.seniornav/app.phueber.seniornav.NavBarService"));
    }

    @Test public void otherwiseOff() {
        assertFalse(NavBar.listed(null));
        assertFalse(NavBar.listed(""));
        assertFalse(NavBar.listed("com.other/.A"));
        assertFalse(NavBar.listed("app.phueber.seniornavx/.Fake"));
    }

    @Test public void providerAnswerWins() {
        String on = "app.phueber.seniornav/.NavBarService";
        assertFalse(NavBar.decide(0, on)); // paused or not running: our Back shows
        assertTrue(NavBar.decide(1, null));
    }

    @Test public void withoutProviderTheSettingDecides() {
        assertTrue(NavBar.decide(NavBar.UNKNOWN, "app.phueber.seniornav/.NavBarService"));
        assertFalse(NavBar.decide(NavBar.UNKNOWN, "com.other/.A"));
    }
}
