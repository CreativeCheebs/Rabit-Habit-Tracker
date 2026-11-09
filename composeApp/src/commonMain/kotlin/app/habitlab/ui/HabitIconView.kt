package app.habitlab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Keys saved before the Fluent catalog existed, and the names a habit used to imply. */
private val LEGACY_ICON_ALIASES =
    mapOf(
        "sun" to "weather_sunny",
        "moon" to "weather_moon",
        "target" to "target",
        "lines" to "brain_circuit",
        "heart" to "heart",
        "book" to "book",
        "drop" to "drop",
        "bolt" to "flash",
    )

/** Icons offered as quick picks and listed to chatbots in the YAML prompt. */
internal val FEATURED_ICON_KEYS =
    listOf(
        "weather_sunny",
        "weather_moon",
        "target",
        "brain_circuit",
        "heart",
        "book",
        "drop",
        "flash",
        "run",
        "dumbbell",
        "bed",
        "food_apple",
        "drink_coffee",
        "calendar_checkmark_center",
        "notebook",
        "music_note_1",
    )

private val ICON_BY_KEY: Map<String, HabitIconDef> by lazy { HABIT_ICONS.associateBy { it.key } }
private val PATH_CACHE = HashMap<String, Path>()

internal fun resolveIconKey(raw: String): String? {
    val key = LEGACY_ICON_ALIASES[raw] ?: raw
    return key.takeIf { it in ICON_BY_KEY }
}

internal fun isKnownIcon(raw: String): Boolean = resolveIconKey(raw) != null

/** Habits created before icons existed keep the glyph their name always implied. */
internal fun legacyIcon(name: String): String =
    when (name) {
        "Morning light" -> "weather_sunny"
        "Sleep before 11:30 PM" -> "weather_moon"
        "Plan the day" -> "target"
        "Focused work block" -> "brain_circuit"
        else -> "checkmark_circle"
    }

private fun iconPath(key: String): Path? {
    val def = ICON_BY_KEY[key] ?: return null
    return PATH_CACHE.getOrPut(key) { PathParser().parsePathString(def.path).toPath() }
}

/** One catalog icon drawn in [tint]. */
@Composable internal fun IconGlyph(
    key: String,
    modifier: Modifier,
    tint: Color,
) {
    val path = iconPath(resolveIconKey(key) ?: "checkmark_circle") ?: return
    Canvas(modifier) {
        val scale = size.minDimension / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) { drawPath(path, tint) }
    }
}

@Composable internal fun HabitGlyph(
    habit: Habit,
    modifier: Modifier = Modifier,
    cutoutColor: Color = hero,
    tint: Color = green,
) {
    IconGlyph(habit.icon.ifBlank { legacyIcon(habit.name) }, modifier, tint)
}

/** Searchable icon grid in a bottom sheet. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun IconPickerSheet(
    selected: String,
    tint: Color,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val tokens = query.lowercase().split(' ').filter { it.isNotBlank() }
    val matches =
        remember(query) {
            if (tokens.isEmpty()) {
                HABIT_ICONS
            } else {
                HABIT_ICONS.filter { icon ->
                    tokens.all { t ->
                        icon.label.contains(t) ||
                            icon.tags.contains(t)
                    }
                }
            }
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = surface,
    ) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Choose an icon", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text("Search: run, water, read…") },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).semantics { contentDescription = "Search icons" },
            )
            Text(
                "${matches.size} ${if (matches.size == 1) "icon" else "icons"} · Fluent System Icons",
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                modifier = Modifier.padding(top = 6.dp, bottom = 6.dp),
            )
            LazyVerticalGrid(
                columns = GridCells.Adaptive(56.dp),
                modifier = Modifier.height(380.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(matches, key = { it.key }) { icon ->
                    val on = resolveIconKey(selected) == icon.key
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (on) palette.accentSoft else palette.tile)
                            .border(if (on) 2.dp else 0.dp, tint, RoundedCornerShape(14.dp))
                            .clickable { onPick(icon.key) }
                            .semantics { contentDescription = "Icon ${icon.label}" },
                        contentAlignment = Alignment.Center,
                    ) { IconGlyph(icon.key, Modifier.size(28.dp), if (on) tint else ink) }
                }
            }
        }
    }
}

/** Row of color swatches; the first (blank) means "use the accent". */
@Composable internal fun ColorSwatchRow(
    selected: String,
    onPick: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        HABIT_COLORS.forEach { (key, color) ->
            val on = selected == key
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (key.isEmpty()) green else color)
                    .border(if (on) 3.dp else 0.dp, ink, CircleShape)
                    .clickable { onPick(key) }
                    .semantics { contentDescription = if (key.isEmpty()) "Color accent" else "Color $key" },
                contentAlignment = Alignment.Center,
            ) { if (key.isEmpty()) Text("A", color = palette.onAccent, style = MaterialTheme.typography.labelSmall) }
        }
    }
}
