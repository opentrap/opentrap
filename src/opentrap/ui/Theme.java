package opentrap.ui;

import opentrap.ui.theme.*;

import javax.swing.*;
import javax.swing.border.Border;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.*;

/**
 * The suite's visual theme: a flat interface, light or dark, with one orange accent.
 *
 * <p>Everything here is plain Swing. The cross-platform look and feel is the
 * base, so the interface is identical on every desktop, and the parts of it
 * that look dated (bevelled buttons, boxed tabs, chunky scrollbars) are
 * replaced with flat delegates from {@code opentrap.ui.theme}. Colours and
 * fonts live in this one class so the whole interface changes together.
 */
public final class Theme {
    private Theme() {}

    // ---- palette ----
    // Not final: the dark variant is chosen at install() time (before any UI is
    // built, so the Ui constants that copy these capture the right values). A
    // theme change relaunches the app, exactly as a scale change does.
    /** Page background. */
    public static Color BG         = new Color(0xFF, 0xFF, 0xFF);
    /** Raised surfaces: toolbars, table headers, cards, the status bar. */
    public static Color SURFACE    = new Color(0xEE, 0xEE, 0xEE);
    /** Hover and pressed state of surfaces and secondary buttons. */
    public static Color SURFACE_2  = new Color(0xE4, 0xE4, 0xE4);
    public static Color SURFACE_3  = new Color(0xD8, 0xD8, 0xD8);
    /** Hairlines and outlines. */
    public static Color BORDER     = new Color(0xD4, 0xD4, 0xD4);
    public static Color TEXT       = new Color(0x1A, 0x1A, 0x1A);
    public static Color MUTED      = new Color(0x6B, 0x6B, 0x6B);
    /** The accent: orange, used on primary buttons and the active tab. */
    public static Color ACCENT       = new Color(0xDB, 0x61, 0x2F);
    public static Color ACCENT_DARK  = new Color(0xC0, 0x4E, 0x20);
    /** Row selection: a light blue selection that stays legible against the orange accent. */
    public static Color ACCENT_LIGHT = new Color(0xCA, 0xDA, 0xF0);
    /** The colour of switched-on controls: check marks, radio dots, toggles, filter chips, selection bars. */
    public static Color CONTROL      = new Color(0x2F, 0x6F, 0xD6);
    /** The tint behind a selected card or list row. */
    public static Color CONTROL_TINT = new Color(0xE8, 0xF0, 0xFB);
    public static Color ACCENT_FAINT = new Color(0xF7, 0xF7, 0xF7);

    // ---- the rest of the palette: everything a tool used to hard-code ----
    /** Cards and other raised panels that sit on the page. */
    public static Color CARD        = new Color(0xFF, 0xFF, 0xFF);
    /** Table headers. */
    public static Color HEADER      = new Color(0xF2, 0xF2, 0xF2);
    /** Strokes of small drawn icons (play, pause, dots, section glyphs). */
    public static Color ICON        = new Color(0x55, 0x55, 0x55);
    /** The track of a switched-off toggle. */
    public static Color TOGGLE_OFF  = new Color(0xB8, 0xBC, 0xC2);
    public static Color SCROLL_THUMB       = new Color(0xC9, 0xCF, 0xD6);
    public static Color SCROLL_THUMB_HOVER = new Color(0xB4, 0xBB, 0xC4);
    /** Success: a finished task, a running service. */
    public static Color OK          = new Color(0x3A, 0x9A, 0x4B);
    /** The search-match highlight in message views. */
    public static Color HIGHLIGHT   = new Color(0xFF, 0xF1, 0x76);
    /** Message syntax colouring: header names and markup tags, the start line, JSON keys, strings, numbers. */
    public static Color CODE_NAME   = new Color(0x33, 0x33, 0x8D);
    public static Color CODE_START  = new Color(0x8A, 0x1F, 0x1F);
    public static Color CODE_KEY    = new Color(0x9A, 0x4A, 0x0A);
    public static Color CODE_STRING = new Color(0x1A, 0x7F, 0x37);
    public static Color CODE_NUMBER = new Color(0x0B, 0x72, 0x85);
    /** Hex view: the offset column and the text column. */
    public static Color HEX_OFFSET  = new Color(0x88, 0x88, 0x88);
    public static Color HEX_ASCII   = new Color(0x33, 0x55, 0x88);
    /** Comparer: backgrounds of deleted, added and modified runs. */
    public static Color DIFF_DELETED  = new Color(0xF7, 0xC9, 0xC9);
    public static Color DIFF_ADDED    = new Color(0xCB, 0xE8, 0xCB);
    public static Color DIFF_MODIFIED = new Color(0xC7, 0xDC, 0xF5);

    /** Whether the dark palette is active (set by {@link #configure}). */
    public static boolean dark = false;

    /** Read the saved theme preference (system property wins, then prefs). */
    public static boolean darkPreferred() {
        String flag = System.getProperty("opentrap.theme");
        if (flag != null) return flag.equalsIgnoreCase("dark");
        try { return "dark".equalsIgnoreCase(
                java.util.prefs.Preferences.userRoot().node("opentrap.v1").get("ui.theme", "light")); }
        catch (Exception e) { return false; }
    }

    /** Point the palette at the light or dark colours. Call before install(). */
    public static void configure(boolean darkMode) {
        dark = darkMode;
        if (darkMode) {
            // Neutral dark greys, cards a step lighter than the page, and the
            // same orange accent as the light theme, so the brand reads the
            // same in both.
            BG           = new Color(0x25, 0x25, 0x25);
            SURFACE      = new Color(0x2C, 0x2C, 0x2C);
            SURFACE_2    = new Color(0x3A, 0x3A, 0x3A);
            SURFACE_3    = new Color(0x4A, 0x4A, 0x4A);
            BORDER       = new Color(0x40, 0x40, 0x40);
            TEXT         = new Color(0xDE, 0xDE, 0xDE);
            MUTED        = new Color(0x9C, 0x9C, 0x9C);
            ACCENT       = new Color(0xDB, 0x61, 0x2F);
            ACCENT_DARK  = new Color(0xC0, 0x4E, 0x20);
            ACCENT_LIGHT = new Color(0x2F, 0x3E, 0x55);
            ACCENT_FAINT = new Color(0x2B, 0x2B, 0x2B);
            CARD         = new Color(0x30, 0x30, 0x30);
            HEADER       = new Color(0x3A, 0x3A, 0x3A);
            ICON         = new Color(0xB4, 0xB4, 0xB4);
            TOGGLE_OFF   = new Color(0x55, 0x5A, 0x62);
            SCROLL_THUMB       = new Color(0x4E, 0x54, 0x5C);
            SCROLL_THUMB_HOVER = new Color(0x62, 0x69, 0x72);
            OK           = new Color(0x5C, 0xC4, 0x6E);
            HIGHLIGHT    = new Color(0x7A, 0x60, 0x00);
            CODE_NAME    = new Color(0x8F, 0xA8, 0xFF);
            CODE_START   = new Color(0xFF, 0x8A, 0x80);
            CODE_KEY     = new Color(0xE8, 0x95, 0x4A);
            CODE_STRING  = new Color(0xE0, 0xC0, 0x6E);
            CODE_NUMBER  = new Color(0x7F, 0xB8, 0xE8);
            HEX_OFFSET   = new Color(0x7A, 0x7A, 0x7A);
            HEX_ASCII    = new Color(0x8F, 0xB0, 0xE0);
            DIFF_DELETED  = new Color(0x6B, 0x2B, 0x2B);
            DIFF_ADDED    = new Color(0x2B, 0x5B, 0x2B);
            DIFF_MODIFIED = new Color(0x2B, 0x45, 0x66);
        } else {
            BG           = new Color(0xFF, 0xFF, 0xFF);
            SURFACE      = new Color(0xEE, 0xEE, 0xEE);
            SURFACE_2    = new Color(0xE4, 0xE4, 0xE4);
            SURFACE_3    = new Color(0xD8, 0xD8, 0xD8);
            BORDER       = new Color(0xD4, 0xD4, 0xD4);
            TEXT         = new Color(0x1A, 0x1A, 0x1A);
            MUTED        = new Color(0x6B, 0x6B, 0x6B);
            ACCENT       = new Color(0xDB, 0x61, 0x2F);
            ACCENT_DARK  = new Color(0xC0, 0x4E, 0x20);
            ACCENT_LIGHT = new Color(0xCA, 0xDA, 0xF0);
            ACCENT_FAINT = new Color(0xF7, 0xF7, 0xF7);
            CARD         = new Color(0xFF, 0xFF, 0xFF);
            HEADER       = new Color(0xF2, 0xF2, 0xF2);
            ICON         = new Color(0x55, 0x55, 0x55);
            TOGGLE_OFF   = new Color(0xB8, 0xBC, 0xC2);
            SCROLL_THUMB       = new Color(0xC9, 0xCF, 0xD6);
            SCROLL_THUMB_HOVER = new Color(0xB4, 0xBB, 0xC4);
            OK           = new Color(0x3A, 0x9A, 0x4B);
            HIGHLIGHT    = new Color(0xFF, 0xF1, 0x76);
            CODE_NAME    = new Color(0x33, 0x33, 0x8D);
            CODE_START   = new Color(0x8A, 0x1F, 0x1F);
            CODE_KEY     = new Color(0x9A, 0x4A, 0x0A);
            CODE_STRING  = new Color(0x1A, 0x7F, 0x37);
            CODE_NUMBER  = new Color(0x0B, 0x72, 0x85);
            HEX_OFFSET   = new Color(0x88, 0x88, 0x88);
            HEX_ASCII    = new Color(0x33, 0x55, 0x88);
            DIFF_DELETED  = new Color(0xF7, 0xC9, 0xC9);
            DIFF_ADDED    = new Color(0xCB, 0xE8, 0xCB);
            DIFF_MODIFIED = new Color(0xC7, 0xDC, 0xF5);
            CONTROL       = new Color(0x5B, 0x8F, 0xE8);
            CONTROL_TINT  = new Color(0x22, 0x2E, 0x44);
        }
    }

    public static final Font FONT      = new Font("Dialog", Font.PLAIN, 12);
    public static final Font FONT_BOLD = new Font("Dialog", Font.BOLD, 12);

    /** Corner radius shared by buttons, fields and cards. */
    public static final int RADIUS = 6;

    /** Client property that marks a button as the primary action of its panel. */
    public static final String PRIMARY = "opentrap.primary";

    /** Install the theme. Call once, before any component is created. */
    public static void install() {
        configure(darkPreferred());
        MetalLookAndFeel.setCurrentTheme(new Palette());
        try { UIManager.setLookAndFeel(new MetalLookAndFeel()); } catch (Exception ignored) {}

        UIDefaults d = UIManager.getDefaults();
        ColorUIResource bg = new ColorUIResource(BG), surface = new ColorUIResource(SURFACE),
                border = new ColorUIResource(BORDER), text = new ColorUIResource(TEXT),
                muted = new ColorUIResource(MUTED), sel = new ColorUIResource(ACCENT_LIGHT);
        FontUIResource font = new FontUIResource(FONT);

        for (String k : new String[]{"Button.font", "ToggleButton.font", "Label.font", "CheckBox.font",
                "RadioButton.font", "ComboBox.font", "TextField.font", "TextArea.font", "TabbedPane.font",
                "Table.font", "TableHeader.font", "Tree.font", "List.font", "Menu.font", "MenuItem.font",
                "MenuBar.font", "ToolTip.font", "ProgressBar.font", "TitledBorder.font", "OptionPane.font",
                "CheckBoxMenuItem.font", "RadioButtonMenuItem.font", "PopupMenu.font", "Spinner.font",
                "PasswordField.font", "EditorPane.font", "TextPane.font", "FormattedTextField.font"}) {
            d.put(k, font);
        }

        // Surfaces
        d.put("Panel.background", bg);
        d.put("Viewport.background", bg);
        d.put("ScrollPane.background", bg);
        d.put("SplitPane.background", bg);
        d.put("TabbedPane.background", bg);
        d.put("TabbedPane.contentAreaColor", bg);
        d.put("OptionPane.background", bg);
        d.put("Label.foreground", text);
        d.put("control", bg);

        // Tabs: text with an accent underline, no boxes.
        d.put("TabbedPaneUI", FlatTabbedPaneUI.class.getName());
        d.put("TabbedPane.foreground", muted);
        d.put("TabbedPane.selectedForeground", text);
        d.put("TabbedPane.tabInsets", new Insets(7, 11, 7, 11));
        d.put("TabbedPane.tabAreaInsets", new Insets(0, 6, 0, 6));
        d.put("TabbedPane.contentBorderInsets", new Insets(1, 0, 0, 0));
        d.put("TabbedPane.selectedTabPadInsets", new Insets(0, 0, 0, 0));
        d.put("TabbedPane.tabRunOverlay", 0);

        // Buttons: flat, rounded, with a filled accent variant.
        d.put("ButtonUI", FlatButtonUI.class.getName());
        d.put("ToggleButtonUI", FlatButtonUI.class.getName());
        d.put("Button.foreground", text);
        d.put("ToggleButton.foreground", text);
        d.put("Button.disabledText", muted);
        d.put("ToggleButton.disabledText", muted);
        // The padding lives in the border so the flat shape keeps its size.
        d.put("Button.border", new javax.swing.plaf.BorderUIResource.EmptyBorderUIResource(5, 12, 5, 12));
        d.put("ToggleButton.border", new javax.swing.plaf.BorderUIResource.EmptyBorderUIResource(5, 12, 5, 12));
        d.put("Button.margin", new Insets(0, 0, 0, 0));
        d.put("ToggleButton.margin", new Insets(0, 0, 0, 0));

        // Inputs
        Border fieldBorder = new RoundedBorder(BORDER, ACCENT, RADIUS, new Insets(4, 7, 4, 7));
        d.put("TextField.border", fieldBorder);
        d.put("PasswordField.border", fieldBorder);
        d.put("FormattedTextField.border", fieldBorder);
        d.put("TextField.background", bg);
        d.put("TextArea.background", bg);
        d.put("TextField.selectionBackground", sel);
        d.put("TextArea.selectionBackground", sel);
        d.put("TextField.selectionForeground", text);
        d.put("TextArea.selectionForeground", text);
        d.put("TextField.caretForeground", text);
        // Every text component, including the styled panes the message views
        // and advisories use, takes the page colours; otherwise they keep the
        // look and feel's white, which the dark theme cannot carry.
        for (String k : new String[]{"TextPane", "EditorPane", "TextArea", "TextField",
                "PasswordField", "FormattedTextField"}) {
            d.put(k + ".background", bg);
            d.put(k + ".foreground", text);
            d.put(k + ".caretForeground", text);
            d.put(k + ".selectionBackground", sel);
            d.put(k + ".selectionForeground", text);
            d.put(k + ".inactiveBackground", surface);
            d.put(k + ".inactiveForeground", muted);
            d.put(k + ".disabledBackground", surface);
        }
        d.put("List.background", bg);
        d.put("List.foreground", text);
        d.put("Tree.foreground", text);
        d.put("Tree.textForeground", text);
        d.put("Tree.textBackground", bg);
        d.put("Table.focusCellForeground", text);
        d.put("Spinner.background", bg);
        d.put("Spinner.foreground", text);
        d.put("Label.disabledForeground", muted);
        d.put("ComboBox.disabledForeground", muted);
        d.put("ComboBoxUI", FlatComboBoxUI.class.getName());
        d.put("ComboBox.background", bg);
        d.put("ComboBox.foreground", text);
        d.put("ComboBox.selectionBackground", sel);
        d.put("ComboBox.selectionForeground", text);
        d.put("ComboBox.border", new RoundedBorder(BORDER, ACCENT, RADIUS, new Insets(2, 6, 2, 2)));
        d.put("ComboBox.squareButton", Boolean.FALSE);
        d.put("ComboBox.padding", new Insets(0, 2, 0, 6));
        d.put("CheckBox.icon", new CheckIcon(false));
        d.put("RadioButton.icon", new CheckIcon(true));
        d.put("CheckBox.foreground", text);
        d.put("RadioButton.foreground", text);

        // Scrollbars: thin, no arrow buttons.
        d.put("ScrollBarUI", FlatScrollBarUI.class.getName());
        d.put("ScrollBar.width", 10);
        d.put("ScrollBar.background", bg);
        d.put("ScrollBar.track", bg);

        // Tables and trees
        d.put("Table.background", bg);
        d.put("Table.foreground", text);
        d.put("Table.gridColor", border);
        d.put("Table.selectionBackground", sel);
        d.put("Table.selectionForeground", text);
        d.put("Table.focusCellHighlightBorder", BorderFactory.createEmptyBorder(1, 1, 1, 1));
        d.put("TableHeader.background", new ColorUIResource(HEADER));
        d.put("TableHeader.foreground", text);
        d.put("TableHeader.cellBorder", BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 1, BORDER), BorderFactory.createEmptyBorder(5, 6, 5, 6)));
        d.put("TableHeader.font", font);
        d.put("Tree.background", bg);
        d.put("Tree.selectionBackground", sel);
        d.put("Tree.selectionForeground", text);
        d.put("Tree.selectionBorderColor", sel);
        d.put("Tree.rowHeight", 22);
        d.put("Tree.expandedIcon", Glyphs.chevron(12, MUTED, 1));
        d.put("Tree.collapsedIcon", Glyphs.chevron(12, MUTED, 0));
        d.put("Tree.leftChildIndent", 10);
        d.put("Tree.rightChildIndent", 12);
        d.put("Tree.paintLines", Boolean.FALSE);
        d.put("Tree.lineTypeDashed", Boolean.FALSE);
        d.put("List.selectionBackground", sel);
        d.put("List.selectionForeground", text);

        // Split panes: a plain hairline divider.
        d.put("SplitPaneUI", "javax.swing.plaf.basic.BasicSplitPaneUI");
        d.put("SplitPane.dividerSize", 6);
        d.put("SplitPaneDivider.border", BorderFactory.createEmptyBorder());
        d.put("SplitPane.border", BorderFactory.createEmptyBorder());
        d.put("SplitPane.background", surface);

        // Menus
        d.put("MenuBar.gradient", null);
        d.put("MenuBar.background", bg);
        d.put("MenuBar.border", BorderFactory.createMatteBorder(0, 0, 1, 0, BORDER));
        d.put("Menu.background", bg);
        d.put("Menu.foreground", text);
        d.put("Menu.selectionBackground", sel);
        d.put("Menu.selectionForeground", text);
        d.put("Menu.border", BorderFactory.createEmptyBorder(4, 8, 4, 8));
        d.put("MenuItem.background", bg);
        d.put("MenuItem.selectionBackground", sel);
        d.put("MenuItem.selectionForeground", text);
        d.put("MenuItem.border", BorderFactory.createEmptyBorder(4, 10, 4, 10));
        d.put("PopupMenu.background", bg);
        d.put("PopupMenu.border", BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER), BorderFactory.createEmptyBorder(4, 0, 4, 0)));
        d.put("Separator.foreground", border);
        d.put("Separator.background", bg);

        // Progress, tooltips, dialogs
        d.put("ProgressBarUI", "javax.swing.plaf.basic.BasicProgressBarUI");
        d.put("ProgressBar.foreground", new ColorUIResource(ACCENT));
        d.put("ProgressBar.background", new ColorUIResource(SURFACE_2));
        d.put("ProgressBar.selectionForeground", bg);
        d.put("ProgressBar.selectionBackground", text);
        d.put("ProgressBar.border", BorderFactory.createEmptyBorder());
        d.put("ToolTip.background", new ColorUIResource(TEXT));
        d.put("ToolTip.foreground", bg);
        d.put("ToolTip.border", BorderFactory.createEmptyBorder(5, 8, 5, 8));
        d.put("OptionPane.messageForeground", text);
        d.put("OptionPane.informationIcon", new DialogIcon(DialogIcon.Kind.INFO));
        d.put("OptionPane.questionIcon", new DialogIcon(DialogIcon.Kind.QUESTION));
        d.put("OptionPane.warningIcon", new DialogIcon(DialogIcon.Kind.WARNING));
        d.put("OptionPane.errorIcon", new DialogIcon(DialogIcon.Kind.ERROR));
        d.put("TitledBorder.titleColor", text);
        d.put("TabbedPane.focus", new Color(0, 0, 0, 0));
    }

    /**
     * An HTML editor kit whose page colours follow the theme. A plain
     * {@code JEditorPane} renders HTML in black on white whatever the look and
     * feel says, which the dark theme cannot carry; every HTML pane (the
     * response render, advisories, issue definitions) takes this kit. A page
     * that brings its own styles still overrides these, as a render should.
     */
    public static javax.swing.text.html.HTMLEditorKit htmlKit() {
        javax.swing.text.html.HTMLEditorKit kit = new javax.swing.text.html.HTMLEditorKit();
        javax.swing.text.html.StyleSheet ss = new javax.swing.text.html.StyleSheet();
        ss.addStyleSheet(kit.getStyleSheet());
        ss.addRule(String.format("body { color: #%06X; background-color: #%06X; font-family: sans-serif; }",
                TEXT.getRGB() & 0xFFFFFF, BG.getRGB() & 0xFFFFFF));
        ss.addRule(String.format("a { color: #%06X; }", ACCENT.getRGB() & 0xFFFFFF));
        ss.addRule(String.format("pre, code { color: #%06X; }", TEXT.getRGB() & 0xFFFFFF));
        kit.setStyleSheet(ss);
        return kit;
    }

    /** A rounded hairline border for cards and grouped content. */
    public static Border cardBorder() {
        return new RoundedBorder(BORDER, null, RADIUS + 2, new Insets(10, 12, 10, 12));
    }

    /** Mark a button as the panel's primary action: filled with the accent. */
    public static <B extends AbstractButton> B primary(B b) {
        b.putClientProperty(PRIMARY, Boolean.TRUE);
        return b;
    }

    /** The base colours the look and feel falls back to for anything not styled here. */
    private static final class Palette extends DefaultMetalTheme {
        private final ColorUIResource p1 = new ColorUIResource(ACCENT_DARK);
        private final ColorUIResource p2 = new ColorUIResource(ACCENT);
        private final ColorUIResource p3 = new ColorUIResource(ACCENT_LIGHT);
        private final ColorUIResource s1 = new ColorUIResource(BORDER);
        private final ColorUIResource s2 = new ColorUIResource(SURFACE_2);
        private final ColorUIResource s3 = new ColorUIResource(BG);
        private final ColorUIResource black = new ColorUIResource(TEXT);
        private final FontUIResource font = new FontUIResource(FONT);
        @Override public String getName() { return "OpenTRAP"; }
        @Override protected ColorUIResource getPrimary1() { return p1; }
        @Override protected ColorUIResource getPrimary2() { return p2; }
        @Override protected ColorUIResource getPrimary3() { return p3; }
        @Override protected ColorUIResource getSecondary1() { return s1; }
        @Override protected ColorUIResource getSecondary2() { return s2; }
        @Override protected ColorUIResource getSecondary3() { return s3; }
        @Override protected ColorUIResource getBlack() { return black; }
        @Override public FontUIResource getControlTextFont() { return font; }
        @Override public FontUIResource getSystemTextFont() { return font; }
        @Override public FontUIResource getUserTextFont() { return font; }
        @Override public FontUIResource getMenuTextFont() { return font; }
        @Override public FontUIResource getWindowTitleFont() { return font; }
        @Override public FontUIResource getSubTextFont() { return font; }
    }
}
