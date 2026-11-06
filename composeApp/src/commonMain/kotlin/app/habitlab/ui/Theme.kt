package app.habitlab.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Every color the UI draws with, derived from dark/light mode plus one accent seed. */
internal class Palette(
    val dark: Boolean,
    val bg: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val hero: Color,
    val ink: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val accentSoft2: Color,
    val track: Color,
    val divider: Color,
    val tile: Color,
    val emptyDay: Color,
    val partial: Color,
    val partialSoft: Color,
    val skipped: Color,
    val skippedSoft: Color,
    val error: Color,
)

internal class AccentOption(val key: String, val label: String, val seed: Color)

internal val ACCENTS = listOf(
    AccentOption("green", "Green", Color(0xFFA4D0AD)),
    AccentOption("teal", "Teal", Color(0xFF8FD3CF)),
    AccentOption("blue", "Blue", Color(0xFFA9C7FF)),
    AccentOption("purple", "Purple", Color(0xFFCDB7F5)),
    AccentOption("pink", "Pink", Color(0xFFF2B1C9)),
    AccentOption("orange", "Orange", Color(0xFFF5BC8A)),
    AccentOption("yellow", "Yellow", Color(0xFFE3CE7A)),
    AccentOption("red", "Red", Color(0xFFF0A9A0)),
)

/** Habit colors offered in the habit editor; blank means "use the accent". */
internal val HABIT_COLORS = listOf(
    "" to Color.Unspecified,
    "red" to Color(0xFFE57373), "orange" to Color(0xFFFFA45B), "yellow" to Color(0xFFE6C84F),
    "green" to Color(0xFF72C58A), "teal" to Color(0xFF4DB6AC), "blue" to Color(0xFF6FA3F0),
    "purple" to Color(0xFFA98BE8), "pink" to Color(0xFFEC82B0), "brown" to Color(0xFFB08968), "gray" to Color(0xFF9AA5A0),
)

internal fun habitColorOf(key: String): Color? = HABIT_COLORS.firstOrNull { it.first == key && key.isNotEmpty() }?.second

internal fun buildPalette(dark: Boolean, accentKey: String): Palette {
    val seed = (ACCENTS.firstOrNull { it.key == accentKey } ?: ACCENTS.first()).seed
    return if (dark) {
        val bg = lerp(Color(0xFF101311), seed, .04f)
        val surface = lerp(Color(0xFF1A1F1B), seed, .05f)
        Palette(
            dark = true, bg = bg, surface = surface, surfaceAlt = lerp(surface, Color.White, .05f),
            hero = lerp(Color(0xFF151A16), seed, .06f),
            ink = Color(0xFFE5ECE6), muted = Color(0xFFA3AFA6),
            accent = seed, onAccent = lerp(seed, Color.Black, .78f),
            accentSoft = lerp(surface, seed, .26f), accentSoft2 = lerp(surface, seed, .14f), track = lerp(surface, seed, .32f),
            divider = lerp(surface, Color.White, .12f), tile = lerp(surface, Color.White, .08f), emptyDay = lerp(surface, Color.White, .06f),
            partial = Color(0xFFE6BD76), partialSoft = Color(0xFF4A402A),
            skipped = Color(0xFF8A5757), skippedSoft = Color(0xFF5A3532), error = Color(0xFFE7B4AD),
        )
    } else {
        val accent = lerp(seed, Color.Black, .42f)
        val bg = lerp(Color(0xFFF6F7F5), seed, .06f)
        val surface = lerp(Color.White, seed, .05f)
        Palette(
            dark = false, bg = bg, surface = surface, surfaceAlt = lerp(surface, seed, .08f),
            hero = lerp(Color.White, seed, .12f),
            ink = Color(0xFF191C1A), muted = Color(0xFF5A635D),
            accent = accent, onAccent = Color.White,
            accentSoft = lerp(surface, seed, .55f), accentSoft2 = lerp(surface, seed, .28f), track = lerp(surface, seed, .5f),
            divider = lerp(surface, Color.Black, .1f), tile = lerp(surface, seed, .3f), emptyDay = lerp(surface, Color.Black, .06f),
            partial = Color(0xFFB77B10), partialSoft = Color(0xFFF6E3BE),
            skipped = Color(0xFFD08C87), skippedSoft = Color(0xFFF7D9D6), error = Color(0xFFB3261E),
        )
    }
}

// Theme inputs live in snapshot state so any composable or draw lambda that reads `palette` updates with them.
internal var themeModePref by mutableStateOf("system")
internal var accentPref by mutableStateOf("green")
internal var systemDarkState by mutableStateOf(true)
internal var hidePointsPref by mutableStateOf(false)

internal val palette: Palette by derivedStateOf {
    val dark = themeModePref == "dark" || (themeModePref == "system" && systemDarkState)
    buildPalette(dark, accentPref)
}

internal fun Palette.toColorScheme(): ColorScheme =
    if (dark) darkColorScheme(
        primary = accent, onPrimary = onAccent, background = bg, onBackground = ink,
        surface = surface, onSurface = ink, onSurfaceVariant = muted,
        secondary = accent, secondaryContainer = accentSoft, onSecondaryContainer = ink,
        surfaceVariant = surfaceAlt, outline = muted, outlineVariant = divider,
        surfaceContainer = surface, surfaceContainerHigh = surfaceAlt, error = error,
    ) else lightColorScheme(
        primary = accent, onPrimary = onAccent, background = bg, onBackground = ink,
        surface = surface, onSurface = ink, onSurfaceVariant = muted,
        secondary = accent, secondaryContainer = accentSoft, onSecondaryContainer = ink,
        surfaceVariant = surfaceAlt, outline = muted, outlineVariant = divider,
        surfaceContainer = surface, surfaceContainerHigh = surfaceAlt, error = error,
    )
