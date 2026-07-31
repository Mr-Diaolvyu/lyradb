package io.github.lexaquila.lyradb.desktop.ui;

import java.awt.Color;
import java.awt.color.ColorSpace;
import java.util.function.UnaryOperator;

/**
 * 深浅主题共享的动态语义色。组件保留同一个 Color 实例，切换主题后即可重绘。
 */
final class ThemePalette {

    enum Token {
        BACKGROUND,
        SURFACE,
        SURFACE_ALT,
        SURFACE_HOVER,
        TABLE_ALT,
        BORDER,
        BORDER_SOFT,
        FOREGROUND,
        MUTED,
        ACCENT,
        ACCENT_STRONG,
        ACCENT_LIGHT,
        ACCENT_SOFT,
        SUCCESS,
        SUCCESS_SOFT,
        WARNING,
        ERROR,
        ERROR_DARK,
        GLASS_SURFACE,
        GLASS_HIGHLIGHT,
        GLASS_BORDER,
        SHADOW,
        AURORA_PRIMARY,
        AURORA_SECONDARY
    }

    private static final Palette DARK = new Palette(
            rgb(9, 11, 18),
            rgb(17, 22, 34),
            rgb(24, 30, 45),
            rgb(31, 39, 57),
            rgb(20, 25, 38),
            rgb(55, 66, 91),
            rgb(40, 49, 68),
            rgb(232, 235, 243),
            rgb(145, 155, 176),
            rgb(139, 124, 246),
            rgb(111, 92, 236),
            rgb(188, 179, 255),
            rgb(43, 36, 81),
            rgb(66, 215, 163),
            rgb(23, 66, 57),
            rgb(246, 197, 108),
            rgb(255, 107, 122),
            rgb(197, 61, 78),
            rgba(18, 23, 36, 236),
            rgba(255, 255, 255, 10),
            rgba(167, 177, 207, 54),
            rgba(0, 0, 0, 100),
            rgba(125, 102, 255, 42),
            rgba(80, 190, 216, 24));

    private static final Palette LIGHT = new Palette(
            rgb(245, 246, 250),
            rgb(255, 255, 255),
            rgb(239, 241, 247),
            rgb(230, 233, 242),
            rgb(249, 250, 252),
            rgb(191, 197, 211),
            rgb(219, 223, 232),
            rgb(27, 29, 39),
            rgb(92, 100, 119),
            rgb(111, 91, 220),
            rgb(91, 72, 202),
            rgb(91, 72, 202),
            rgb(233, 229, 255),
            rgb(15, 132, 94),
            rgb(219, 246, 236),
            rgb(163, 100, 10),
            rgb(205, 58, 78),
            rgb(180, 39, 60),
            rgba(255, 255, 255, 238),
            rgba(255, 255, 255, 164),
            rgba(145, 153, 178, 112),
            rgba(30, 34, 48, 32),
            rgba(115, 92, 225, 28),
            rgba(69, 164, 190, 18));

    private static volatile NativeTheme.Mode mode = NativeTheme.Mode.DARK;

    private ThemePalette() {
    }

    static void setMode(NativeTheme.Mode value) {
        mode = value == null ? NativeTheme.Mode.DARK : value;
    }

    static Color color(Token token) {
        return new DynamicColor(token, UnaryOperator.identity());
    }

    private static Color resolve(Token token) {
        Palette palette = mode == NativeTheme.Mode.LIGHT ? LIGHT : DARK;
        return switch (token) {
            case BACKGROUND -> palette.background();
            case SURFACE -> palette.surface();
            case SURFACE_ALT -> palette.surfaceAlt();
            case SURFACE_HOVER -> palette.surfaceHover();
            case TABLE_ALT -> palette.tableAlt();
            case BORDER -> palette.border();
            case BORDER_SOFT -> palette.borderSoft();
            case FOREGROUND -> palette.foreground();
            case MUTED -> palette.muted();
            case ACCENT -> palette.accent();
            case ACCENT_STRONG -> palette.accentStrong();
            case ACCENT_LIGHT -> palette.accentLight();
            case ACCENT_SOFT -> palette.accentSoft();
            case SUCCESS -> palette.success();
            case SUCCESS_SOFT -> palette.successSoft();
            case WARNING -> palette.warning();
            case ERROR -> palette.error();
            case ERROR_DARK -> palette.errorDark();
            case GLASS_SURFACE -> palette.glassSurface();
            case GLASS_HIGHLIGHT -> palette.glassHighlight();
            case GLASS_BORDER -> palette.glassBorder();
            case SHADOW -> palette.shadow();
            case AURORA_PRIMARY -> palette.auroraPrimary();
            case AURORA_SECONDARY -> palette.auroraSecondary();
        };
    }

    private static Color rgb(int red, int green, int blue) {
        return new Color(red, green, blue);
    }

    private static Color rgba(int red, int green, int blue, int alpha) {
        return new Color(red, green, blue, alpha);
    }

    private record Palette(Color background, Color surface, Color surfaceAlt,
            Color surfaceHover, Color tableAlt, Color border, Color borderSoft,
            Color foreground, Color muted, Color accent, Color accentStrong,
            Color accentLight, Color accentSoft, Color success, Color successSoft,
            Color warning, Color error, Color errorDark, Color glassSurface,
            Color glassHighlight, Color glassBorder, Color shadow,
            Color auroraPrimary, Color auroraSecondary) {
    }

    private static final class DynamicColor extends Color {
        private final Token token;
        private final UnaryOperator<Color> transform;

        private DynamicColor(Token token, UnaryOperator<Color> transform) {
            super(0, true);
            this.token = token;
            this.transform = transform;
        }

        private Color value() {
            return transform.apply(resolve(token));
        }

        @Override public int getRed() { return value().getRed(); }
        @Override public int getGreen() { return value().getGreen(); }
        @Override public int getBlue() { return value().getBlue(); }
        @Override public int getAlpha() { return value().getAlpha(); }
        @Override public int getRGB() { return value().getRGB(); }
        @Override public int getTransparency() { return value().getTransparency(); }
        @Override public ColorSpace getColorSpace() { return value().getColorSpace(); }
        @Override public float[] getRGBComponents(float[] values) {
            return value().getRGBComponents(values);
        }
        @Override public float[] getRGBColorComponents(float[] values) {
            return value().getRGBColorComponents(values);
        }
        @Override public Color brighter() {
            return new DynamicColor(token, color -> transform.apply(color).brighter());
        }
        @Override public Color darker() {
            return new DynamicColor(token, color -> transform.apply(color).darker());
        }
    }
}
