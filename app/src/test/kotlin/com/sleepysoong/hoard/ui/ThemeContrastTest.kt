package com.sleepysoong.hoard.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sleepysoong.hoard.ui.glass.IOSSegmentedControl
import com.sleepysoong.hoard.ui.theme.HoardTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The app theme (설정 → 시스템/라이트/다크) can differ from the phone's theme.
 * Every surface must follow the *app* theme; if a thumb follows the phone instead,
 * the selected label is drawn in the same colour as its background and vanishes.
 * Checked in all four app×phone combinations by measuring the rendered pixels.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ThemeContrastTest {
    @get:Rule val compose = createComposeRule()

    private fun selectedSegmentContrast(appDark: Boolean, name: String): Double {
        compose.setContent {
            HoardTheme(darkTheme = appDark) {
                IOSSegmentedControl(
                    options = listOf("시스템", "라이트", "다크"),
                    selectedIndex = 0,
                    onSelect = {},
                    modifier = Modifier.fillMaxWidth().padding(12.dp).testTag("seg")
                )
            }
        }
        val bmp = compose.onNodeWithTag("seg").captureToImage().asAndroidBitmap()
        File(System.getProperty("hoard.artifacts") ?: "build/test-artifacts", "theme").apply { mkdirs() }
            .let { File(it, "$name.png") }.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Selected segment = left third. Background = its most common colour, text = the
        // pixel that differs most from it. WCAG contrast between the two.
        val px = mutableListOf<Int>()
        for (x in 4 until bmp.width / 3 - 4) for (y in 4 until bmp.height - 4) px += bmp.getPixel(x, y)
        val bg = px.groupingBy { it }.eachCount().maxBy { it.value }.key
        val bgLum = Color(bg).luminance()
        val textLum = px.map { Color(it).luminance() }.maxBy { kotlin.math.abs(it - bgLum) }
        val (hi, lo) = maxOf(bgLum, textLum) to minOf(bgLum, textLum)
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertReadable(appDark: Boolean, name: String) {
        val c = selectedSegmentContrast(appDark, name)
        assertTrue("$name: selected label contrast %.2f < 4.5".format(c), c >= 4.5)
    }

    @Test @Config(qualifiers = "notnight") fun appDarkOnLightPhone() = assertReadable(true, "app-dark_phone-light")
    @Test @Config(qualifiers = "night") fun appLightOnDarkPhone() = assertReadable(false, "app-light_phone-dark")
    @Test @Config(qualifiers = "night") fun appDarkOnDarkPhone() = assertReadable(true, "app-dark_phone-dark")
    @Test @Config(qualifiers = "notnight") fun appLightOnLightPhone() = assertReadable(false, "app-light_phone-light")
}
