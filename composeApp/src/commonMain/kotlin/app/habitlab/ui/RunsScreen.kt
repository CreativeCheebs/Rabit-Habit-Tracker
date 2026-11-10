package app.habitlab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.habitlab.db.HabitDatabase
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

private val MONTHS = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private const val WEEKDAY_LETTERS = "SMTWTFS"

/** 0 = Sunday … 6 = Saturday (Sakamoto). */
internal fun isoDayOfWeek(date: String): Int {
    val parts = date.split("-")
    var y = parts[0].toInt()
    val m = parts[1].toInt()
    val d = parts[2].toInt()
    val t = intArrayOf(0, 3, 2, 5, 0, 3, 5, 1, 4, 6, 2, 4)
    if (m < 3) y -= 1
    return (y + y / 4 - y / 100 + y / 400 + t[m - 1] + d) % 7
}

internal fun shortDate(date: String): String = "${MONTHS[date.substring(5, 7).toInt() - 1]} ${date.substring(8).toInt()}"

/** Check-in state codes shown in the day dots. */
private const val NONE = 0
private const val DONE = 1
private const val PARTIAL = 2
private const val SKIPPED = 3
private const val OUTSIDE = -1

internal fun Habit.withIteration(value: Long): Habit = copy(iteration = romanNumeral(value.toInt()))

internal fun romanNumeral(n: Int): String =
    listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X").getOrNull(n - 1) ?: n.toString()

/** Weekly targets do not identify specific weekdays; only intervals have definite rest days. */
internal fun isIntervalRestDay(
    recurrence: String,
    date: String,
    completedDays: Set<String>,
): Boolean {
    if (!recurrence.startsWith("Every ")) return false
    val interval =
        Regex("\\d+")
            .find(recurrence)
            ?.value
            ?.toIntOrNull()
            ?.coerceIn(1, 365) ?: return false
    return (1 until interval).any { addIsoDays(date, -it) in completedDays }
}

/** Habits of the latest run, in run order; falls back to the sample habits before any run exists. */
internal fun loadRunHabits(
    database: HabitDatabase,
    runUid: String?,
): List<Habit> =
    runUid?.let { uid ->
        database.schemaQueries.selectHabitsForRun(uid).executeAsList().map { row ->
            val recur =
                if (row.recurrence_type ==
                    "interval"
                ) {
                    "Every ${row.recurrence_value} days"
                } else {
                    "${row.recurrence_value} days per week"
                }
            Habit(
                row.title,
                row.group_name,
                row.impact_points.toInt(),
                recur,
                romanNumeral(row.iteration.toInt()),
                row.uid,
                database.habitIcon(row.uid),
                database.habitColor(row.uid),
            )
        }
    } ?: emptyList()

@Composable internal fun RunsScreen(
    database: HabitDatabase,
    revision: Int,
    today: String,
    dense: Boolean,
    groupHabits: Boolean,
    historyDays: Int,
    onManageRuns: () -> Unit,
    onOpenHabit: (Habit) -> Unit,
    onLocalChange: () -> Unit,
) {
    val latestRun = remember(database, revision) { database.currentRun() }
    if (latestRun == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No runs yet. Tap + to create one.", color = muted)
        }
        return
    }
    val habits = remember(database, revision, latestRun) { loadRunHabits(database, latestRun?.uid) }
    val runName = latestRun?.name ?: "21-day habit run"
    val runDays = latestRun?.duration_days?.toInt() ?: 21
    val runStart = latestRun?.start_date ?: today
    val elapsed = (0 until runDays).count { addIsoDays(runStart, it) <= today }.coerceIn(1, runDays)
    val todayOffset = (0 until runDays).firstOrNull { addIsoDays(runStart, it) == today } ?: if (today < runStart) 0 else runDays - 1

    // date -> (habit uid -> recorded state), one query per run day.
    val records =
        remember(database, revision, latestRun) {
            (0 until runDays).associate { offset ->
                val date = addIsoDays(runStart, offset)
                date to
                    database.schemaQueries
                        .selectCheckInsForDay(date)
                        .executeAsList()
                        .associate { it.habit_uid to it.state }
            }
        }
    val pointsById = remember(habits) { habits.associate { it.uid() to it.points } }
    val pointsPerRunDay =
        remember(records, pointsById) {
            (0 until runDays).map { offset ->
                records[addIsoDays(runStart, offset)].orEmpty().entries.filter { it.value == "done" }.sumOf {
                    pointsById[it.key]
                        ?: 0
                }
            }
        }
    val earnedPoints = pointsPerRunDay.sum()

    var selectedOffset by remember(latestRun?.uid) { mutableIntStateOf(todayOffset) }
    val week = selectedOffset / 7
    val selectedDay = selectedOffset % 7
    var openRow by remember { mutableStateOf<String?>(null) }
    var restDaysExpanded by remember(latestRun?.uid) { mutableStateOf(false) }
    val runScope = rememberCoroutineScope()

    val completedDays =
        remember(database, revision, habits) {
            habits.associate { habit ->
                habit.uid() to
                    database.schemaQueries
                        .selectCheckInsForHabit(habit.uid())
                        .executeAsList()
                        .filter { it.state == "done" }
                        .map { it.day }
                        .toSet()
            }
        }
    val selectedDate = addIsoDays(runStart, selectedOffset)
    val (notDue, due) =
        habits.partition { habit ->
            records[selectedDate]?.containsKey(habit.uid()) != true &&
                isIntervalRestDay(habit.recurrence, selectedDate, completedDays[habit.uid()].orEmpty())
        }

    val selectedEligible =
        habits.filter { habit ->
            !isIntervalRestDay(habit.recurrence, today, completedDays[habit.uid()].orEmpty())
        }
    val selectedTotalPoints = selectedEligible.sumOf { it.points }
    val selectedEarnedPoints = selectedEligible.filter { records[today]?.get(it.uid()) == "done" }.sumOf { it.points }
    val selectedImpactPercent = if (selectedTotalPoints == 0) 0 else (selectedEarnedPoints * 100 / selectedTotalPoints).coerceIn(0, 100)

    fun stateFor(
        habit: Habit,
        offset: Int,
    ): Int {
        if (offset >= runDays) return OUTSIDE
        val date = addIsoDays(runStart, offset)
        return when (records[date]?.get(habit.uid())) {
            "done" -> DONE
            "partial" -> PARTIAL
            "skipped" -> SKIPPED
            else -> if (date < today) SKIPPED else NONE
        }
    }

    fun commit(
        habit: Habit,
        state: Int,
    ) {
        if (latestRun.status != "active") return
        val offset = selectedOffset
        if (offset >= runDays) return
        val date = addIsoDays(runStart, offset)
        val dbState =
            when (state) {
                DONE -> "done"
                PARTIAL -> "partial"
                else -> "skipped"
            }
        if (records[date]?.containsKey(habit.uid()) == true) {
            database.schemaQueries.updateCheckIn(dbState, null, habit.uid(), date)
        } else {
            database.schemaQueries.insertCheckIn("${habit.uid()}:$date", habit.uid(), date, dbState, null)
        }
        onLocalChange()
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            RunSummaryCard(
                runName,
                runDays,
                habits.size,
                earnedPoints,
                earnedPoints.toFloat() / elapsed,
                elapsed,
                latestRun.status,
                onManageRuns,
            )
        }
        item {
            DateStrip(
                runStart = runStart,
                runDays = runDays,
                todayOffset = todayOffset,
                selectedOffset = selectedOffset,
                impactPercent = selectedImpactPercent,
                completedOffsets = pointsPerRunDay.mapIndexed { i, p -> i to (p > 0) }.toMap(),
                onToday = {
                    selectedOffset = todayOffset
                    openRow = null
                },
                onDate = {
                    selectedOffset = it
                    openRow = null
                },
            )
        }
        if (latestRun.status !=
            "active"
        ) {
            item {
                Text(
                    "This run is stopped. Its existing history remains available.",
                    color = muted,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (habits.isEmpty()) {
            item { Text("This run has no habits.", color = muted, style = MaterialTheme.typography.bodyMedium) }
        }
        if (habits.isNotEmpty()) {
            item(key = "habits-card") {
                Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
                    Column(Modifier.fillMaxWidth().padding(8.dp)) {
                        due.groupBy { if (groupHabits) it.group else "" }.forEach { (group, inGroup) ->
                            if (groupHabits) {
                                Text(
                                    "${group.uppercase()} · ${inGroup.size}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = green,
                                    modifier = Modifier.padding(top = 8.dp, start = 8.dp),
                                )
                            }
                            inGroup.forEach { habit ->
                                val dots = (0 until if (dense) 7 else historyDays).map { stateFor(habit, week * 7 + it) }
                                SwipeHabitRow(
                                    habit = habit,
                                    dots = dots,
                                    selectedDay = selectedDay,
                                    dense = dense,
                                    openRow = openRow,
                                    onOpenRow = { openRow = it },
                                    onCommit = { commit(habit, it) },
                                    onOpen = { onOpenHabit(habit) },
                                    scope = runScope,
                                )
                            }
                        }
                        if (notDue.isNotEmpty()) {
                            TextButton(onClick = { restDaysExpanded = !restDaysExpanded }, modifier = Modifier.fillMaxWidth()) {
                                Text("${if (restDaysExpanded) "▾" else "▸"} Not due on this day · ${notDue.size}")
                            }
                            if (restDaysExpanded) {
                                notDue.forEach { habit ->
                                    val dots = (0 until if (dense) 7 else historyDays).map { stateFor(habit, week * 7 + it) }
                                    SwipeHabitRow(
                                        habit = habit,
                                        dots = dots,
                                        selectedDay = selectedDay,
                                        dense = dense,
                                        openRow = openRow,
                                        onOpenRow = { openRow = it },
                                        onCommit = { commit(habit, it) },
                                        onOpen = { onOpenHabit(habit) },
                                        scope = runScope,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        if (!hidePointsPref) item { PointsPerDayCard(pointsPerRunDay, habits.sumOf { it.points }) }
        if (!hidePointsPref) item { PointsBurnupCard(pointsPerRunDay, elapsed, habits.sumOf { it.points }) }
    }
}

@Composable internal fun RunManagementPage(
    database: HabitDatabase,
    revision: Int,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onChanged: () -> Unit,
) {
    val runs = remember(database, revision) { database.schemaQueries.selectRuns().executeAsList() }
    val current = remember(database, revision) { database.currentRun()?.uid }
    val orderedRuns = listOfNotNull(runs.firstOrNull { it.uid == current }) + runs.filter { it.uid != current }
    var showPastRuns by remember { mutableStateOf(false) }
    val visibleRuns = if (showPastRuns) orderedRuns else orderedRuns.take(1)
    var pending by remember { mutableStateOf<Triple<String, String, String>?>(null) }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Back") }
            Text("Manage runs", style = MaterialTheme.typography.titleLarge)
        }
        if (runs.size > 1) {
            TextButton(onClick = { showPastRuns = !showPastRuns }) {
                Text(if (showPastRuns) "Hide past runs" else "Show past runs (${runs.size - 1})")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleRuns, key = { it.uid }) { run ->
                Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(12.dp)) {
                        Text(run.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${run.start_date} · ${run.status}",
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (run.uid == current) {
                                TextButton(onClick = onEdit) { Text("Edit") }
                            }
                            if (run.status ==
                                "active"
                            ) {
                                TextButton(onClick = { pending = Triple("stop", run.uid, run.name) }) { Text("Stop") }
                            } else if (run.status == "stopped") {
                                TextButton(onClick = { pending = Triple("activate", run.uid, run.name) }) { Text("Make active") }
                            }
                            TextButton(onClick = { pending = Triple("delete", run.uid, run.name) }) { Text("Delete") }
                        }
                    }
                }
            }
        }
    }
    pending?.let { (action, uid, name) ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                Text(
                    when (action) {
                        "stop" -> "Stop run?"
                        "activate" -> "Make this run active?"
                        else -> "Delete run?"
                    },
                )
            },
            text = {
                Text(
                    if (action == "activate") {
                        "Make $name active? The current active run will be stopped. Habits and check-ins will be kept."
                    } else {
                        "${if (action == "stop") "Stop" else "Delete"} $name? Its habits and check-ins will be kept."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (action == "activate") {
                        val activated = database.activateExistingRun(uid)
                        pending = null
                        if (activated) {
                            onChanged()
                            onBack()
                        }
                    } else {
                        if (action == "stop") {
                            database.schemaQueries.stopRun(uid)
                        } else {
                            database.deleteRunKeepingHabits(uid)
                        }
                        pending = null
                        onChanged()
                    }
                }) {
                    Text(
                        when (action) {
                            "stop" -> "Stop"
                            "activate" -> "Make active"
                            else -> "Delete"
                        },
                    )
                }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Cancel") } },
        )
    }
}

@Composable private fun RunSummaryCard(
    runName: String,
    days: Int,
    habitCount: Int,
    earned: Int,
    average: Float,
    elapsed: Int,
    status: String,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = palette.accentSoft2),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (status ==
                            "active"
                        ) {
                            "ACTIVE RUN"
                        } else {
                            "STOPPED RUN"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = green,
                    )
                    Text(runName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
                    Text("Day $elapsed of $days · $habitCount habits", style = MaterialTheme.typography.labelSmall, color = muted)
                }
                if (!hidePointsPref) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text("$earned pts", style = MaterialTheme.typography.titleMedium, color = green)
                        Text("${"%.1f".format(average)} avg / day", style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
            }
            LinearProgressIndicator(progress = {
                (elapsed.toFloat() / days.coerceAtLeast(1)).coerceIn(0f, 1f)
            }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = green, trackColor = palette.track)
        }
    }
}

/** Compact swipeable date rail with selected-day impact progress. */
@Composable private fun DateStrip(
    runStart: String,
    runDays: Int,
    todayOffset: Int,
    selectedOffset: Int,
    impactPercent: Int,
    completedOffsets: Map<Int, Boolean>,
    onToday: () -> Unit,
    onDate: (Int) -> Unit,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = (selectedOffset - 3).coerceAtLeast(0))
    val scrollScope = rememberCoroutineScope()
    val selectedDate = addIsoDays(runStart, selectedOffset)
    val monthYear = "${MONTHS[selectedDate.substring(5, 7).toInt() - 1]} ${selectedDate.substring(0, 4)}"
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(monthYear, modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = {
                onToday()
                scrollScope.launch { listState.animateScrollToItem((todayOffset - 3).coerceAtLeast(0)) }
            }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) {
                Text("Today", style = MaterialTheme.typography.labelSmall)
            }
        }
        LazyRow(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            items(runDays) { offset ->
                val date = addIsoDays(runStart, offset)
                val selected = offset == selectedOffset
                Column(
                    Modifier
                        .width(38.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { onDate(offset) }
                        .padding(vertical = 1.dp)
                        .semantics { contentDescription = "${shortDate(date)}${if (offset == todayOffset) ", today" else ""}" },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Text(WEEKDAY_LETTERS[isoDayOfWeek(date)].toString(), style = MaterialTheme.typography.labelSmall, color = muted)
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .then(if (selected) Modifier.background(green) else Modifier)
                            .then(if (offset == todayOffset && !selected) Modifier.border(1.dp, green, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            date.substring(8).toInt().toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) palette.onAccent else ink,
                        )
                    }
                    Box(
                        Modifier.padding(top = 1.dp).size(4.dp).clip(CircleShape).background(
                            if (completedOffsets[offset] ==
                                true
                            ) {
                                green
                            } else {
                                Color.Transparent
                            },
                        ),
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp, top = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("TODAY", style = MaterialTheme.typography.labelSmall, color = muted)
            Spacer(Modifier.width(8.dp))
            LinearProgressIndicator(
                progress = { impactPercent / 100f },
                modifier = Modifier.weight(1f).height(3.dp).clip(CircleShape),
                color = green,
                trackColor = palette.track,
            )
            Text(" $impactPercent%", style = MaterialTheme.typography.labelSmall, color = green)
        }
    }
}

@Composable private fun StepButton(
    glyph: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(
                48.dp,
            ).clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = MaterialTheme.typography.headlineSmall, color = if (enabled) ink else muted.copy(alpha = .35f)) }
}

/** Seven circular dots, one per day of the visible week. Display only. */
@Composable internal fun DayDots(
    states: List<Int>,
    selectedDay: Int,
    size: androidx.compose.ui.unit.Dp,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (size < 12.dp) 4.dp else 5.dp), verticalAlignment = Alignment.CenterVertically) {
        states.forEachIndexed { day, state ->
            val fill =
                when (state) {
                    DONE -> green
                    PARTIAL -> palette.partial
                    SKIPPED -> palette.skipped
                    OUTSIDE -> palette.emptyDay.copy(alpha = .5f)
                    else -> Color.Transparent
                }
            Box(
                Modifier
                    .size(size)
                    .clip(CircleShape)
                    .background(fill)
                    .then(if (state == NONE) Modifier.border(1.dp, muted, CircleShape) else Modifier)
                    .then(if (selectedDay == day && state != OUTSIDE) Modifier.border(2.dp, ink, CircleShape) else Modifier),
            )
        }
    }
}

private val ROW_SHAPE = RoundedCornerShape(16.dp)

@Composable private fun SwipeHabitRow(
    habit: Habit,
    dots: List<Int>,
    selectedDay: Int,
    dense: Boolean,
    openRow: String?,
    onOpenRow: (String?) -> Unit,
    onCommit: (Int) -> Unit,
    onOpen: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
) {
    val density = LocalDensity.current
    val buttonPx = with(density) { 76.dp.toPx() }
    val rightPanelPx = buttonPx
    val leftPanelPx = buttonPx * 2
    val flingPx = with(density) { 700.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    val offset = remember(habit.uid()) { Animatable(0f) }
    var rowWidthPx by remember { mutableFloatStateOf(0f) }
    val commitLatest by rememberUpdatedState(onCommit)
    val trayOpen by remember(offset) { derivedStateOf { abs(offset.value) >= rightPanelPx - 1f } }
    val committing by remember(offset) { derivedStateOf { rowWidthPx > 0f && abs(offset.value) >= rowWidthPx * REVEAL_COMMIT_FRACTION } }
    LaunchedEffect(committing) { if (committing) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    LaunchedEffect(openRow) {
        if (openRow != habit.uid() &&
            offset.value != 0f
        ) {
            offset.animateTo(0f, spring(dampingRatio = .85f, stiffness = 500f))
        }
    }

    fun settle(
        target: Float,
        velocity: Float,
    ) {
        scope.launch { offset.animateTo(target, spring(dampingRatio = .82f, stiffness = 520f), initialVelocity = velocity) }
    }

    fun stateOf(action: QuickSwipeAction) =
        when (action) {
            QuickSwipeAction.Done -> DONE
            QuickSwipeAction.Partial -> PARTIAL
            else -> SKIPPED
        }

    Box(Modifier.fillMaxWidth().clipToBounds().onSizeChanged { rowWidthPx = it.width.toFloat() }) {
        SwipeRevealLayer(offset, rowWidthPx, buttonPx, trayOpen, committing) { action ->
            commitLatest(stateOf(action))
            settle(0f, 0f)
        }
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    translationX = offset.value
                    shape = ROW_SHAPE
                    clip = true
                    shadowElevation = (abs(offset.value) / 24f).coerceIn(0f, 1f) * 10.dp.toPx()
                }.background(surface)
                .clickable { if (trayOpen) settle(0f, 0f) else onOpen() }
                .semantics {
                    contentDescription = "${habit.name}, open details"
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Mark done") {
                                commitLatest(DONE)
                                true
                            },
                            CustomAccessibilityAction("Mark partial") {
                                commitLatest(PARTIAL)
                                true
                            },
                            CustomAccessibilityAction("Mark skipped") {
                                commitLatest(SKIPPED)
                                true
                            },
                        )
                }.horizontalSwipe(
                    key = offset,
                    onStart = { onOpenRow(habit.uid()) },
                    onDrag = { dx, _ ->
                        val limit = if (rowWidthPx > 0f) rowWidthPx else Float.MAX_VALUE
                        val next = (offset.value + dx).coerceIn(-limit, limit)
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(next) }
                    },
                    onRelease = { velocity ->
                        val panel = if (offset.value < 0f) leftPanelPx else rightPanelPx
                        when (revealSettle(offset.value, velocity, panel, rowWidthPx, flingPx)) {
                            RevealSettle.Closed -> settle(0f, velocity)
                            RevealSettle.OpenRight -> settle(rightPanelPx, velocity)
                            RevealSettle.OpenLeft -> settle(-leftPanelPx, velocity)
                            RevealSettle.CommitRight -> {
                                commitLatest(DONE)
                                settle(0f, velocity)
                            }
                            RevealSettle.CommitLeft -> {
                                commitLatest(SKIPPED)
                                settle(0f, velocity)
                            }
                        }
                    },
                ).heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = if (dense) 8.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!dense) {
                val tint = habitColorOf(habit.color) ?: green
                Box(
                    Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .2f)),
                    contentAlignment = Alignment.Center,
                ) {
                    HabitGlyph(habit, Modifier.size(22.dp), tint.copy(alpha = .2f), tint)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    habit.name,
                    fontWeight = FontWeight.Medium,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = if (dense) 1 else 2,
                )
                if (!dense) {
                    val sub =
                        buildString {
                            if (!hidePointsPref) append("${habit.points} pts · ")
                            append(habit.recurrence)
                        }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                    Column(Modifier.padding(top = 5.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        dots.chunked(7).forEachIndexed { rowIndex, row ->
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                row.forEachIndexed { columnIndex, state ->
                                    val index = rowIndex * 7 + columnIndex
                                    val fill =
                                        when (state) {
                                            DONE -> green
                                            PARTIAL -> palette.partial
                                            SKIPPED -> palette.skipped
                                            else -> Color.Transparent
                                        }
                                    Box(
                                        Modifier
                                            .size(15.dp)
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(fill)
                                            .border(
                                                if (index ==
                                                    selectedDay
                                                ) {
                                                    2.dp
                                                } else {
                                                    1.dp
                                                },
                                                if (index == selectedDay) ink else muted,
                                                RoundedCornerShape(3.dp),
                                            ),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            if (dense) DayDots(dots, selectedDay, 10.dp)
        }
    }
}

/** Colored area uncovered behind a swiped card. Its buttons hang off the card's moving edge. */
@Composable private fun BoxScope.SwipeRevealLayer(
    offset: Animatable<Float, AnimationVector1D>,
    rowWidthPx: Float,
    buttonPx: Float,
    panelEnabled: Boolean,
    committing: Boolean,
    onAction: (QuickSwipeAction) -> Unit,
) {
    val side by remember(offset) { derivedStateOf { sign(offset.value).toInt() } }
    if (side == 0) return
    val actions = if (side > 0) listOf(QuickSwipeAction.Done) else listOf(QuickSwipeAction.Partial, QuickSwipeAction.Skipped)
    val outer = if (side > 0) QuickSwipeAction.Done else QuickSwipeAction.Skipped
    val fill = if (committing) lerp(swipeActionContainer(outer), swipeActionContent(outer), .45f) else swipeActionContainer(outer)
    val total = buttonPx * actions.size
    Box(Modifier.matchParentSize().clip(ROW_SHAPE).background(fill)) {
        Row(
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .offset { IntOffset(if (side > 0) (offset.value - total).roundToInt() else (rowWidthPx + offset.value).roundToInt(), 0) },
        ) {
            actions.forEach { action ->
                Column(
                    Modifier
                        .width(with(LocalDensity.current) { buttonPx.toDp() })
                        .fillMaxHeight()
                        .background(if (action == outer) Color.Transparent else swipeActionContainer(action))
                        .clickable(enabled = panelEnabled) { onAction(action) }
                        .semantics { contentDescription = swipeActionLabel(action) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(swipeActionGlyph(action), color = swipeActionContent(action), style = MaterialTheme.typography.titleLarge)
                    Text(
                        swipeActionLabel(action),
                        color = swipeActionContent(action),
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable private fun PointsPerDayCard(
    perDay: List<Int>,
    maxDailyPoints: Int,
) {
    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Points per day", style = MaterialTheme.typography.titleSmall)
            val values = perDay
            val top = max(maxDailyPoints, 1)
            Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                Canvas(Modifier.width(max(300, perDay.size * 24).dp).height(112.dp).padding(top = 12.dp)) {
                    val slot = size.width / perDay.size.coerceAtLeast(1)
                    val barWidth = slot * .46f
                    for (line in 1..3) {
                        val y = size.height * line / 4f
                        drawLine(palette.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                    }
                    values.forEachIndexed { i, value ->
                        val height = (size.height - 8.dp.toPx()) * value / top
                        if (value >
                            0
                        ) {
                            drawRoundRect(
                                green,
                                Offset(i * slot + (slot - barWidth) / 2, size.height - height),
                                androidx.compose.ui.geometry
                                    .Size(barWidth, height),
                                androidx.compose.ui.geometry
                                    .CornerRadius(5.dp.toPx()),
                            )
                        }
                    }
                }
                Row(Modifier.width(max(300, perDay.size * 24).dp)) {
                    perDay.indices.forEach { i ->
                        Text(
                            "${i + 1}",
                            style = MaterialTheme.typography.labelSmall,
                            color = muted,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun PointsBurnupCard(
    perDay: List<Int>,
    elapsed: Int,
    maxDailyPoints: Int,
) {
    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Points burnup", style = MaterialTheme.typography.titleSmall)
            Text(
                "Cumulative points across the run",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = 3.dp),
            )
            val cumulative = perDay.take(elapsed).runningFold(0) { acc, v -> acc + v }
            val peak = max(maxDailyPoints * perDay.size, 1)
            Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 12.dp)) {
                val left = 8.dp.toPx()
                val right = size.width - 6.dp.toPx()
                val top = 5.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                for (line in 1..3) {
                    val y = top + (bottom - top) * line / 4f
                    drawLine(palette.divider, Offset(left, y), Offset(right, y), 1.dp.toPx())
                }
                val n = max(perDay.size, 2)
                val path = Path()
                cumulative.forEachIndexed { i, value ->
                    val x = left + (right - left) * i / (n).toFloat()
                    val y = bottom - (bottom - top) * value / peak
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path,
                    green,
                    style =
                        Stroke(
                            width = 3.dp.toPx(),
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                )
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Day 1", style = MaterialTheme.typography.labelSmall, color = muted)
                Text("${cumulative.last()} pts so far", style = MaterialTheme.typography.labelSmall, color = green)
                Text("Day ${perDay.size}", style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}
