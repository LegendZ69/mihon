package mihon.feature.translation.overlay

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import androidx.appcompat.view.ContextThemeWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.color.MaterialColors
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Uses real minified app theme resources; never writes global UI or translator preferences. */
@RunWith(AndroidJUnit4::class)
class QualityReviewThemeTest {
    @Test
    fun backgroundReviewMatchesReaderLightDarkAndAmoledColors() = runBlocking<Unit> {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "review-theme-test-${UUID.randomUUID()}").apply { check(mkdirs()) }
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").apply { check(isDirectory || mkdirs()) }
        }
        val originalConfiguration = Configuration(base.resources.configuration)
        val source = File(directory, "page.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(Color.WHITE)
            source.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            bitmap.recycle()
        }
        try {
            val hash = MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") {
                "%02x".format(it)
            }
            val image = TranslationImage("theme-page", 0, source.path, "image/png", 32, 32, hash, source.length())
            val baseline = TranslationPageResult(image.id, hash, 32, 32, emptyList(), revision = 17)
            var selected = ThemeMode.LIGHT to false
            val renderer = AndroidQualityReviewRenderer(context, presentationContext = {
                resolveReviewTheme(context, selected.first, AppTheme.CATPPUCCIN, selected.second)
            })
            val observedColors = mutableListOf<Pair<Long?, Long?>>()
            val fingerprints = mutableListOf<String>()
            for (choice in listOf(ThemeMode.LIGHT to false, ThemeMode.DARK to false, ThemeMode.DARK to true)) {
                selected = choice
                val reference = readerContext(context, choice.first, AppTheme.CATPPUCCIN, choice.second)
                val expectedText = MaterialColors.getColor(
                    reference,
                    com.google.android.material.R.attr.colorOnSurface,
                    Color.RED,
                )
                val expectedBackground = MaterialColors.getColor(
                    reference,
                    com.google.android.material.R.attr.colorSurface,
                    Color.RED,
                )
                assertNotEquals("The reference must resolve actual app resources", Color.RED, expectedBackground)
                val request = QualityReviewRequest(
                    "synthetic-theme",
                    UUID.randomUUID().toString(),
                    TranslationSettings(),
                    image,
                    baseline,
                )
                val evidence = renderer.prepare(request)
                val resolved = evidence.presentation.resolvedStyle
                assertEquals(expectedText.toUInt().toLong(), resolved.textColor)
                assertEquals(expectedBackground.toUInt().toLong(), resolved.backgroundColor)
                assertEquals(
                    evidence.presentationFingerprint,
                    renderer.presentationFingerprint(baseline, request.settings.style),
                )
                observedColors += resolved.textColor to resolved.backgroundColor
                fingerprints += evidence.presentationFingerprint
                renderer.cleanup(evidence)
            }
            assertEquals(
                "Light, dark, and dark AMOLED must resolve distinct presentations",
                3,
                observedColors.distinct().size,
            )
            assertEquals(3, fingerprints.distinct().size)
            assertEquals(
                "Theme resolution must not mutate application configuration",
                originalConfiguration,
                base.resources.configuration,
            )
            assertEquals(
                hash,
                MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") {
                    "%02x".format(it)
                },
            )
        } finally {
            assertTrue(directory.deleteRecursively())
        }
    }

    @Test
    fun systemModeKeepsTheSuppliedNightConfigurationWithoutChangingOtherFields() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        for (night in listOf(Configuration.UI_MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_YES)) {
            val configured = base.createConfigurationContext(
                Configuration(base.resources.configuration).apply {
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
                },
            )
            val themed = resolveReviewTheme(configured, ThemeMode.SYSTEM, AppTheme.CATPPUCCIN, false)
            assertEquals(night, themed.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
            assertEquals(configured.resources.configuration.densityDpi, themed.resources.configuration.densityDpi)
            assertEquals(configured.resources.configuration.locales, themed.resources.configuration.locales)
            assertEquals(configured.resources.configuration.orientation, themed.resources.configuration.orientation)
        }
    }

    private fun readerContext(context: Context, mode: ThemeMode, appTheme: AppTheme, amoled: Boolean): Context {
        val configured = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or when (mode) {
                    ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
                    ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
                    ThemeMode.SYSTEM -> uiMode and Configuration.UI_MODE_NIGHT_MASK
                }
            },
        )
        // This mirrors BaseActivity.onCreate + ThemingDelegate.applyAppTheme: setTheme in order.
        return ContextThemeWrapper(configured, R.style.Theme_Tachiyomi).apply {
            ThemingDelegate.getThemeResIds(appTheme, amoled).forEach(::setTheme)
        }
    }
}
