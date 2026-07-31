package io.github.lexaquila.lyradb.desktop.ui;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.assertj.core.api.Assertions.assertThat;

class NativeThemeTest {

    @AfterEach
    void restoreDarkMode() {
        ThemePalette.setMode(NativeTheme.Mode.DARK);
    }

    @Test
    void shouldUpdateSemanticColorsWithoutReplacingReferences() {
        Color foreground = NativeTheme.FOREGROUND;
        Color background = NativeTheme.BACKGROUND;

        ThemePalette.setMode(NativeTheme.Mode.DARK);
        int darkForeground = foreground.getRGB();
        int darkBackground = background.getRGB();
        ThemePalette.setMode(NativeTheme.Mode.LIGHT);

        assertThat(NativeTheme.FOREGROUND).isSameAs(foreground);
        assertThat(foreground.getRGB()).isNotEqualTo(darkForeground);
        assertThat(background.getRGB()).isNotEqualTo(darkBackground);
    }

    @Test
    void shouldMeetTextContrastInBothThemes() {
        for (NativeTheme.Mode mode : NativeTheme.Mode.values()) {
            ThemePalette.setMode(mode);
            assertThat(contrast(NativeTheme.FOREGROUND, NativeTheme.BACKGROUND))
                    .as("%s foreground", mode)
                    .isGreaterThanOrEqualTo(4.5D);
            assertThat(contrast(NativeTheme.MUTED, NativeTheme.BACKGROUND))
                    .as("%s muted", mode)
                    .isGreaterThanOrEqualTo(4.5D);
            assertThat(contrast(Color.WHITE, NativeTheme.ACCENT_STRONG))
                    .as("%s selected text", mode)
                    .isGreaterThanOrEqualTo(4.5D);
        }
    }

    @Test
    void shouldParsePersistedThemeSafely() {
        assertThat(NativeTheme.Mode.from("light")).isEqualTo(NativeTheme.Mode.LIGHT);
        assertThat(NativeTheme.Mode.from("unknown")).isEqualTo(NativeTheme.Mode.DARK);
        assertThat(NativeTheme.Mode.from(null)).isEqualTo(NativeTheme.Mode.DARK);
    }

    private static double contrast(Color first, Color second) {
        double lighter = Math.max(luminance(first), luminance(second));
        double darker = Math.min(luminance(first), luminance(second));
        return (lighter + 0.05D) / (darker + 0.05D);
    }

    private static double luminance(Color color) {
        return 0.2126D * linear(color.getRed())
                + 0.7152D * linear(color.getGreen())
                + 0.0722D * linear(color.getBlue());
    }

    private static double linear(int component) {
        double value = component / 255D;
        return value <= 0.04045D
                ? value / 12.92D
                : Math.pow((value + 0.055D) / 1.055D, 2.4D);
    }
}
