package app.lumen.player.ui.theme

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Цвета интерфейса — берутся из текущей темы (см. Themes.kt). */
object LC {
    val Bg get() = ThemeStore.current.background
    val Text get() = ThemeStore.current.text
    val Dim get() = ThemeStore.current.textDim
    val Faint get() = ThemeStore.current.textFaint
    val Glass get() = ThemeStore.current.card
    val GlassStrong get() = ThemeStore.current.cardStrong
    val Border get() = ThemeStore.current.border
    val OnAccent get() = ThemeStore.current.onAccent
    val DefaultAccent = Color(0xFFFF7A59)
    val Sheet get() = ThemeStore.current.sheetBottom
}

/** Короткий доступ к текущей теме. */
val LT: ThemeSpec get() = ThemeStore.current

data class CoverColors(val accent: Color, val a: Color, val b: Color, val c: Color)

val DefaultCoverColors = CoverColors(
    accent = LC.DefaultAccent,
    a = Color(0xFFFF5F6D),
    b = Color(0xFF7B2FF7),
    c = Color(0xFFFFB347),
)

val LocalAccent = compositionLocalOf { LC.DefaultAccent }

@Composable
fun LumenTheme(accent: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = LC.OnAccent,
        secondary = accent,
        tertiary = accent,
        background = LC.Bg,
        onBackground = LC.Text,
        surface = LC.Sheet,
        onSurface = LC.Text,
        surfaceVariant = Color(0xFF1E1B26),
        onSurfaceVariant = LC.Dim,
        surfaceContainer = Color(0xFF1C1826),
    )
    MaterialTheme(colorScheme = scheme, typography = Typography()) {
        CompositionLocalProvider(
            LocalAccent provides accent,
            LocalContentColor provides LC.Text,
            content = content,
        )
    }
}
