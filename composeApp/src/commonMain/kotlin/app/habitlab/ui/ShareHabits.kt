package app.habitlab.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.habitlab.db.HabitDatabase

private val shareMonths =
    listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

internal data class ShareHabit(
    val title: String,
    val state: String?,
)

internal data class ShareSnapshot(
    val dayNumber: Int,
    val date: String,
    val habits: List<ShareHabit>,
    val todayPercent: Int,
    val runPercent: Int,
)

internal fun formatShareText(
    snapshot: ShareSnapshot,
    showHabitCompletion: Boolean,
    showToday: Boolean,
    showRun: Boolean,
): String {
    val month = shareMonths[snapshot.date.substring(5, 7).toInt() - 1]
    val day = snapshot.date.substring(8).toInt()
    val lines = mutableListOf("# Day ${snapshot.dayNumber}, $day $month", "")
    snapshot.habits.forEach { habit ->
        val mark =
            when (habit.state) {
                "done" -> "✓ "
                "partial" -> "◐ "
                else -> "✕ "
            }
        lines += "- ${if (showHabitCompletion) mark else ""}${habit.title}"
    }
    if (showToday || showRun) lines += ""
    if (showToday) lines += "Today: ${snapshot.todayPercent}%"
    if (showRun) lines += "Whole run: ${snapshot.runPercent}%"
    return lines.joinToString("\n")
}

/** The run percentage uses completed eligible habit-days through today, including only opted-in habits. */
internal fun buildShareSnapshot(
    database: HabitDatabase,
    today: String,
): ShareSnapshot? {
    val run = database.currentRun() ?: return null
    val days = run.duration_days.toInt()
    val offset = (0 until days).firstOrNull { addIsoDays(run.start_date, it) == today } ?: return null
    val habits = loadRunHabits(database, run.uid).filter { database.isHabitShareable(it.uid()) }
    val doneDays =
        habits.associate { habit ->
            habit.uid() to
                database.schemaQueries
                    .selectCheckInsForHabit(habit.uid())
                    .executeAsList()
                    .filter { it.state == "done" }
                    .map { it.day }
                    .toSet()
        }

    fun eligible(
        habit: Habit,
        date: String,
    ): Boolean = !isIntervalRestDay(habit.recurrence, date, doneDays[habit.uid()].orEmpty())

    var eligibleTotal = 0
    var completedTotal = 0
    var eligibleToday = 0
    var completedToday = 0
    var todayStates = emptyMap<String, String>()
    (0..offset).forEach { index ->
        val date = addIsoDays(run.start_date, index)
        val states =
            database.schemaQueries
                .selectCheckInsForDay(date)
                .executeAsList()
                .associate { it.habit_uid to it.state }
        if (index == offset) todayStates = states
        habits.forEach { habit ->
            if (eligible(habit, date) || states.containsKey(habit.uid())) {
                eligibleTotal++
                if (states[habit.uid()] == "done") completedTotal++
                if (index == offset) {
                    eligibleToday++
                    if (states[habit.uid()] == "done") completedToday++
                }
            }
        }
    }

    fun percent(
        completed: Int,
        eligible: Int,
    ) = if (eligible == 0) 0 else completed * 100 / eligible
    return ShareSnapshot(
        offset + 1,
        today,
        habits
            .filter { eligible(it, today) || todayStates.containsKey(it.uid()) }
            .map { ShareHabit(it.name, todayStates[it.uid()]) },
        percent(completedToday, eligibleToday),
        percent(completedTotal, eligibleTotal),
    )
}

@Composable
internal fun ShareHabitsPage(
    database: HabitDatabase,
    today: String,
    revision: Int,
    onBack: () -> Unit,
) {
    val snapshot = remember(database, today, revision) { buildShareSnapshot(database, today) }
    var settingsOpen by remember { mutableStateOf(false) }
    var showHabits by remember { mutableStateOf(database.pref(PREF_SHARE_HABITS) != "0") }
    var showToday by remember { mutableStateOf(database.pref(PREF_SHARE_TODAY) != "0") }
    var showRun by remember { mutableStateOf(database.pref(PREF_SHARE_RUN) != "0") }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (settingsOpen) settingsOpen = false else onBack() }) {
                Text("←", modifier = Modifier.semantics { contentDescription = "Back" })
            }
            Text(
                if (settingsOpen) "Share settings" else "Share",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
            )
            if (!settingsOpen) {
                IconButton(onClick = { settingsOpen = true }) {
                    Text("⚙", modifier = Modifier.semantics { contentDescription = "Share settings" })
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (settingsOpen) {
                ShareOption("Habit completion", "Show a status symbol beside each habit.", showHabits) {
                    showHabits = it
                    database.setPref(PREF_SHARE_HABITS, if (it) "1" else "0")
                }
                ShareOption("Today's completion", "Show today's completion percentage.", showToday) {
                    showToday = it
                    database.setPref(PREF_SHARE_TODAY, if (it) "1" else "0")
                }
                ShareOption("Whole-run completion", "Show completion through today.", showRun) {
                    showRun = it
                    database.setPref(PREF_SHARE_RUN, if (it) "1" else "0")
                }
                Text(
                    "Percentages include shareable habits on their eligible days.",
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else if (snapshot == null) {
                Text("Today's date is outside the selected run.", color = muted)
            } else if (snapshot.habits.isEmpty()) {
                Text("No habits are shared today. Open a habit and enable “Include when sharing.”", color = muted)
            } else {
                Text("Day ${snapshot.dayNumber}", style = MaterialTheme.typography.headlineSmall)
                snapshot.habits.forEach { habit ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(habit.title)
                        if (showHabits) {
                            Text(
                                when (habit.state) {
                                    "done" -> "✓"
                                    "partial" -> "◐"
                                    else -> "✕"
                                },
                                color = green,
                            )
                        }
                    }
                }
                if (showToday) Text("Today: ${snapshot.todayPercent}%")
                if (showRun) Text("Whole run: ${snapshot.runPercent}%")
                Card(colors = CardDefaults.cardColors(containerColor = surface)) {
                    Text(formatShareText(snapshot, showHabits, showToday, showRun), Modifier.fillMaxWidth().padding(16.dp))
                }
                Button(onClick = {
                    clipboard.setText(AnnotatedString(formatShareText(snapshot, showHabits, showToday, showRun)))
                    copied = true
                }, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "Copied ✓" else "Copy text") }
            }
        }
    }
}

@Composable
private fun ShareOption(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, color = muted, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
