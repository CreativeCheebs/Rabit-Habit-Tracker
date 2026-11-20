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
import androidx.compose.material3.TextButton
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

internal data class CompletedRunShare(
    val name: String,
    val durationDays: Int,
    val completionPercent: Int,
    val impactPoints: Int,
    val daysLogged: Int,
)

internal fun completedRunShare(
    database: HabitDatabase,
    run: app.habitlab.db.Run,
): CompletedRunShare {
    val days = run.duration_days.toInt()
    val habits = loadRunHabits(database, run.uid)
    val endDate = addIsoDays(run.start_date, days - 1)
    var daysLogged = 0
    for (offset in 0 until days) {
        val date = addIsoDays(run.start_date, offset)
        val states =
            database.schemaQueries
                .selectCheckInsForDay(date)
                .executeAsList()
                .map { it.habit_uid }
                .toSet()
        if (habits.any { it.uid() in states }) daysLogged++
    }
    val completion = runCompletion(database, habits, run.start_date, endDate)
    return CompletedRunShare(
        name = run.name,
        durationDays = days,
        completionPercent = completion.percent,
        impactPoints = completion.earned,
        daysLogged = daysLogged,
    )
}

internal fun formatCompletedRunShare(summary: CompletedRunShare): String =
    buildString {
        appendLine("# ${summary.name} · Run completed")
        appendLine("Duration: ${summary.durationDays} days")
        appendLine("Completion: ${summary.completionPercent}%")
        appendLine("Impact points: ${summary.impactPoints}")
        append("Days logged: ${summary.daysLogged} / ${summary.durationDays}")
    }

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
    if (showToday) lines += "Day: ${snapshot.todayPercent}%"
    if (showRun) lines += "Whole run: ${snapshot.runPercent}%"
    return lines.joinToString("\n")
}

/** The run percentage uses completed eligible habit-days through today, including only opted-in habits. */
internal fun buildShareSnapshot(
    database: HabitDatabase,
    today: String,
    runUid: String? = null,
): ShareSnapshot? {
    val run = if (runUid == null) database.currentRun() else database.schemaQueries.selectRunByUid(runUid).executeAsOneOrNull()
    run ?: return null
    val days = run.duration_days.toInt()
    val offset = (0 until days).firstOrNull { addIsoDays(run.start_date, it) == today } ?: return null
    val runHabits = loadRunHabits(database, run.uid)
    val habits = runHabits.filter { database.isHabitShareable(it.uid()) }
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
    val todayStates =
        database.schemaQueries
            .selectCheckInsForDay(today)
            .executeAsList()
            .associate { it.habit_uid to it.state }
    return ShareSnapshot(
        offset + 1,
        today,
        habits
            .filter { !isIntervalRestDay(it.recurrence, today, doneDays[it.uid()].orEmpty()) || todayStates.containsKey(it.uid()) }
            .map { ShareHabit(it.name, todayStates[it.uid()]) },
        // Day: the shared habits listed above, weighted by impact points.
        runCompletion(database, habits, today, today).percent,
        // Whole run: every habit in the run, the same number the Runs screen and browser app show.
        runCompletion(database, runHabits, run.start_date, today).percent,
    )
}

@Composable
internal fun ShareHabitsPage(
    database: HabitDatabase,
    today: String,
    revision: Int,
    onBack: () -> Unit,
) {
    val runs = remember(database, revision) { database.schemaQueries.selectRuns().executeAsList() }
    var selectedRunUid by remember(database, revision) { mutableStateOf(database.currentRun()?.uid) }
    val run = runs.firstOrNull { it.uid == selectedRunUid }
    val completedRun =
        remember(database, run, today, revision) {
            run?.takeIf { database.isRunEnded(it, today) }?.let { completedRunShare(database, it) }
        }
    val lastShareOffset =
        remember(run, today) {
            if (run == null || today < run.start_date) {
                -1
            } else {
                (0 until run.duration_days.toInt()).lastOrNull { addIsoDays(run.start_date, it) <= today } ?: -1
            }
        }
    var selectedOffset by remember(run?.uid, lastShareOffset) { mutableStateOf(lastShareOffset) }
    val selectedDate = if (run != null && selectedOffset >= 0) addIsoDays(run.start_date, selectedOffset) else today
    val snapshot =
        remember(database, selectedDate, selectedRunUid, revision) {
            selectedRunUid?.let { buildShareSnapshot(database, selectedDate, it) }
        }
    var settingsOpen by remember { mutableStateOf(false) }
    PlatformBackHandler(enabled = settingsOpen, onProgress = {}, onBack = { settingsOpen = false })
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
                ShareOption("Whole-run completion", "Show the run's impact completion through the selected day.", showRun) {
                    showRun = it
                    database.setPref(PREF_SHARE_RUN, if (it) "1" else "0")
                }
                Text(
                    "Day % counts the shared habits listed. Whole run % counts every habit in the run, by impact points, and matches the Runs screen.",
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                if (runs.size > 1) {
                    val index = runs.indexOfFirst { it.uid == selectedRunUid }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = index > 0, onClick = { selectedRunUid = runs[index - 1].uid }) { Text("Previous run") }
                        Text(run?.name.orEmpty(), modifier = Modifier.weight(1f))
                        TextButton(enabled = index >= 0 && index < runs.lastIndex, onClick = { selectedRunUid = runs[index + 1].uid }) {
                            Text("Next run")
                        }
                    }
                }
                if (completedRun != null) {
                    Text("Run completed", style = MaterialTheme.typography.headlineSmall)
                    Text(completedRun.name, style = MaterialTheme.typography.titleMedium)
                    Text("${completedRun.durationDays} days · ${completedRun.completionPercent}% complete", color = muted)
                    Text("${completedRun.impactPoints} impact points", color = green, style = MaterialTheme.typography.titleMedium)
                    Text("${completedRun.daysLogged} / ${completedRun.durationDays} days logged", color = muted)
                    Card(colors = CardDefaults.cardColors(containerColor = surface)) {
                        Text(formatCompletedRunShare(completedRun), Modifier.fillMaxWidth().padding(16.dp))
                    }
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(formatCompletedRunShare(completedRun)))
                        copied = true
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "Copied ✓" else "Copy run summary") }
                } else if (lastShareOffset >= 0) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(enabled = selectedOffset > 0, onClick = {
                            selectedOffset--
                            copied = false
                        }) { Text("Previous day") }
                        Text(selectedDate, modifier = Modifier.weight(1f))
                        TextButton(enabled = selectedOffset < lastShareOffset, onClick = {
                            selectedOffset++
                            copied = false
                        }) { Text("Next day") }
                    }
                }
                if (snapshot == null) {
                    Text("No run day is available to share yet.", color = muted)
                } else if (snapshot.habits.isEmpty()) {
                    Text("No habits are shared on this day. Open a habit and enable sharing.", color = muted)
                } else {
                    Text("Day ${snapshot.dayNumber}", style = MaterialTheme.typography.headlineSmall)
                    snapshot.habits.forEach { habit ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(habit.title)
                            if (showHabits) {
                                Text(
                                    when (habit.state) {
                                        "done" -> "\u2713"
                                        "partial" -> "\u25d0"
                                        else -> "\u2715"
                                    },
                                    color = green,
                                )
                            }
                        }
                    }
                    if (showToday) Text("Day: ${snapshot.todayPercent}%")
                    if (showRun) Text("Whole run: ${snapshot.runPercent}%")
                    Card(colors = CardDefaults.cardColors(containerColor = surface)) {
                        Text(formatShareText(snapshot, showHabits, showToday, showRun), Modifier.fillMaxWidth().padding(16.dp))
                    }
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(formatShareText(snapshot, showHabits, showToday, showRun)))
                        copied = true
                    }, modifier = Modifier.fillMaxWidth()) { Text(if (copied) "Copied \u2713" else "Copy text") }
                }
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
