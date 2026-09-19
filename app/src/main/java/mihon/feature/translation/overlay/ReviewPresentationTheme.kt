package mihon.feature.translation.overlay

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.view.ContextThemeWrapper
import eu.kanade.domain.ui.model.AppTheme
import eu.kanade.domain.ui.model.ThemeMode
import eu.kanade.tachiyomi.ui.base.delegate.ThemingDelegate

/** Resolves the reader's configured colors for background work without changing application preferences. */
internal fun resolveReviewTheme(
    context: Context,
    mode: ThemeMode,
    appTheme: AppTheme,
    amoled: Boolean,
): Context {
    val configuration = Configuration(context.resources.configuration)
    val nightMode = when (mode) {
        ThemeMode.LIGHT -> Configuration.UI_MODE_NIGHT_NO
        ThemeMode.DARK -> Configuration.UI_MODE_NIGHT_YES
        ThemeMode.SYSTEM -> configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
    }
    configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightMode
    val configured = context.createConfigurationContext(configuration)
    val themes = ThemingDelegate.getThemeResIds(appTheme, amoled)
    return ContextThemeWrapper(configured, themes.first()).apply {
        themes.drop(1).forEach { theme.applyStyle(it, true) }
    }
}
