package app.phueber.senioralert;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class AlertBackupTest {
    private static final String PKG = "app.phueber.senioralert";

    private static List<AlertBackup.Item> load(String json) throws Backup.Failure {
        return AlertBackup.fromSettings(Backup.settingsFor(json, PKG));
    }

    private static String file(String settings) {
        return "{\"format\":\"seniorphonekit-backup\",\"version\":1,\"apps\":{\"" + PKG
                + "\":{\"appVersion\":\"0.1.3\",\"settings\":" + settings + "}}}";
    }

    private static void malformed(String settings) {
        try {
            load(file(settings));
            fail("accepted: " + settings);
        } catch (Backup.Failure f) {
            assertEquals(Backup.ERROR_MALFORMED, f.code);
        }
    }

    @Test public void roundTripThroughTheFile() throws Exception {
        List<Alarm> alarms = Arrays.asList(new Alarm(3, 8, 0, true), new Alarm(7, 19, 0, false),
                new Alarm(9, 23, 59, true));
        String json = Backup.writeOne("2026-11-23T10:15:00Z", PKG, "0.1.3", AlertBackup.toSettings(alarms));
        assertEquals(Arrays.asList(new AlertBackup.Item(8, 0, true), new AlertBackup.Item(19, 0, false),
                new AlertBackup.Item(23, 59, true)), load(json));
    }

    @Test public void idsAreNotSaved() {
        Map<String, Object> s = AlertBackup.toSettings(Arrays.asList(new Alarm(42, 7, 30, true)));
        assertEquals("{\n  \"alarms\": [\n    {\n      \"hour\": 7,\n      \"minute\": 30,\n"
                + "      \"on\": true\n    }\n  ]\n}\n", Json.write(s));
    }

    @Test public void missingFieldsTakeDefaults() throws Exception {
        assertTrue(load(file("{}")).isEmpty());
        assertTrue(load(file("{\"alarms\":[]}")).isEmpty());
        assertEquals(Arrays.asList(new AlertBackup.Item(6, 5, false)),
                load(file("{\"alarms\":[{\"hour\":6,\"minute\":5}]}")));
        // Unknown fields (from a later 1.x) are ignored.
        assertEquals(Arrays.asList(new AlertBackup.Item(6, 5, true)),
                load(file("{\"alarms\":[{\"hour\":6,\"minute\":5,\"on\":true,\"label\":\"x\"}],\"extra\":1}")));
    }

    @Test public void badAlarmsRefuseTheWholeFile() {
        malformed("{\"alarms\":{}}");
        malformed("{\"alarms\":[1]}");
        malformed("{\"alarms\":[{\"minute\":5,\"on\":true}]}");
        malformed("{\"alarms\":[{\"hour\":6,\"on\":true}]}");
        malformed("{\"alarms\":[{\"hour\":24,\"minute\":0}]}");
        malformed("{\"alarms\":[{\"hour\":-1,\"minute\":0}]}");
        malformed("{\"alarms\":[{\"hour\":6,\"minute\":60}]}");
        malformed("{\"alarms\":[{\"hour\":\"6\",\"minute\":0}]}");
        malformed("{\"alarms\":[{\"hour\":6.5,\"minute\":0}]}");
        malformed("{\"alarms\":[{\"hour\":6,\"minute\":0,\"on\":\"yes\"}]}");
        malformed("{\"alarms\":[{\"hour\":6,\"minute\":0},{\"hour\":99,\"minute\":0}]}");
    }

    @Test public void moreThanTheListHoldsIsRefused() throws Exception {
        StringBuilder b = new StringBuilder("{\"alarms\":[");
        for (int i = 0; i < Alarm.MAX_COUNT; i++) b.append(i == 0 ? "" : ",").append("{\"hour\":7,\"minute\":0}");
        assertEquals(Alarm.MAX_COUNT, load(file(b + "]}")).size());
        malformed(b + ",{\"hour\":7,\"minute\":0}]}");
    }
}
