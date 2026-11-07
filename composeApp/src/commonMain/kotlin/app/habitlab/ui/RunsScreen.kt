package app.habitlab.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.AlertDialog
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

internal fun romanNumeral(n: Int): String = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X").getOrNull(n - 1) ?: n.toString()

/** Weekly targets do not identify specific weekdays; only intervals have definite rest days. */
internal fun isIntervalRestDay(recurrence: String, date: String, completedDays: Set<String>): Boolean {
    if (!recurrence.startsWith("Every ")) return false
    val interval = Regex("\\d+").find(recurrence)?.value?.toIntOrNull()?.coerceIn(1, 365) ?: return false
    return (1 until interval).any { addIsoDays(date, -it) in completedDays }
}

/** Habits of the latest run, in run order; falls back to the sample habits before any run exists. */
internal fun loadRunHabits(database: HabitDatabase, runUid: String?): List<Habit> =
    runUid?.let { uid ->
        database.schemaQueries.selectHabitsForRun(uid).executeAsList().map { row ->
            val recur = if (row.recurrence_type == "interval") "Every ${row.recurrence_value} days" else "${row.recurrence_value} days per week"
            Habit(row.title, row.group_name, row.impact_points.toInt(), recur, romanNumeral(row.iteration.toInt()), row.uid, database.habitIcon(row.uid), database.habitColor(row.uid))
        }
    } ?: emptyList()

@Composable internal fun RunsScreen(
    database: HabitDatabase,
    revision: Int,
    today: String,
    dense: Boolean,
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
    val maxWeek = (runDays - 1) / 7
    val elapsed = (0 until runDays).count { addIsoDays(runStart, it) <= today }.coerceIn(1, runDays)
    val todayOffset = (0 until runDays).firstOrNull { addIsoDays(runStart, it) == today } ?: if (today < runStart) 0 else runDays - 1

    // date -> (habit uid -> recorded state), one query per run day.
    val records = remember(database, revision, latestRun) {
        (0 until runDays).associate { offset ->
            val date = addIsoDays(runStart, offset)
            date to database.schemaQueries.selectCheckInsForDay(date).executeAsList().associate { it.habit_uid to it.state }
        }
    }
    val pointsById = remember(habits) { habits.associate { it.uid() to it.points } }
    val pointsPerRunDay = remember(records, pointsById) {
        (0 until runDays).map { offset -> records[addIsoDays(runStart, offset)].orEmpty().entries.filter { it.value == "done" }.sumOf { pointsById[it.key] ?: 0 } }
    }
    val earnedPoints = pointsPerRunDay.sum()

    var week by remember(latestRun?.uid) { mutableIntStateOf(todayOffset / 7) }
    var selectedDay by remember(latestRun?.uid) { mutableIntStateOf(todayOffset % 7) }
    var openRow by remember { mutableStateOf<String?>(null) }
    var choosingRun by remember { mutableStateOf(false) }
    val allRuns = remember(database, revision) { database.schemaQueries.selectRuns().executeAsList() }
    val activeRuns = allRuns.filter { it.status == "active" }
    var confirmAction by remember { mutableStateOf("") }
    var restDaysExpanded by remember(latestRun?.uid) { mutableStateOf(false) }
    val runScope = rememberCoroutineScope()

    val completedDays = remember(database, revision, habits) {
        habits.associate { habit ->
            habit.uid() to database.schemaQueries.selectCheckInsForHabit(habit.uid()).executeAsList()
                .filter { it.state == "done" }.map { it.day }.toSet()
        }
    }
    val selectedDate = addIsoDays(runStart, week * 7 + selectedDay)
    val (notDue, due) = habits.partition { habit ->
        records[selectedDate]?.containsKey(habit.uid()) != true &&
            isIntervalRestDay(habit.recurrence, selectedDate, completedDays[habit.uid()].orEmpty())
    }

    fun changeWeek(next: Int) {
        val clamped = next.coerceIn(0, maxWeek)
        if (clamped == week) return
        week = clamped
        selectedDay = if (clamped == todayOffset / 7) todayOffset % 7 else 0
        openRow = null
    }

    fun stateFor(habit: Habit, offset: Int): Int {
        if (offset >= runDays) return OUTSIDE
        val date = addIsoDays(runStart, offset)
        return when (records[date]?.get(habit.uid())) {
            "done" -> DONE
            "partial" -> PARTIAL
            "skipped" -> SKIPPED
            else -> if (date < today) SKIPPED else NONE
        }
    }

    fun commit(habit: Habit, state: Int) {
        if (latestRun.status != "active") return
        val offset = week * 7 + selectedDay
        if (offset >= runDays) return
        val date = addIsoDays(runStart, offset)
        val dbState = when (state) { DONE -> "done"; PARTIAL -> "partial"; else -> "skipped" }
        if (records[date]?.containsKey(habit.uid()) == true) {
            database.schemaQueries.updateCheckIn(dbState, null, habit.uid(), date)
        } else {
            database.schemaQueries.insertCheckIn("${habit.uid()}:$date", habit.uid(), date, dbState, null)
        }
        onLocalChange()
    }

    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (allRuns.size > 1) item {
            Box {
                TextButton(onClick = { choosingRun = true }) { Text("Choose run: $runName ▾") }
                DropdownMenu(expanded = choosingRun, onDismissRequest = { choosingRun = false }) {
                    allRuns.forEach { run ->
                        DropdownMenuItem(text = { Text("${run.name} · ${run.start_date} · ${run.status}") }, onClick = {
                            database.setPref(PREF_CURRENT_RUN, run.uid)
                            choosingRun = false
                            onLocalChange()
                        })
                    }
                }
            }
        }
        if (activeRuns.size > 1) item { Text("Multiple runs are active. Stop each one before starting another.", color = palette.error, style = MaterialTheme.typography.bodySmall) }
        item { RunSummaryCard(runName, runDays, habits.size, earnedPoints, earnedPoints.toFloat() / elapsed, elapsed, latestRun.status) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (latestRun.status == "active") TextButton(onClick = { confirmAction = "stop" }) { Text("Stop run") }
                TextButton(onClick = { confirmAction = "delete" }) { Text("Delete run") }
            }
        }
        item {
            WeekStrip(
                week = week, maxWeek = maxWeek, runStart = runStart, runDays = runDays, today = today, selectedDay = selectedDay,
                onWeek = { changeWeek(it) }, onDay = { selectedDay = it }
            )
        }
        item { Text(if (latestRun.status == "active") "Swipe a habit right for Done, left for Partial or Skipped. Tap it for details." else "This run is stopped. Its existing history remains available.", color = muted, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp)) }
        if (habits.isEmpty()) {
            item { Text("This run has no habits.", color = muted, style = MaterialTheme.typography.bodyMedium) }
        }
        due.groupBy { it.group }.forEach { (group, inGroup) ->
            item(key = "group-$group") {
                Text("${group.uppercase()} · ${inGroup.size}", style = MaterialTheme.typography.labelMedium, color = green, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
            }
            items(inGroup, key = { it.uid() }) { habit ->
                val dots = (0 until 7).map { stateFor(habit, week * 7 + it) }
                SwipeHabitRow(
                    habit = habit, dots = dots, selectedDay = selectedDay, dense = dense,
                    openRow = openRow, onOpenRow = { openRow = it },
                    onCommit = { commit(habit, it) }, onOpen = { onOpenHabit(habit) }, scope = runScope
                )
            }
        }
        if (notDue.isNotEmpty()) {
            item(key = "not-due-header") {
                TextButton(onClick = { restDaysExpanded = !restDaysExpanded }, modifier = Modifier.fillMaxWidth()) {
                    Text("${if (restDaysExpanded) "▾" else "▸"} Not due on this day · ${notDue.size}")
                }
            }
            if (restDaysExpanded) items(notDue, key = { it.uid() }) { habit ->
                val dots = (0 until 7).map { stateFor(habit, week * 7 + it) }
                SwipeHabitRow(
                    habit = habit, dots = dots, selectedDay = selectedDay, dense = dense,
                    openRow = openRow, onOpenRow = { openRow = it },
                    onCommit = { commit(habit, it) }, onOpen = { onOpenHabit(habit) }, scope = runScope
                )
            }
        }
        if (!hidePointsPref) item { PointsPerDayCard(pointsPerRunDay, week, runStart, runDays) }
        if (!hidePointsPref) item { PointsBurnupCard(pointsPerRunDay, elapsed) }
    }
    if (confirmAction.isNotEmpty()) AlertDialog(
        onDismissRequest = { confirmAction = "" },
        title = { Text(if (confirmAction == "stop") "Stop this run?" else "Delete this run?") },
        text = { Text(if (confirmAction == "stop") "Stop ${latestRun.name}? Its habits and check-ins will be kept." else "Delete ${latestRun.name}? Its habits and check-ins will be kept, including those shared with other runs.") },
        confirmButton = { TextButton(onClick = {
            if (confirmAction == "stop") database.schemaQueries.stopRun(latestRun.uid) else database.deleteRunKeepingHabits(latestRun.uid)
            confirmAction = ""
            onLocalChange()
        }) { Text(if (confirmAction == "stop") "Stop run" else "Delete run") } },
        dismissButton = { TextButton(onClick = { confirmAction = "" }) { Text("Cancel") } }
    )
}

@Composable private fun RunSummaryCard(runName: String, days: Int, habitCount: Int, earned: Int, average: Float, elapsed: Int, status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = palette.accentSoft2), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(if (status == "active") "ACTIVE RUN" else "STOPPED RUN", style = MaterialTheme.typography.labelSmall, color = green)
                    Text(runName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
                    Text("Day $elapsed of $days · $habitCount habits", style = MaterialTheme.typography.labelSmall, color = muted)
                }
                if (!hidePointsPref) Column(horizontalAlignment = Alignment.End) {
                    Text("$earned pts", style = MaterialTheme.typography.titleMedium, color = green)
                    Text("${"%.1f".format(average)} avg / day", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
            LinearProgressIndicator(progress = { (elapsed.toFloat() / days.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = green, trackColor = palette.track)
        }
    }
}

/** Week stepper plus a calendar-style row of 7 days. Selecting a day chooses which date swipes log. */
@Composable private fun WeekStrip(
    week: Int, maxWeek: Int, runStart: String, runDays: Int, today: String, selectedDay: Int,
    onWeek: (Int) -> Unit, onDay: (Int) -> Unit,
) {
    val first = addIsoDays(runStart, week * 7)
    val lastOffset = minOf(week * 7 + 6, runDays - 1)
    val last = addIsoDays(runStart, lastOffset)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            StepButton("‹", "Previous week", enabled = week > 0) { onWeek(week - 1) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Week ${week + 1} of ${maxWeek + 1}", style = MaterialTheme.typography.titleSmall)
                Text("${shortDate(first)} – ${shortDate(last)}", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            StepButton("›", "Next week", enabled = week < maxWeek) { onWeek(week + 1) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            (0 until 7).forEach { i ->
                val offset = week * 7 + i
                val inRun = offset < runDays
                val date = addIsoDays(runStart, offset)
                val selected = selectedDay == i && inRun
                Column(
                    Modifier.weight(1f).clickable(enabled = inRun) { onDay(i) }.padding(vertical = 4.dp)
                        .semantics { contentDescription = "Day ${offset + 1}, ${shortDate(date)}" },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(WEEKDAY_LETTERS[isoDayOfWeek(date)].toString(), style = MaterialTheme.typography.labelSmall, color = muted)
                    Box(
                        Modifier.padding(top = 4.dp).size(40.dp).clip(CircleShape)
                            .then(if (selected) Modifier.background(green) else Modifier)
                            .then(if (date == today && !selected) Modifier.border(1.5.dp, green, CircleShape) else Modifier),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            date.substring(8).toInt().toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = when { selected -> palette.onAccent; !inRun -> muted.copy(alpha = .4f); else -> ink }
                        )
                    }
                }
            }
        }
    }
}

@Composable private fun StepButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(enabled = enabled, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) { Text(glyph, style = MaterialTheme.typography.headlineSmall, color = if (enabled) ink else muted.copy(alpha = .35f)) }
}

/** Seven circular dots, one per day of the visible week. Display only. */
@Composable internal fun DayDots(states: List<Int>, selectedDay: Int, size: androidx.compose.ui.unit.Dp) {
    Row(horizontalArrangement = Arrangement.spacedBy(if (size < 12.dp) 4.dp else 5.dp), verticalAlignment = Alignment.CenterVertically) {
        states.forEachIndexed { day, state ->
            val fill = when (state) { DONE -> green; PARTIAL -> palette.partial; SKIPPED -> palette.skipped; OUTSIDE -> palette.emptyDay.copy(alpha = .5f); else -> Color.Transparent }
            Box(
                Modifier.size(size).clip(CircleShape).background(fill)
                    .then(if (state == NONE) Modifier.border(1.dp, muted, CircleShape) else Modifier)
                    .then(if (selectedDay == day && state != OUTSIDE) Modifier.border(2.dp, ink, CircleShape) else Modifier)
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
    LaunchedEffect(openRow) { if (openRow != habit.uid() && offset.value != 0f) offset.animateTo(0f, spring(dampingRatio = .85f, stiffness = 500f)) }

    fun settle(target: Float, velocity: Float) {
        scope.launch { offset.animateTo(target, spring(dampingRatio = .82f, stiffness = 520f), initialVelocity = velocity) }
    }
    fun stateOf(action: QuickSwipeAction) = when (action) { QuickSwipeAction.Done -> DONE; QuickSwipeAction.Partial -> PARTIAL; else -> SKIPPED }

    Box(Modifier.fillMaxWidth().onSizeChanged { rowWidthPx = it.width.toFloat() }) {
        SwipeRevealLayer(offset, rowWidthPx, buttonPx, trayOpen, committing) { action ->
            commitLatest(stateOf(action))
            settle(0f, 0f)
        }
        Row(
            Modifier.fillMaxWidth()
                .graphicsLayer {
                    translationX = offset.value
                    shape = ROW_SHAPE
                    clip = true
                    shadowElevation = (abs(offset.value) / 24f).coerceIn(0f, 1f) * 10.dp.toPx()
                }
                .background(surface)
                .clickable { if (trayOpen) settle(0f, 0f) else onOpen() }
                .semantics {
                    contentDescription = "${habit.name}, open details"
                    customActions = listOf(
                        CustomAccessibilityAction("Mark done") { commitLatest(DONE); true },
                        CustomAccessibilityAction("Mark partial") { commitLatest(PARTIAL); true },
                        CustomAccessibilityAction("Mark skipped") { commitLatest(SKIPPED); true }
                    )
                }
                .horizontalSwipe(
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
                            RevealSettle.CommitRight -> { commitLatest(DONE); settle(0f, velocity) }
                            RevealSettle.CommitLeft -> { commitLatest(SKIPPED); settle(0f, velocity) }
                        }
                    }
                )
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp, vertical = if (dense) 8.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!dense) {
                val tint = habitColorOf(habit.color) ?: green
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(tint.copy(alpha = .2f)), contentAlignment = Alignment.Center) {
                    HabitGlyph(habit, Modifier.size(22.dp), tint.copy(alpha = .2f), tint)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(habit.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, maxLines = if (dense) 1 else 2)
                if (!dense) {
                    val sub = buildString {
                        if (!hidePointsPref) append("${habit.points} pts · ")
                        append(habit.recurrence)
                    }
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                }
            }
            DayDots(dots, selectedDay, if (dense) 10.dp else 12.dp)
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
            Modifier.align(Alignment.CenterStart).fillMaxHeight()
                .offset { IntOffset(if (side > 0) (offset.value - total).roundToInt() else (rowWidthPx + offset.value).roundToInt(), 0) }
        ) {
            actions.forEach { action ->
                Column(
                    Modifier.width(with(LocalDensity.current) { buttonPx.toDp() }).fillMaxHeight()
                        .background(if (action == outer) Color.Transparent else swipeActionContainer(action))
                        .clickable(enabled = panelEnabled) { onAction(action) }
                        .semantics { contentDescription = swipeActionLabel(action) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(swipeActionGlyph(action), color = swipeActionContent(action), style = MaterialTheme.typography.titleLarge)
                    Text(swipeActionLabel(action), color = swipeActionContent(action), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
    }
}


@Composable private fun PointsPerDayCard(perDay: List<Int>, week: Int, runStart: String, runDays: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Points per day", style = MaterialTheme.typography.titleSmall)
            Text("Earned points · week ${week + 1}", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 3.dp))
            val values = (0 until 7).map { i -> perDay.getOrNull(week * 7 + i) }
            val top = max(values.filterNotNull().maxOrNull() ?: 0, 1)
            Canvas(Modifier.fillMaxWidth().height(112.dp).padding(top = 12.dp)) {
                val slot = size.width / 7
                val barWidth = slot * .46f
                for (line in 1..3) {
                    val y = size.height * line / 4f
                    drawLine(palette.divider, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
                }
                values.forEachIndexed { i, value ->
                    if (value == null) return@forEachIndexed
                    val height = (size.height - 8.dp.toPx()) * value / top
                    drawRoundRect(green, Offset(i * slot + (slot - barWidth) / 2, size.height - height), androidx.compose.ui.geometry.Size(barWidth, height.coerceAtLeast(2.dp.toPx())), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth()) {
                (0 until 7).forEach { i ->
                    val inRun = week * 7 + i < runDays
                    Text(
                        if (inRun) WEEKDAY_LETTERS[isoDayOfWeek(addIsoDays(runStart, week * 7 + i))].toString() else "", style = MaterialTheme.typography.labelSmall, color = muted,
                        modifier = Modifier.weight(1f), textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable private fun PointsBurnupCard(perDay: List<Int>, elapsed: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Points burnup", style = MaterialTheme.typography.titleSmall)
            Text("Cumulative points across the run", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 3.dp))
            val cumulative = perDay.take(elapsed).runningFold(0) { acc, v -> acc + v }
            val peak = max(cumulative.last(), 1)
            Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 12.dp)) {
                val left = 8.dp.toPx(); val right = size.width - 6.dp.toPx(); val top = 5.dp.toPx(); val bottom = size.height - 8.dp.toPx()
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
                drawPath(path, green, style = Stroke(width = 3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Day 1", style = MaterialTheme.typography.labelSmall, color = muted)
                Text("${cumulative.last()} pts so far", style = MaterialTheme.typography.labelSmall, color = green)
                Text("Day ${perDay.size}", style = MaterialTheme.typography.labelSmall, color = muted)
            }
        }
    }
}
