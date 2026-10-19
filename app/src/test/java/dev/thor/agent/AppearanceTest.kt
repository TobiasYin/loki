package dev.thor.agent

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.pow

class AppearanceTest {
    @Test fun explicitModesOverrideSystemAndUnknownSettingsFollowSystem() {
        for (systemDark in listOf(false, true)) {
            assertEquals(systemDark, ColorMode.SYSTEM.isDark(systemDark))
            assertFalse(ColorMode.LIGHT.isDark(systemDark))
            assertTrue(ColorMode.DARK.isDark(systemDark))
        }
        assertEquals(ColorMode.SYSTEM, ColorMode.parse(null))
        assertEquals(ColorMode.SYSTEM, ColorMode.parse("unexpected"))
        ColorMode.entries.forEach { assertEquals(it, ColorMode.parse(it.key)) }
    }
    @Test fun wallpaperAndImageUseEquivalentCompositingAndKeepVisibleDetails() {
        for (dark in listOf(false, true)) {
            val palette = AppearancePalette(dark)
            assertTrue(palette.scrimAlpha in .1f.. .35f)
            assertEquals(1f, palette.wallpaperScrimAlpha(0), .0001f)
            assertEquals(palette.scrimAlpha, palette.wallpaperScrimAlpha(100), .0001f)
            assertEquals(palette.scrimAlpha, palette.wallpaperScrimAlpha(1000), .0001f)
            assertEquals(1f, palette.wallpaperScrimAlpha(-1), .0001f)
            assertEquals(.6f * (1f - palette.scrimAlpha), 1f - palette.wallpaperScrimAlpha(60), .0001f)
        }
    }
    private fun blend(front: Int, back: Int, alpha: Double): Int = (0..2).fold(0xff000000.toInt()) { result, index ->
        val shift = index * 8
        result or (((((front shr shift) and 255) * alpha + ((back shr shift) and 255) * (1 - alpha)).toInt()) shl shift)
    }
    private fun luminance(color: Int): Double {
        fun channel(shift: Int): Double { val v = ((color shr shift) and 255) / 255.0; return if (v <= .04045) v / 12.92 else ((v + .055) / 1.055).pow(2.4) }
        return .2126 * channel(16) + .7152 * channel(8) + .0722 * channel(0)
    }
    private fun contrast(a: Int, b: Int): Double { val x = luminance(a); val y = luminance(b); return (maxOf(x, y) + .05) / (minOf(x, y) + .05) }
    @Test fun normalAndSecondaryTextStayReadableOverBrightAndDarkWallpapers() {
        for (dark in listOf(false, true)) {
            val p = AppearancePalette(dark)
            for (wallpaper in listOf(0xff000000.toInt(), 0xffffffff.toInt())) {
                val backdrop = blend(p.background, wallpaper, p.scrimAlpha.toDouble())
                val message = blend(p.background, backdrop, if (dark) .78 else .84)
                assertTrue("Main text contrast in $dark", contrast(p.ink, message) >= 4.5)
                assertTrue("Secondary text contrast in $dark", contrast(p.muted, message) >= 4.5)
            }
            assertTrue(contrast(p.ink, p.background) >= 4.5)
            assertTrue(contrast(p.muted, p.surface) >= 4.5)
        }
    }
}
