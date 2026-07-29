package io.github.lexaquila.lyradb.desktop.ui;

import com.formdev.flatlaf.FlatDarkLaf;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import javax.swing.plaf.ColorUIResource;
import java.awt.Color;
import java.awt.Font;
import java.awt.Insets;

/**
 * LyraDB 原生桌面设计系统。
 */
public final class NativeTheme {

    public static final Color BACKGROUND = new Color(15, 20, 29);
    public static final Color SURFACE = new Color(23, 30, 42);
    public static final Color SURFACE_ALT = new Color(31, 41, 55);
    public static final Color SURFACE_HOVER = new Color(39, 51, 68);
    public static final Color BORDER = new Color(51, 65, 85);
    public static final Color BORDER_SOFT = new Color(40, 52, 70);
    public static final Color FOREGROUND = new Color(229, 234, 242);
    public static final Color MUTED = new Color(148, 163, 184);
    public static final Color ACCENT = new Color(79, 140, 255);
    public static final Color ACCENT_STRONG = new Color(43, 98, 202);
    public static final Color ACCENT_LIGHT = new Color(137, 180, 255);
    public static final Color ACCENT_SOFT = new Color(31, 55, 94);
    public static final Color SUCCESS = new Color(52, 211, 153);
    public static final Color SUCCESS_SOFT = new Color(20, 67, 59);
    public static final Color WARNING = new Color(251, 191, 36);
    public static final Color ERROR = new Color(248, 113, 113);
    public static final Color ERROR_DARK = new Color(185, 52, 69);

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

    private NativeTheme() {
    }

    /**
     * 必须在创建任何 Swing 组件前调用。
     */
    public static void install() {
        System.setProperty("flatlaf.useWindowDecorations", "true");
        System.setProperty("flatlaf.menuBarEmbedded", "true");
        if (!FlatDarkLaf.setup()) {
            throw new IllegalStateException("无法初始化 LyraDB 原生主题");
        }

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
        putColor("Button.default.background", ACCENT);
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
        putColor("List.selectionBackground", ACCENT_SOFT);
        putColor("List.selectionForeground", Color.WHITE);
        putColor("Tree.background", SURFACE);
        putColor("Tree.foreground", FOREGROUND);
        putColor("Tree.selectionBackground", ACCENT_SOFT);
        putColor("Tree.selectionForeground", Color.WHITE);
        putColor("Tree.selectionInactiveBackground", SURFACE_HOVER);
        UIManager.put("Tree.rowHeight", 28);
        UIManager.put("Tree.wideSelection", true);

        putColor("Table.background", SURFACE);
        putColor("Table.foreground", FOREGROUND);
        putColor("Table.alternateRowColor", new Color(27, 36, 49));
        putColor("Table.gridColor", BORDER_SOFT);
        putColor("Table.selectionBackground", ACCENT_SOFT);
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
        putColor("Menu.selectionForeground", Color.WHITE);
        putColor("MenuItem.background", SURFACE);
        putColor("MenuItem.foreground", FOREGROUND);
        putColor("MenuItem.selectionBackground", SURFACE_HOVER);
        putColor("MenuItem.selectionForeground", Color.WHITE);
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

    private static void putColor(String key, Color color) {
        UIManager.put(key, new ColorUIResource(color));
    }
}
