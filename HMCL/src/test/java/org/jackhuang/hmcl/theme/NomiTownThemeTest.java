package org.jackhuang.hmcl.theme;

import org.glavo.monetfx.Brightness;
import org.glavo.monetfx.ColorRole;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Checks bundled Nomi art, light/dark readability and compatibility with stored default-theme references.
@NotNullByDefault
class NomiTownThemeTest {
    /// Both brightness modes must resolve real bundled artwork and readable text/button colors.
    @Test
    void bundledThemeResolvesInLightAndDarkModes() throws Exception {
        ThemePackManifest manifest;
        try (var input = Objects.requireNonNull(getClass().getResourceAsStream("/assets/themes/hmcl.default/manifest.json"))) {
            manifest = JsonUtils.fromNonNullJsonFully(input, ThemePackManifest.class);
        }
        assertEquals("hmcl.default", manifest.id());
        var installed = new ThemePackManager.InstalledThemePack(new ThemePackManager.ThemePackLocation.Builtin(manifest.id()), manifest);
        var theme = manifest.themes().getFirst();
        for (var brightness : new Brightness[]{Brightness.LIGHT, Brightness.DARK}) {
            var context = new ThemeResolveContext(brightness, "windows", "zh-Hans");
            var background = Objects.requireNonNull(ThemePackManager.resolveThemeBackground(installed, theme, context));
            assertEquals(brightness == Brightness.LIGHT ? 0.86 : 0.46, background.opacity(), 0.001);
            try (var input = Objects.requireNonNull(background.imageResource()).openStream()) {
                var image = Objects.requireNonNull(javax.imageio.ImageIO.read(input));
                assertTrue(image.getWidth() >= 1280);
                assertTrue(image.getWidth() > image.getHeight());
            }
            var scheme = theme.resolve(context).toResolvedTheme(context).toColorScheme();
            assertTrue(contrast(scheme.getColor(ColorRole.ON_SURFACE), scheme.getColor(ColorRole.SURFACE)) >= 7);
            assertTrue(contrast(scheme.getColor(ColorRole.ON_PRIMARY), scheme.getColor(ColorRole.PRIMARY)) >= 4.5);
            assertTrue(contrast(scheme.getColor(ColorRole.ON_PRIMARY_CONTAINER), scheme.getColor(ColorRole.PRIMARY_CONTAINER)) >= 4.5);
        }
    }

    /// WCAG relative contrast for the actual generated palette rather than hardcoded sample colors.
    private static double contrast(javafx.scene.paint.Color a, javafx.scene.paint.Color b) {
        double first = luminance(a), second = luminance(b);
        return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
    }

    /// Converts sRGB to linear luminance before computing a contrast ratio.
    private static double luminance(javafx.scene.paint.Color color) {
        return 0.2126 * linear(color.getRed()) + 0.7152 * linear(color.getGreen()) + 0.0722 * linear(color.getBlue());
    }

    /// Applies the standard sRGB transfer function.
    private static double linear(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }
}
