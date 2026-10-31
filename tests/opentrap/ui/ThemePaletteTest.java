package opentrap.ui;

import opentrap.testkit.Expect;
import opentrap.testkit.Harness;

import java.awt.Color;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/**
 * Pins the palette to its theme.
 *
 * <p>{@link Theme#configure(boolean)} reassigns every colour in one of two
 * branches. A colour assigned in only one branch keeps the other theme's value,
 * which is how the selected task card came to be painted dark navy in the light
 * theme: CONTROL and CONTROL_TINT held the dark-theme pair and were set at the
 * end of the light branch. These cases make that class of mistake fail here
 * rather than in a screenshot.
 */
public final class ThemePaletteTest {

    /** Perceived brightness, 0 (black) to 255 (white). */
    private static int luma(Color c) {
        return (int) (0.299 * c.getRed() + 0.587 * c.getGreen() + 0.114 * c.getBlue());
    }

    /** Every reassignable colour on the palette. */
    private static Field[] colours() {
        java.util.List<Field> out = new java.util.ArrayList<>();
        for (Field f : Theme.class.getDeclaredFields()) {
            if (f.getType() == Color.class && Modifier.isStatic(f.getModifiers())
                    && !Modifier.isFinal(f.getModifiers())) out.add(f);
        }
        return out.toArray(new Field[0]);
    }

    private static Color read(Field f) {
        try { return (Color) f.get(null); }
        catch (IllegalAccessException e) { throw new AssertionError(e); }
    }

    private static String hex(Color c) {
        return String.format("#%02X%02X%02X", c.getRed(), c.getGreen(), c.getBlue());
    }

    /** Within {@code tol} per channel, which absorbs the screen recording's noise. */
    private static boolean near(Color c, int r, int g, int b, int tol) {
        return Math.abs(c.getRed() - r) <= tol
            && Math.abs(c.getGreen() - g) <= tol
            && Math.abs(c.getBlue() - b) <= tol;
    }

    public void run() {
        Harness.suite("theme palette");

        Harness.test("every colour changes with the theme", () -> {
            Field[] fields = colours();
            Expect.isTrue(fields.length > 0, "the palette has reassignable colours");

            Theme.configure(false);
            java.util.Map<String, Color> light = new java.util.LinkedHashMap<>();
            for (Field f : fields) light.put(f.getName(), read(f));

            Theme.configure(true);
            for (Field f : fields) {
                // The accent is deliberately the same in both themes, so the
                // brand reads the same; everything else must move.
                if (f.getName().startsWith("ACCENT") && !f.getName().equals("ACCENT_LIGHT")
                        && !f.getName().equals("ACCENT_FAINT")) continue;
                Expect.isFalse(read(f).equals(light.get(f.getName())),
                        f.getName() + " is left at the other theme's value");
            }
        });

        Harness.test("the sampled colours are the ones the reference uses", () -> {
            // Taken with a colour picker over the reference frames in
            // ~/Working/burpmap/video/frames: #26639C is the most common
            // saturated blue across all 346 of them by a factor of five, and
            // is the fill of the site map's selected "URL view" segment and of
            // the selected task card's edge bar. #EAEFF5 is that card's fill
            // and #DFDFDF the unselected segment. #DA602F is the accent.
            Theme.configure(false);
            Expect.equal(hex(Theme.CONTROL), "#26639C", "the control blue");
            Expect.equal(hex(Theme.CONTROL_TINT), "#EAEFF5", "the selection tint");
            Expect.equal(hex(Theme.SEGMENT_OFF), "#DFDFDF", "the unselected segment");
            Expect.isTrue(near(Theme.ACCENT, 0xDA, 0x60, 0x2F, 3), "the accent is the sampled orange");
        });

        Harness.test("a tint stays on the right side of its text", () -> {
            Theme.configure(false);
            Expect.isTrue(luma(Theme.CONTROL_TINT) > 200,
                    "the light theme's selection tint is pale, so dark text reads on it");
            Expect.isTrue(luma(Theme.TEXT) < 100, "the light theme's text is dark");

            Theme.configure(true);
            Expect.isTrue(luma(Theme.CONTROL_TINT) < 90,
                    "the dark theme's selection tint is dark, so light text reads on it");
            Expect.isTrue(luma(Theme.TEXT) > 150, "the dark theme's text is light");

            Theme.configure(false);   // leave the palette as the suite found it
        });
    }
}
