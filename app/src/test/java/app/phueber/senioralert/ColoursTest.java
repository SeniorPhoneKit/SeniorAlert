package app.phueber.senioralert;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Test;

/**
 * The colours live in resource files, so this reads them and checks the pairs
 * the screens and the widget actually draw: 7:1 (WCAG AAA) for body text,
 * 4.5:1 (AA) for secondary text and white on a filled button, 3:1 for the edge
 * of an outlined button (WCAG 1.4.11).
 */
public class ColoursTest {
    private static final Pattern COLOR =
            Pattern.compile("<color name=\"(\\w+)\">#([0-9A-Fa-f]{6})</color>");

    private static Map<String, Integer> read(String file) throws IOException {
        // Unit tests run in the module directory (app/).
        String xml = new String(Files.readAllBytes(Paths.get("src/main/res/values", file)),
                StandardCharsets.UTF_8);
        Map<String, Integer> m = new HashMap<>();
        Matcher x = COLOR.matcher(xml);
        while (x.find()) m.put(x.group(1), Integer.parseInt(x.group(2), 16));
        return m;
    }

    private static void atLeast(double min, Map<String, Integer> c, String fg, String bg) {
        assertTrue(fg + " on " + bg + " is missing", c.containsKey(fg) && c.containsKey(bg));
        double r = contrast(c.get(fg), c.get(bg));
        assertTrue(fg + " on " + bg + " contrast " + r + " < " + min, r >= min);
    }

    @Test public void screensAreReadable() throws IOException {
        Map<String, Integer> c = read("styles.xml");
        c.put("white", 0xFFFFFF);
        atLeast(7, c, "text_black", "page");
        atLeast(7, c, "text_black", "card");
        atLeast(7, c, "text_black", "warn_yellow");
        atLeast(4.5, c, "text_muted", "page");
        atLeast(4.5, c, "text_muted", "card");
        atLeast(7, c, "red_text", "card");
        atLeast(4.5, c, "off_text", "off_bg");       // the OFF | ON switch, unpressed half
        atLeast(4.5, c, "white", "off_text");        // the OFF half when the alarm is off
        atLeast(4.5, c, "white", "on_green");
        atLeast(4.5, c, "white", "stop_red");
        atLeast(3, c, "outline", "page");
        atLeast(3, c, "outline", "card");
        atLeast(3, c, "stop_red", "card");           // Delete's edge
    }

    @Test public void widgetIsReadable() throws IOException {
        Map<String, Integer> c = read("widget.xml");
        atLeast(7, c, "widget_text", "widget_bg");
        atLeast(4.5, c, "widget_muted", "widget_bg");
        atLeast(7, c, "widget_off_text", "widget_off_bg");
        atLeast(4.5, c, "widget_on_text", "widget_on_bg");
    }

    /** WCAG 2 contrast ratio of two opaque RGB colours, 1..21. */
    private static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    private static double luminance(int rgb) {
        return 0.2126 * channel((rgb >> 16) & 0xFF)
                + 0.7152 * channel((rgb >> 8) & 0xFF)
                + 0.0722 * channel(rgb & 0xFF);
    }

    private static double channel(int c) {
        double s = c / 255.0;
        return s <= 0.03928 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
    }
}
