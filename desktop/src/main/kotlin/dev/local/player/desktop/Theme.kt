package dev.local.player.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Цветной образец для выбора акцента и цвета текста. */
data class Swatch(val key: String, val label: String, val color: Color)

/** Акцент — все цветные элементы интерфейса. */
val ACCENTS = listOf(
    Swatch("sky", "Небесный", Color(0xFF8CCBF2)),
    Swatch("mint", "Мятный", Color(0xFF8EE3C8)),
    Swatch("lime", "Лаймовый", Color(0xFFC5E478)),
    Swatch("sun", "Солнечный", Color(0xFFF2D16B)),
    Swatch("peach", "Персиковый", Color(0xFFF5B48A)),
    Swatch("coral", "Коралловый", Color(0xFFF28B82)),
    Swatch("rose", "Розовый", Color(0xFFF4A3C8)),
    Swatch("lilac", "Сиреневый", Color(0xFFC3A6F2)),
    Swatch("lavender", "Лавандовый", Color(0xFFA7B4F5)),
    Swatch("silver", "Серебро", Color(0xFFD3DCE3)),
)

/** Цвет текста — отдельно от акцента. */
val TEXTS = listOf(
    Swatch("white", "Белый", Color(0xFFECEFF1)),
    Swatch("warm", "Тёплый", Color(0xFFF2E8D8)),
    Swatch("gray", "Серый", Color(0xFFB9C2C8)),
    Swatch("blue", "Голубой", Color(0xFFD4EAF7)),
    Swatch("green", "Терминал", Color(0xFFA8F0B8)),
    Swatch("amber", "Янтарь", Color(0xFFFFCF8A)),
    Swatch("pink", "Розовый", Color(0xFFF7D0DF)),
)

fun accentOf(key: String?): Swatch = ACCENTS.find { it.key == key } ?: ACCENTS[0]
fun textOf(key: String?): Swatch = TEXTS.find { it.key == key } ?: TEXTS[0]

/** Цвет панелей (шапка, плеер): полупрозрачный поверх фона-картинки, сплошной без неё. */
val LocalPanelColor = staticCompositionLocalOf { Color(0xFF1A2227) }

private val OnAccent = Color(0xFF10151A)
private val BgColor = Color(0xFF111518)
private val SurfaceColor = Color(0xFF1A2227)

@Composable
fun AkkTheme(accent: Color, text: Color, panelColor: Color, content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = accent,
        onPrimary = OnAccent,
        secondary = accent,
        onSecondary = OnAccent,
        secondaryContainer = accent.copy(alpha = 0.22f),
        onSecondaryContainer = text,
        background = BgColor,
        surface = SurfaceColor,
        onBackground = text,
        onSurface = text,
        onSurfaceVariant = text.copy(alpha = 0.6f),
    )
    CompositionLocalProvider(LocalPanelColor provides panelColor) {
        MaterialTheme(colorScheme = scheme, content = content)
    }
}
