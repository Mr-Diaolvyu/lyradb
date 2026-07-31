package io.github.lexaquila.lyradb.desktop.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLightLaf;

import javax.swing.BorderFactory;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;
import java.awt.Window;

/**
 * LyraDB 原生桌面设计系统。
 */
public final class NativeTheme {

    public static final Color BACKGROUND = color(ThemePalette.Token.BACKGROUND);
    public static final Color SURFACE = color(ThemePalette.Token.SURFACE);
    public static final Color SURFACE_ALT = color(ThemePalette.Token.SURFACE_ALT);
    public static final Color SURFACE_HOVER = color(ThemePalette.Token.SURFACE_HOVER);
    public static final Color TABLE_ALT = color(ThemePalette.Token.TABLE_ALT);
    public static final Color BORDER = color(ThemePalette.Token.BORDER);
    public static final Color BORDER_SOFT = color(ThemePalette.Token.BORDER_SOFT);
    public static final Color FOREGROUND = color(ThemePalette.Token.FOREGROUND);
    public static final Color MUTED = color(ThemePalette.Token.MUTED);
    public static final Color ACCENT = color(ThemePalette.Token.ACCENT);
    public static final Color ACCENT_STRONG = color(ThemePalette.Token.ACCENT_STRONG);
    public static final Color ACCENT_LIGHT = color(ThemePalette.Token.ACCENT_LIGHT);
    public static final Color ACCENT_SOFT = color(ThemePalette.Token.ACCENT_SOFT);
    public static final Color SUCCESS = color(ThemePalette.Token.SUCCESS);
    public static final Color SUCCESS_SOFT = color(ThemePalette.Token.SUCCESS_SOFT);
    public static final Color WARNING = color(ThemePalette.Token.WARNING);
    public static final Color ERROR = color(ThemePalette.Token.ERROR);
    public static final Color ERROR_DARK = color(ThemePalette.Token.ERROR_DARK);
    public static final Color GLASS_SURFACE = color(ThemePalette.Token.GLASS_SURFACE);
    public static final Color GLASS_HIGHLIGHT = color(ThemePalette.Token.GLASS_HIGHLIGHT);
    public static final Color GLASS_BORDER = color(ThemePalette.Token.GLASS_BORDER);
    public static final Color SHADOW = color(ThemePalette.Token.SHADOW);
    public static final Color AURORA_PRIMARY = color(ThemePalette.Token.AURORA_PRIMARY);
    public static final Color AURORA_SECONDARY = color(ThemePalette.Token.AURORA_SECONDARY);

    private static volatile Mode currentMode = Mode.DARK;

    public static final Font FONT_HERO =
            new Font("Microsoft YaHei UI", Font.BOLD, 28);
    public static final Font FONT_TITLE =
            new Font("Microsoft YaHei UI", Font.BOLD, 14);
    public static final Font FONT_BODY =
            new Font("Microsoft YaHei UI", Font.PLAIN, 13);
    public static final Font FONT_CAPTION =
            new Font("Microsoft YaHei UI", Font.PLAIN, 12);
    public static final Font FONT_CAPTION_BOLD =
            new Font("Microsoft YaHei UI", Font.BOLD, 12);
    public static final Font FONT_EYEBROW =
            new Font("Microsoft YaHei UI", Font.BOLD, 11);
    public static final Font FONT_MONO =
            new Font(Font.MONOSPACED, Font.PLAIN, 13);

    public enum Mode {
        DARK("深色"),
        LIGHT("浅色");

        private final String displayName;

        Mode(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public static Mode from(String value) {
            if (value == null || value.isBlank()) {
                return DARK;
            }
            try {
                return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                return DARK;
            }
        }
    }

    private NativeTheme() {
    }

    /**
     * 必须在创建任何 Swing 组件前调用。
     */
    public static void install() {
        install(Mode.DARK);
    }

    public static void install(Mode mode) {
        Mode selectedMode = mode == null ? Mode.DARK : mode;
        Mode previousMode = currentMode;
        ThemePalette.setMode(selectedMode);
        System.setProperty("flatlaf.useWindowDecorations", "true");
        System.setProperty("flatlaf.menuBarEmbedded", "true");
        if (!setupLookAndFeel(selectedMode)) {
            ThemePalette.setMode(previousMode);
            throw new IllegalStateException("无法初始化 LyraDB 主题");
        }
        currentMode = selectedMode;

        putColor("Panel.background", BACKGROUND);
        putColor("Viewport.background", BACKGROUND);
        putColor("RootPane.background", BACKGROUND);
        putColor("Label.foreground", FOREGROUND);
        putColor("Label.disabledForeground", MUTED.darker());
        putColor("Component.accentColor", ACCENT);
        putColor("Component.focusColor", ACCENT);
        putColor("Component.borderColor", BORDER);
        putColor("Component.disabledBorderColor", BORDER_SOFT);
        putColor("Component.focusedBorderColor", ACCENT);
        UIManager.put("Component.arc", 8);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.innerFocusWidth", 0);
        UIManager.put("Component.borderWidth", 1);

        putColor("Button.background", SURFACE_ALT);
        putColor("Button.foreground", FOREGROUND);
        putColor("Button.hoverBackground", SURFACE_HOVER);
        putColor("Button.pressedBackground", ACCENT_SOFT);
        putColor("Button.default.background", ACCENT_STRONG);
        putColor("Button.default.foreground", Color.WHITE);
        UIManager.put("Button.arc", 8);
        UIManager.put("Button.margin", new Insets(7, 12, 7, 12));

        putColor("TextField.background", SURFACE_ALT);
        putColor("TextField.foreground", FOREGROUND);
        putColor("TextField.caretForeground", FOREGROUND);
        putColor("TextField.placeholderForeground", MUTED);
        putColor("PasswordField.background", SURFACE_ALT);
        putColor("PasswordField.foreground", FOREGROUND);
        putColor("PasswordField.caretForeground", FOREGROUND);
        putColor("FormattedTextField.background", SURFACE_ALT);
        putColor("FormattedTextField.foreground", FOREGROUND);
        putColor("TextArea.background", SURFACE_ALT);
        putColor("TextArea.foreground", FOREGROUND);
        putColor("TextArea.caretForeground", FOREGROUND);
        putColor("EditorPane.background", SURFACE_ALT);
        putColor("EditorPane.foreground", FOREGROUND);
        UIManager.put("TextComponent.arc", 7);
        UIManager.put("TextComponent.margin", new Insets(6, 8, 6, 8));

        putColor("ComboBox.background", SURFACE_ALT);
        putColor("ComboBox.foreground", FOREGROUND);
        putColor("ComboBox.buttonBackground", SURFACE_ALT);
        putColor("ComboBox.buttonArrowColor", MUTED);
        UIManager.put("ComboBox.padding", new Insets(4, 7, 4, 7));

        putColor("CheckBox.background", BACKGROUND);
        putColor("CheckBox.foreground", FOREGROUND);
        putColor("Spinner.background", SURFACE_ALT);
        putColor("Spinner.foreground", FOREGROUND);

        putColor("List.background", SURFACE);
        putColor("List.foreground", FOREGROUND);
        putColor("List.selectionBackground", ACCENT_STRONG);
        putColor("List.selectionForeground", Color.WHITE);
        putColor("Tree.background", SURFACE);
        putColor("Tree.foreground", FOREGROUND);
        putColor("Tree.selectionBackground", ACCENT_STRONG);
        putColor("Tree.selectionForeground", Color.WHITE);
        putColor("Tree.selectionInactiveBackground", SURFACE_HOVER);
        UIManager.put("Tree.rowHeight", 28);
        UIManager.put("Tree.wideSelection", true);

        putColor("Table.background", SURFACE);
        putColor("Table.foreground", FOREGROUND);
        putColor("Table.alternateRowColor", TABLE_ALT);
        putColor("Table.gridColor", BORDER_SOFT);
        putColor("Table.selectionBackground", ACCENT_STRONG);
        putColor("Table.selectionForeground", Color.WHITE);
        putColor("TableHeader.background", SURFACE_ALT);
        putColor("TableHeader.foreground", FOREGROUND);
        putColor("TableHeader.separatorColor", BORDER);
        UIManager.put("Table.rowHeight", 28);
        UIManager.put("Table.showHorizontalLines", true);
        UIManager.put("Table.showVerticalLines", false);

        putColor("TabbedPane.background", BACKGROUND);
        putColor("TabbedPane.foreground", MUTED);
        putColor("TabbedPane.selectedBackground", SURFACE);
        putColor("TabbedPane.selectedForeground", FOREGROUND);
        putColor("TabbedPane.underlineColor", ACCENT);
        putColor("TabbedPane.inactiveUnderlineColor", BORDER);
        putColor("TabbedPane.contentAreaColor", BORDER_SOFT);
        UIManager.put("TabbedPane.tabHeight", 36);
        UIManager.put("TabbedPane.showTabSeparators", true);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", false);
        UIManager.put("TabbedPane.tabInsets", new Insets(0, 14, 0, 14));

        putColor("MenuBar.background", SURFACE);
        putColor("MenuBar.foreground", FOREGROUND);
        putColor("Menu.background", SURFACE);
        putColor("Menu.foreground", FOREGROUND);
        putColor("Menu.selectionBackground", SURFACE_HOVER);
        putColor("Menu.selectionForeground", FOREGROUND);
        putColor("MenuItem.background", SURFACE);
        putColor("MenuItem.foreground", FOREGROUND);
        putColor("MenuItem.selectionBackground", SURFACE_HOVER);
        putColor("MenuItem.selectionForeground", FOREGROUND);
        putColor("PopupMenu.background", SURFACE);
        putColor("PopupMenu.borderColor", BORDER);

        putColor("TitlePane.background", SURFACE);
        putColor("TitlePane.foreground", FOREGROUND);
        putColor("TitlePane.inactiveBackground", SURFACE);
        putColor("TitlePane.inactiveForeground", MUTED);
        UIManager.put("TitlePane.unifiedBackground", true);
        UIManager.put("TitlePane.menuBarEmbedded", true);

        putColor("ScrollPane.background", BACKGROUND);
        putColor("ScrollPane.borderColor", BORDER_SOFT);
        putColor("ScrollBar.track", BACKGROUND);
        putColor("ScrollBar.thumb", BORDER);
        putColor("ScrollBar.hoverThumbColor", MUTED.darker());
        UIManager.put("ScrollBar.width", 11);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.trackArc", 999);

        putColor("SplitPane.background", BACKGROUND);
        putColor("SplitPaneDivider.draggingColor", ACCENT);
        UIManager.put("SplitPane.dividerSize", 5);

        putColor("ToolBar.background", SURFACE);
        putColor("ToolBar.foreground", FOREGROUND);
        putColor("Separator.foreground", BORDER_SOFT);
        putColor("ToolTip.background", SURFACE_ALT);
        putColor("ToolTip.foreground", FOREGROUND);
        putColor("OptionPane.background", SURFACE);
        putColor("OptionPane.messageForeground", FOREGROUND);
        putColor("TitledBorder.titleColor", MUTED);

        UIManager.put("defaultFont", FONT_BODY);
        UIManager.put("Label.font", FONT_BODY);
        UIManager.put("Button.font", FONT_BODY);
        UIManager.put("Menu.font", FONT_BODY);
        UIManager.put("MenuItem.font", FONT_BODY);
        UIManager.put("ToolTip.font", FONT_CAPTION);
        UIManager.put("TitledBorder.font", FONT_CAPTION_BOLD);
        UIManager.put("ScrollPane.border",
                BorderFactory.createLineBorder(BORDER_SOFT));
    }

    public static Mode mode() {
        return currentMode;
    }

    /**
     * 在事件分派线程中切换主题，并刷新所有已打开窗口。
     */
    public static void apply(Mode mode) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("主题切换必须在 Swing 事件线程执行");
        }
        install(mode);
        for (Window window : Window.getWindows()) {
            SwingUtilities.updateComponentTreeUI(window);
            window.repaint();
        }
    }

    private static boolean setupLookAndFeel(Mode mode) {
        return mode == Mode.LIGHT ? FlatLightLaf.setup() : FlatDarkLaf.setup();
    }

    private static Color color(ThemePalette.Token token) {
        return ThemePalette.color(token);
    }

    private static void putColor(String key, Color color) {
        UIManager.put(key, new ColorUIResource(color));
    }
}
