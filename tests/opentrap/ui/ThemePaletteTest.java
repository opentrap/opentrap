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
