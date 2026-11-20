package app.habitlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val WEEKLY = "weekly"
private const val INTERVAL = "interval"
private const val DAY_LETTERS = "MTWTFSS"

/**
 * Bottom sheet for creating or editing one habit of a run draft: name, colour, icon, schedule,
 * points and group. Nothing is saved until the run editor's Create / Save.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun HabitSheet(
    initial: Habit?,
    groups: List<String>,
    onDone: (Habit) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableStateOf(initial?.color.orEmpty()) }
    var icon by remember { mutableStateOf(initial?.icon.orEmpty()) }
    var mode by remember { mutableStateOf(if (initial?.recurrence?.startsWith("Every ") == true) INTERVAL else WEEKLY) }
    var count by remember {
        mutableIntStateOf(
            initial?.let { Regex("\\d+").find(it.recurrence)?.value?.toIntOrNull() } ?: 3,
        )
    }
    var points by remember { mutableIntStateOf(initial?.points ?: 1) }
    var group by remember { mutableStateOf(initial?.group?.ifBlank { null } ?: "General") }
    var extraGroups by remember { mutableStateOf(listOf<String>()) }
    var addingGroup by remember { mutableStateOf(false) }
    var newGroup by remember { mutableStateOf("") }
    var showIconPicker by remember { mutableStateOf(false) }
    val interval = mode == INTERVAL
    val maxCount = if (interval) 365 else 7
    val recurrence = if (interval) "Every $count days" else "$count days per week"
    val tone = palette.habitTone(color)
    val preview = Habit(name.ifBlank { "New habit" }, group, points, recurrence, initial?.iteration ?: "I", initial?.id ?: "", icon, color)
    val summary =
        buildString {
            append(scheduleLabel(recurrence))
            if (!hidePointsPref) append(" · $points pts")
            append(" · $group")
        }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    Modifier
                        .size(60.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(tone.tint)
                        .clearAndSetSemantics {},
                    contentAlignment = Alignment.Center,
                ) { HabitGlyph(preview, Modifier.size(32.dp), tone.tint, tone.ink) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        if (initial == null) "New habit" else "Edit habit",
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 20.sp),
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(summary, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = muted)
                }
                if (onRemove != null) {
                    Box(
                        Modifier
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.errorSoft)
                            .clickable(role = Role.Button, onClickLabel = "Remove from this run", onClick = onRemove)
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Remove", color = palette.error, style = MaterialTheme.typography.labelLarge) }
                }
            }

            SheetSection("Name") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    placeholder = { Text("e.g. Morning light") },
                    modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Habit name" },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
                    shape = FIELD_SHAPE,
                    colors = editorFieldColors(),
                )
            }

            SheetSection("Colour") { ColorSwatchRow(color) { color = it } }

            SheetSection("Icon") {
                val keys = (listOfNotNull(resolveIconKey(icon)?.takeIf { it !in FEATURED_ICON_KEYS }) + FEATURED_ICON_KEYS).take(16)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    keys.chunked(8).forEach { rowKeys ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            rowKeys.forEach { key ->
                                val on = resolveIconKey(icon) == key
                                val shape = RoundedCornerShape(12.dp)
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(44.dp)
                                        .clip(shape)
                                        .background(if (on) tone.tint else palette.surface)
                                        .border(if (on) 2.dp else 1.5.dp, if (on) tone.solid else palette.divider, shape)
                                        .clickable(role = Role.RadioButton) { icon = key }
                                        .semantics {
                                            contentDescription = "Icon ${key.replace('_', ' ')}"
                                            stateDescription = if (on) "Selected" else "Not selected"
                                        },
                                    contentAlignment = Alignment.Center,
                                ) { IconGlyph(key, Modifier.size(22.dp), if (on) tone.ink else muted) }
                            }
                            repeat(8 - rowKeys.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    TextButton(onClick = { showIconPicker = true }) { Text("More icons…", color = palette.accent) }
                }
            }

            SheetSection("How often") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SegmentedChoice(
                        options = listOf(WEEKLY to "Days per week", INTERVAL to "Every N days"),
                        selected = mode,
                        onSelect = {
                            mode = it
                            if (it == WEEKLY) count = count.coerceIn(1, 7)
                        },
                    )
                    Stepper(
                        value = count,
                        onChange = { count = it },
                        min = 1,
                        max = maxCount,
                        decreaseLabel = "Fewer",
                        increaseLabel = "More",
                    ) {
                        Text(
                            if (interval) {
                                if (count == 1) "every day" else "days between"
                            } else {
                                "days per week"
                            },
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold),
                            color = muted,
                        )
                    }
                    Row(Modifier.fillMaxWidth().clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DAY_LETTERS.forEachIndexed { i, d ->
                            val hit = if (interval) i % count == 0 else i < count
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(28.dp)
                                    .clip(RoundedCornerShape(9.dp))
                                    .background(if (hit) tone.solid else palette.surfaceAlt),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    d.toString(),
                                    color = if (hit) tone.onSolid else muted,
                                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold),
                                )
                            }
                        }
                    }
                    Text(
                        if (interval) {
                            if (count == 1) "Due every day." else "Due every $count days. If you miss it, it stays due until done."
                        } else {
                            "Do it on any $count ${if (count == 1) "day" else "days"} each week. It shows as due until you hit the target."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                }
            }

            SheetSection("Points each time") {
                Stepper(
                    value = points,
                    onChange = { points = it },
                    min = 0,
                    max = 999,
                    decreaseLabel = "Fewer points",
                    increaseLabel = "More points",
                    valueSize = 20,
                )
            }

            SheetSection("Group") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (groups + extraGroups + group).filter { it.isNotBlank() }.distinct().forEach { g ->
                        ChoicePill(g, g == group, onClick = { group = g })
                    }
                    if (!addingGroup) {
                        ChoicePill("+ New", false, onClick = { addingGroup = true }, dashed = true, contentColor = palette.accent)
                    }
                }
                if (addingGroup) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newGroup,
                            onValueChange = { newGroup = it },
                            singleLine = true,
                            placeholder = { Text("Group name") },
                            modifier = Modifier.weight(1f).semantics { contentDescription = "New group name" },
                            shape = FIELD_SHAPE,
                            colors = editorFieldColors(),
                        )
                        TextButton(
                            enabled = newGroup.isNotBlank(),
                            onClick = {
                                val g = newGroup.trim()
                                extraGroups = extraGroups + g
                                group = g
                                newGroup = ""
                                addingGroup = false
                            },
                        ) { Text("Add") }
                    }
                }
            }

            PrimaryButton(
                "Done",
                enabled = name.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onDone(
                        Habit(
                            name.trim(),
                            group.ifBlank { "General" },
                            points.coerceAtLeast(0),
                            if (interval) "Every ${count.coerceIn(1, 365)} days" else "${count.coerceIn(1, 7)} days per week",
                            initial?.iteration ?: "I",
                            initial?.uid() ?: newHabitId(),
                            icon,
                            color,
                        ),
                    )
                },
            )
        }
    }
    if (showIconPicker) {
        IconPickerSheet(icon, tone.ink, onPick = {
            icon = it
            showIconPicker = false
        }, onDismiss = { showIconPicker = false })
    }
}

@Composable private fun SheetSection(
    label: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold), color = muted)
        content()
    }
}
