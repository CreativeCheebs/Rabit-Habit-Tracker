package app.habitlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.habitlab.db.Goal
import app.habitlab.db.HabitDatabase
import kotlin.math.roundToInt
import kotlin.random.Random

private val GOAL_TINTS = listOf<() -> Color>({ green }, { androidx.compose.ui.graphics.lerp(green, palette.partial, .5f) }, { palette.partial })

/** Mirrors the web app's Goals view: a card per goal with progress from its start date, plus "New goal". */
@Composable internal fun GoalsScreen(database: HabitDatabase, today: String, onLocalChange: () -> Unit) {
    var revision by remember { mutableIntStateOf(0) }
    val goals = remember(database, revision) { database.schemaQueries.selectGoals().executeAsList() }
    var editing by remember { mutableStateOf<Goal?>(null) }
    var creating by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Column {
                Text("Your goals", style = MaterialTheme.typography.titleMedium)
                Text("Progress from each start date. Tap a goal to update it.", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        items(goals.size) { index ->
            val goal = goals[index]
            val tint = GOAL_TINTS[index % GOAL_TINTS.size]()
            val percent = (goal.progress * 100).roundToInt().coerceIn(0, 100)
            Card(
                colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth().clickable { editing = goal }.semantics { contentDescription = "Goal ${goal.description}, $percent percent" }
            ) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(goal.description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Text("$percent%", style = MaterialTheme.typography.titleMedium, color = tint)
                    }
                    Text(
                        "Started ${shortDate(goal.start_date)}" + (goal.target_date?.let { " · target ${shortDate(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
                    )
                    LinearProgressIndicator(progress = { (goal.progress.toFloat()).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(8.dp), color = tint, trackColor = palette.track, gapSize = 0.dp, drawStopIndicator = {})
                }
            }
        }
        item {
            Box(
                Modifier.fillMaxWidth().border(1.5.dp, palette.divider, RoundedCornerShape(18.dp)).clickable { creating = true }.padding(18.dp)
                    .semantics { contentDescription = "New goal" },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("＋ New goal", style = MaterialTheme.typography.titleSmall, color = green)
                    Text("Write it down, track it weekly", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }

    if (creating) GoalDialog(null, onDismiss = { creating = false }, onDelete = null) { description, target, _ ->
        val uid = "g-" + Random.nextInt().toUInt().toString(16).padStart(8, '0')
        database.schemaQueries.upsertGoalRow(uid, description, today, target, 0.0)
        creating = false; revision++; onLocalChange()
    }
    editing?.let { goal ->
        GoalDialog(goal, onDismiss = { editing = null }, onDelete = {
            database.schemaQueries.deleteGoalRow(goal.uid)
            editing = null; revision++; onLocalChange()
        }) { description, target, progress ->
            database.schemaQueries.updateGoal(description, target, progress, goal.uid)
            editing = null; revision++; onLocalChange()
        }
    }
}

@Composable private fun GoalDialog(
    goal: Goal?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)?,
    onSave: (description: String, targetDate: String?, progress: Double) -> Unit,
) {
    var description by remember { mutableStateOf(goal?.description.orEmpty()) }
    var target by remember { mutableStateOf(goal?.target_date.orEmpty()) }
    var progress by remember { mutableFloatStateOf((goal?.progress ?: 0.0).toFloat()) }
    val targetValid = target.isBlank() || isIsoDate(target)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (goal == null) "New goal" else "Edit goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = description, onValueChange = { description = it }, label = { Text("Goal") }, placeholder = { Text("e.g. Read 20 pages daily") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = target, onValueChange = { target = it.take(10) }, label = { Text("Target date (optional)") }, placeholder = { Text("YYYY-MM-DD") },
                    singleLine = true, isError = !targetValid, supportingText = { if (!targetValid) Text("Use YYYY-MM-DD") }, modifier = Modifier.fillMaxWidth()
                )
                if (goal != null) {
                    Text("Progress ${(progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                    Slider(value = progress, onValueChange = { progress = (it * 20).roundToInt() / 20f }, valueRange = 0f..1f, steps = 19, colors = SliderDefaults.colors(thumbColor = green, activeTrackColor = green))
                }
            }
        },
        confirmButton = {
            TextButton(enabled = description.isNotBlank() && targetValid, onClick = { onSave(description.trim(), target.takeIf { it.isNotBlank() }, progress.toDouble()) }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = palette.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}
