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

/** Every color the UI draws with, derived from style (Classic or Japanese), dark/light mode and one accent. */
internal class Palette(
    val dark: Boolean,
    val wa: Boolean,
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
    val field: Color,
    val tile: Color,
    val emptyDay: Color,
    val completed: Color,
    val incomplete: Color,
    val partial: Color,
    val partialSoft: Color,
    val skipped: Color,
    val skippedSoft: Color,
    val error: Color,
    val errorSoft: Color,
    val streak: Color,
    val streakSoft: Color,
    val streakInk: Color,
    val warnBg: Color,
    val warnInk: Color,
    val warnIcon: Color,
    val skipBg: Color,
    val skipInk: Color,
)

/** Accent picker entry; keys are stored in prefs and never change. */
internal class AccentOption(
    val key: String,
    val label: String,
    val classicLight: Color,
    val classicDark: Color,
    val waLight: Color,
    val waDark: Color,
    val waName: String,
) {
    fun value(
        wa: Boolean,
        dark: Boolean,
    ): Color =
        when {
            wa && dark -> waDark
            wa -> waLight
            dark -> classicDark
            else -> classicLight
        }
}

internal val ACCENTS =
    listOf(
        AccentOption("green", "Green", Color(0xFF1F7A4D), Color(0xFF4CC38A), Color(0xFF56652A), Color(0xFFA9BC6E), "Matcha"),
        AccentOption("teal", "Teal", Color(0xFF00756A), Color(0xFF3DD9C5), Color(0xFF00737B), Color(0xFF4FCBD3), "Asagi"),
        AccentOption("blue", "Blue", Color(0xFF2459C4), Color(0xFF8AB4FF), Color(0xFF165E83), Color(0xFF6FA8C9), "Ai"),
        AccentOption("purple", "Purple", Color(0xFF6E44B8), Color(0xFFC3A6F5), Color(0xFF6A4E96), Color(0xFFBBA6DD), "Fuji"),
        AccentOption("pink", "Pink", Color(0xFFB8326F), Color(0xFFF5A3C7), Color(0xFFA8506A), Color(0xFFF0B5C5), "Sakura"),
        AccentOption("orange", "Orange", Color(0xFFB4490F), Color(0xFFFFA866), Color(0xFFB23E0E), Color(0xFFF08A64), "Shu"),
        AccentOption("yellow", "Yellow", Color(0xFF8A6100), Color(0xFFF0CF5C), Color(0xFF8A5F00), Color(0xFFF5C342), "Yamabuki"),
        AccentOption("red", "Red", Color(0xFFB42318), Color(0xFFFF9C8F), Color(0xFFA3262C), Color(0xFFF08A8E), "Beni"),
    )

internal const val STYLE_CLASSIC = "classic"
internal const val STYLE_WA = "wa"

/** One stored habit colour key with its per-style shades. */
internal class HabitColorDef(
    val key: String,
    val name: String,
    val solid: Color,
    val tint: Color,
    val ink: Color,
    val tintDark: Color,
    val inkDark: Color,
    val onSolid: Color,
)

/** The four shades a habit is drawn with in the current mode. */
internal class HabitTone(
    val solid: Color,
    val tint: Color,
    val ink: Color,
    val onSolid: Color,
)

private val WHITE = Color(0xFFFFFFFF)

private fun def(
    key: String,
    name: String,
    solid: Long,
    tint: Long,
    ink: Long,
    tintDark: Long,
    inkDark: Long,
    onSolid: Long = 0xFFFFFFFF,
) = HabitColorDef(key, name, Color(solid), Color(tint), Color(ink), Color(tintDark), Color(inkDark), Color(onSolid))

internal val CLASSIC_HABIT_COLORS =
    listOf(
        def("red", "Tomato", 0xFFE5484D, 0xFFFEEBEC, 0xFFCE2C31, 0xFF3B1219, 0xFFFF977D),
        def("orange", "Carrot", 0xFFF76B15, 0xFFFFEFD6, 0xFFCC4E00, 0xFF331E0B, 0xFFFFA057),
        def("yellow", "Sun", 0xFFFFC53D, 0xFFFFF7C2, 0xFF9E6C00, 0xFF2D2305, 0xFFFFCA16, 0xFF3D2B00),
        def("green", "Leaf", 0xFF30A46C, 0xFFE6F6EB, 0xFF18794E, 0xFF132D21, 0xFF3DD68C),
        def("teal", "Teal", 0xFF12A594, 0xFFE0F8F3, 0xFF008573, 0xFF0D2D2A, 0xFF0BD8B6),
        def("blue", "Sky", 0xFF0090FF, 0xFFE6F4FE, 0xFF0D74CE, 0xFF0D2847, 0xFF70B8FF),
        def("indigo", "Indigo", 0xFF3E63DD, 0xFFEDF2FE, 0xFF3A5BC7, 0xFF182449, 0xFF9EB1FF),
        def("purple", "Grape", 0xFF8E4EC6, 0xFFF7EDFE, 0xFF8145B5, 0xFF301C3B, 0xFFD19DFF),
        def("pink", "Pink", 0xFFD6409F, 0xFFFEE9F5, 0xFFC2298A, 0xFF37172F, 0xFFFF8DCC),
        def("brown", "Cocoa", 0xFFAD7F58, 0xFFF6EEE7, 0xFF815E46, 0xFF2E201A, 0xFFDBB594),
        def("gray", "Slate", 0xFF8B8D98, 0xFFF0F0F3, 0xFF60646C, 0xFF212225, 0xFFB0B4BA),
    )

internal val WA_HABIT_COLORS =
    listOf(
        def("red", "Beni", 0xFFC53D43, 0xFFF9E4E3, 0xFFA3262C, 0xFF331517, 0xFFF08A8E),
        def("orange", "Shu", 0xFFE2541C, 0xFFFCE6DA, 0xFFB23E0E, 0xFF3A1A12, 0xFFF08A64),
        def("yellow", "Yamabuki", 0xFFF8B500, 0xFFFEF3D0, 0xFF8A5F00, 0xFF2E2306, 0xFFF5C342, 0xFF3A2A00),
        def("green", "Wakatake", 0xFF68BE8D, 0xFFE3F3E9, 0xFF2F7A52, 0xFF152A1F, 0xFF7FD3A3),
        def("teal", "Asagi", 0xFF00A3AF, 0xFFDDF3F4, 0xFF00737B, 0xFF0E2B2D, 0xFF4FCBD3),
        def("blue", "Sora", 0xFF3E8FC9, 0xFFE2EFF8, 0xFF1F6699, 0xFF13263A, 0xFF86BCE6),
        def("indigo", "Ai", 0xFF165E83, 0xFFE1EBF1, 0xFF165E83, 0xFF14232D, 0xFF8DBBD6),
        def("purple", "Fuji", 0xFF8F77B5, 0xFFEEE8F5, 0xFF6A4E96, 0xFF261F33, 0xFFBBA6DD),
        def("pink", "Sakura", 0xFFE4A3B5, 0xFFFBEAEF, 0xFFA8506A, 0xFF33202A, 0xFFF0B5C5, 0xFF4A1F2C),
        def("brown", "Matcha", 0xFF7B8D42, 0xFFEEF1DE, 0xFF56652A, 0xFF22281A, 0xFFA9BC6E),
        def("gray", "Nezumi", 0xFF9E9B95, 0xFFEFEDEA, 0xFF5E5B56, 0xFF262523, 0xFFB5B0A8),
    )

/** Stored habit colour keys offered in the habit editor; blank means "use the accent". */
internal val HABIT_COLOR_KEYS = CLASSIC_HABIT_COLORS.map { it.key }

internal fun habitColorDefs(wa: Boolean): List<HabitColorDef> = if (wa) WA_HABIT_COLORS else CLASSIC_HABIT_COLORS

/** Shades for a stored habit colour key in this palette; blank or unknown keys follow the accent. */
internal fun Palette.habitTone(key: String): HabitTone {
    val color = habitColorDefs(wa).firstOrNull { it.key == key && key.isNotEmpty() }
    return if (color == null) {
        HabitTone(accent, accentSoft, accent, onAccent)
    } else {
        HabitTone(color.solid, if (dark) color.tintDark else color.tint, if (dark) color.inkDark else color.ink, color.onSolid)
    }
}

/** Solid colour of a stored habit colour key, or null for blank/unknown (use the accent). */
internal fun habitColorOf(key: String): Color? = habitColorDefs(palette.wa).firstOrNull { it.key == key && key.isNotEmpty() }?.solid

internal fun buildPalette(
    dark: Boolean,
    accentKey: String,
    style: String = STYLE_CLASSIC,
): Palette {
    val wa = style == STYLE_WA
    val option = ACCENTS.firstOrNull { it.key == accentKey } ?: ACCENTS.first()
    val accent = option.value(wa, dark)
    val defaultAccent = if (wa) "blue" else "green"
    val bg: Color
    val surface: Color
    val surfaceAlt: Color
    val ink: Color
    val muted: Color
    val divider: Color
    val field: Color
    val track: Color
    val streak: Color
    val streakSoft: Color
    val streakInk: Color
    val error: Color
    val errorSoft: Color
    val partial: Color
    val defaultHero: Color
    if (wa) {
        bg = if (dark) Color(0xFF161514) else Color(0xFFF7F3EA)
        surface = if (dark) Color(0xFF201E1C) else Color(0xFFFFFDF8)
        surfaceAlt = if (dark) Color(0xFF2A2724) else Color(0xFFEFE8DA)
        defaultHero = if (dark) Color(0xFF14232D) else Color(0xFFE3ECF1)
        ink = if (dark) Color(0xFFEDE6DA) else Color(0xFF23201C)
        muted = if (dark) Color(0xFFA69E92) else Color(0xFF6E665C)
        divider = if (dark) Color(0xFF35312C) else Color(0xFFE6DFD1)
        field = if (dark) Color(0xFF3D3832) else Color(0xFFDDD4C3)
        track = if (dark) Color(0xFF2B2825) else Color(0xFFE8E1D3)
        streak = if (dark) Color(0xFFF08A64) else Color(0xFFE2541C)
        streakSoft = if (dark) Color(0xFF3A1A12) else Color(0xFFFBE4DA)
        streakInk = if (dark) Color(0xFFF08A64) else Color(0xFFB5381A)
        error = if (dark) Color(0xFFF08A8E) else Color(0xFFA3262C)
        errorSoft = if (dark) Color(0xFF331517) else Color(0xFFF9E4E3)
        partial = if (dark) Color(0xFFD9A21C) else Color(0xFFF8B500)
    } else {
        bg = if (dark) Color(0xFF131210) else Color(0xFFFBF8F3)
        surface = if (dark) Color(0xFF1C1A17) else Color(0xFFFFFFFF)
        surfaceAlt = if (dark) Color(0xFF252220) else Color(0xFFF4EFE7)
        defaultHero = if (dark) Color(0xFF16291F) else Color(0xFFE6F4EA)
        ink = if (dark) Color(0xFFF2EDE6) else Color(0xFF211D18)
        muted = if (dark) Color(0xFFA9A096) else Color(0xFF6B6259)
        divider = if (dark) Color(0xFF322E29) else Color(0xFFECE5DA)
        field = if (dark) Color(0xFF3A352F) else Color(0xFFE2D9CC)
        track = if (dark) Color(0xFF34302A) else Color(0xFFE9E2D7)
        streak = if (dark) Color(0xFFFFA057) else Color(0xFFF76B15)
        streakSoft = if (dark) Color(0xFF331E0B) else Color(0xFFFFEFD6)
        streakInk = if (dark) Color(0xFFFFA057) else Color(0xFFCC4E00)
        error = if (dark) Color(0xFFFF977D) else Color(0xFFCE2C31)
        errorSoft = if (dark) Color(0xFF3B1219) else Color(0xFFFEEBEC)
        partial = if (dark) Color(0xFFC99A2E) else Color(0xFFFFC53D)
    }
    val hero = if (option.key == defaultAccent) defaultHero else lerp(surface, accent, if (dark) .14f else .12f)
    val skipBg = if (dark) Color(0xFF2B2A2E) else Color(0xFFF0F0F3)
    val skipInk = if (dark) Color(0xFFB0B4BA) else Color(0xFF60646C)
    return Palette(
        dark = dark,
        wa = wa,
        bg = bg,
        surface = surface,
        surfaceAlt = surfaceAlt,
        hero = hero,
        ink = ink,
        muted = muted,
        accent = accent,
        onAccent = if (dark) Color(0xFF0F0E0D) else WHITE,
        accentSoft = lerp(surface, accent, if (dark) .22f else .14f),
        accentSoft2 = lerp(surface, accent, if (dark) .12f else .08f),
        track = track,
        divider = divider,
        field = field,
        tile = surfaceAlt,
        emptyDay = track,
        completed = accent,
        incomplete = streak,
        partial = partial,
        partialSoft = lerp(surface, partial, .28f),
        skipped = skipInk,
        skippedSoft = skipBg,
        error = error,
        errorSoft = errorSoft,
        streak = streak,
        streakSoft = streakSoft,
        streakInk = streakInk,
        warnBg = if (dark) Color(0xFF2D2305) else Color(0xFFFFF7C2),
        warnInk = if (dark) Color(0xFFFFE29A) else Color(0xFF5C3D00),
        warnIcon = if (dark) Color(0xFFFFCA16) else Color(0xFF9E6C00),
        skipBg = skipBg,
        skipInk = skipInk,
    )
}

// Theme inputs live in snapshot state so any composable or draw lambda that reads `palette` updates with them.
internal var themeModePref by mutableStateOf("system")
internal var accentPref by mutableStateOf("green")
internal var stylePref by mutableStateOf(STYLE_CLASSIC)
internal var systemDarkState by mutableStateOf(true)
internal var hidePointsPref by mutableStateOf(false)

internal val palette: Palette by derivedStateOf {
    val dark = themeModePref == "dark" || (themeModePref == "system" && systemDarkState)
    buildPalette(dark, accentPref, stylePref)
}

internal fun Palette.toColorScheme(): ColorScheme =
    if (dark) {
        darkColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentSoft,
            onPrimaryContainer = ink,
            background = bg,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            onSurfaceVariant = muted,
            secondary = accent,
            secondaryContainer = accentSoft,
            onSecondaryContainer = ink,
            tertiary = streak,
            surfaceVariant = surfaceAlt,
            outline = field,
            outlineVariant = divider,
            surfaceContainerLowest = bg,
            surfaceContainerLow = surface,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceAlt,
            surfaceContainerHighest = surfaceAlt,
            error = error,
            errorContainer = errorSoft,
        )
    } else {
        lightColorScheme(
            primary = accent,
            onPrimary = onAccent,
            primaryContainer = accentSoft,
            onPrimaryContainer = ink,
            background = bg,
            onBackground = ink,
            surface = surface,
            onSurface = ink,
            onSurfaceVariant = muted,
            secondary = accent,
            secondaryContainer = accentSoft,
            onSecondaryContainer = ink,
            tertiary = streak,
            surfaceVariant = surfaceAlt,
            outline = field,
            outlineVariant = divider,
            surfaceContainerLowest = bg,
            surfaceContainerLow = surface,
            surfaceContainer = surface,
            surfaceContainerHigh = surfaceAlt,
            surfaceContainerHighest = surfaceAlt,
            error = error,
            errorContainer = errorSoft,
        )
    }
