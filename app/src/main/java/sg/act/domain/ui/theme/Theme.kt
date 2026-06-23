package sg.act.domain.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.colorResource
import sg.act.domain.R

/**
 * Builds the Material3 [ColorScheme] from color resources, layered on the correct
 * day/night base.
 *
 * The brand and core surface slots come from values/colors.xml or
 * values-night/colors.xml (served automatically by the resource framework). We
 * start from [darkColorScheme]/[lightColorScheme] so the *tonal* slots we don't
 * override — notably `surfaceContainerHigh`, which dialogs and menus use — get
 * the right neutral light/dark defaults. (Previously everything was built on
 * `lightColorScheme`, so dialogs stayed near-white in dark mode.)
 */
@Composable
private fun oracleColorScheme(): ColorScheme {
    val base = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = colorResource(R.color.primary),
        onPrimary = colorResource(R.color.on_primary),
        secondary = colorResource(R.color.secondary),
        onSecondary = colorResource(R.color.on_secondary),
        background = colorResource(R.color.background),
        onBackground = colorResource(R.color.on_background),
        surface = colorResource(R.color.surface),
        onSurface = colorResource(R.color.on_surface),
        surfaceVariant = colorResource(R.color.surface_variant),
        onSurfaceVariant = colorResource(R.color.on_surface_variant),
        outline = colorResource(R.color.outline),
        error = colorResource(R.color.error),
        onError = colorResource(R.color.on_error),
    )
}

@Composable
fun DomainTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = oracleColorScheme(),
        content = content,
    )
}
