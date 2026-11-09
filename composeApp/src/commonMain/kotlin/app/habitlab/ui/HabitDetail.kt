package app.habitlab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.habitlab.db.HabitDatabase
import kotlin.math.max

/** Everything worth knowing about one habit: version, three numbers, two charts, and its look. */
@Composable internal fun HabitDetailPage(
    database: HabitDatabase,
    habit: Habit,
    today: String,
    onBack: () -> Unit,
    onChanged: () -> Unit,
) {
    var icon by remember(habit.uid()) { mutableStateOf(habit.icon) }
    var colorKey by remember(habit.uid()) { mutableStateOf(habit.color) }
    var showPicker by remember { mutableStateOf(false) }
    val tint = habitColorOf(colorKey) ?: green

    val run = remember(database) { database.currentRun() }
    val runStart = run?.start_date ?: today
    val runDays = run?.duration_days?.toInt() ?: 21
    val elapsed = (0 until runDays).count { addIsoDays(runStart, it) <= today }.coerceIn(1, runDays)
    val checkIns =
        remember(database, habit.uid()) {
            database.schemaQueries.selectCheckInsForHabit(habit.uid()).executeAsList().associate {
                it.day to
                    it.state
            }
        }

    val doneInRun = (0 until elapsed).count { checkIns[addIsoDays(runStart, it)] == "done" }
    val completion = doneInRun * 100 / elapsed
    val streak =
        run {
            // consecutive done days ending today, or yesterday if today is still open
            var day = if (checkIns[today] == "done") today else addIsoDays(today, -1)
            var n = 0
            while (checkIns[day] == "done") {
                n++
                day = addIsoDays(day, -1)
            }
            n
        }
    val totalDone = checkIns.values.count { it == "done" }
    val weeks = (runDays + 6) / 7
    val doneByWeek =
        (0 until weeks).map { w ->
            (w * 7 until minOf(w * 7 + 7, runDays)).count { checkIns[addIsoDays(runStart, it)] == "done" }
        }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = onBack,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Back"
                    },
            ) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Text("Habit", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(18.dp)).background(tint.copy(alpha = .2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        IconGlyph(icon.ifBlank { legacyIcon(habit.name) }, Modifier.size(34.dp), tint)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(habit.name, style = MaterialTheme.typography.titleLarge)
                        Text("Iteration ${habit.iteration} · ${habit.group}", style = MaterialTheme.typography.bodyMedium, color = tint)
                        Text(
                            if (hidePointsPref) habit.recurrence else "${habit.recurrence} · ${habit.points} impact points",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("Completion", "$completion%", "of $elapsed run days", Modifier.weight(1f))
                StatTile("Streak", "$streak", if (streak == 1) "day in a row" else "days in a row", Modifier.weight(1f))
                StatTile("Done", "$totalDone", "all time", Modifier.weight(1f))
            }
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Done per week", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "How many days you completed it in each week of the run",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(top = 3.dp),
                    )
                    val peak = max(doneByWeek.maxOrNull() ?: 0, 1)
                    Canvas(Modifier.fillMaxWidth().height(110.dp).padding(top = 12.dp)) {
                        val slot = size.width / doneByWeek.size.coerceAtLeast(1)
                        val barWidth = (slot * .5f).coerceAtMost(44.dp.toPx())
                        for (line in 1..3) {
                            val y = size.height * line / 4f
                            drawLine(palette.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                        }
                        doneByWeek.forEachIndexed { i, value ->
                            val h = (size.height - 8.dp.toPx()) * value / peak
                            drawRoundRect(
                                tint,
                                Offset(i * slot + (slot - barWidth) / 2, size.height - h),
                                Size(barWidth, h.coerceAtLeast(2.dp.toPx())),
                                CornerRadius(5.dp.toPx()),
                            )
                        }
                    }
                    Row(Modifier.fillMaxWidth()) {
                        doneByWeek.indices.forEach {
                            Text(
                                "W${it + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = muted,
                                modifier = Modifier.weight(1f),
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("Last 28 days", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "Oldest at top left, today at bottom right",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(top = 3.dp, bottom = 10.dp),
                    )
                    (0 until 4).forEach { row ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            (0 until 7).forEach { col ->
                                val date = addIsoDays(today, -(27 - (row * 7 + col)))
                                val fill =
                                    when (checkIns[date]) {
                                        "done" -> tint
                                        "partial" -> palette.partial
                                        "skipped" -> palette.skipped
                                        else -> androidx.compose.ui.graphics.Color.Transparent
                                    }
                                Box(
                                    Modifier
                                        .size(26.dp)
                                        .clip(CircleShape)
                                        .background(fill)
                                        .then(if (checkIns[date] == null) Modifier.border(1.dp, palette.divider, CircleShape) else Modifier)
                                        .then(if (date == today) Modifier.border(2.dp, ink, CircleShape) else Modifier),
                                )
                            }
                        }
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Look", style = MaterialTheme.typography.titleSmall)
                    ColorSwatchRow(colorKey) {
                        colorKey = it
                        database.setHabitColor(habit.uid(), it)
                        onChanged()
                    }
                    OutlinedButton(onClick = { showPicker = true }) { Text("Change icon") }
                    Text("Icon and color are saved on this device only.", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
    if (showPicker) {
        IconPickerSheet(
            selected = icon.ifBlank { legacyIcon(habit.name) },
            tint = tint,
            onPick = {
                icon = it
                database.setHabitIcon(habit.uid(), it)
                showPicker = false
                onChanged()
            },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable private fun StatTile(
    label: String,
    value: String,
    caption: String,
    modifier: Modifier,
) {
    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp), modifier = modifier) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = muted)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = green, modifier = Modifier.padding(vertical = 2.dp))
            Text(caption, style = MaterialTheme.typography.labelSmall, color = muted, textAlign = TextAlign.Center)
        }
    }
}
