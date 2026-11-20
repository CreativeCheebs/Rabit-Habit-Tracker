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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Impact points earned and possible over a date range; resting interval days are not possible points. */
internal data class RunCompletion(
    val earned: Int,
    val possible: Int,
) {
    /** Rounded to the nearest whole percent, matching the browser app's run card. */
    val percent: Int get() = if (possible <= 0) 0 else ((earned * 200L + possible) / (2L * possible)).toInt()
}

/**
 * The single "whole run" formula shared by the Runs card and the Share page:
 * for every day from [fromDate] through [toDate], each habit that is due (or was logged that day)
 * adds its impact points to the possible total, and adds them to the earned total when done.
 */
internal fun runCompletion(
    database: HabitDatabase,
    habits: List<Habit>,
    fromDate: String,
    toDate: String,
): RunCompletion {
    if (habits.isEmpty() || toDate < fromDate) return RunCompletion(0, 0)
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
    var earned = 0
    var possible = 0
    var date = fromDate
    while (date <= toDate) {
        val states =
            database.schemaQueries
                .selectCheckInsForDay(date)
                .executeAsList()
                .associate { it.habit_uid to it.state }
        habits.forEach { habit ->
            val logged = states.containsKey(habit.uid())
            if (logged || !isIntervalRestDay(habit.recurrence, date, doneDays[habit.uid()].orEmpty())) {
                possible += habit.points
                if (states[habit.uid()] == "done") earned += habit.points
            }
        }
        date = addIsoDays(date, 1)
    }
    return RunCompletion(earned, possible)
}

internal fun app.habitlab.db.Habit.toUiHabit(database: HabitDatabase): Habit =
    Habit(
        title,
        group_name,
        impact_points.toInt(),
        if (recurrence_type == "interval") "Every $recurrence_value days" else "$recurrence_value days per week",
        romanNumeral(iteration.toInt()),
        uid,
        database.habitIcon(uid),
        database.habitColor(uid),
    )

/** Habits of the latest run, in run order; falls back to the sample habits before any run exists. */
internal fun loadRunHabits(
    database: HabitDatabase,
    runUid: String?,
): List<Habit> =
    runUid?.let { uid ->
        database.schemaQueries
            .selectHabitsForRun(uid)
            .executeAsList()
            .map { it.toUiHabit(database) }
    } ?: emptyList()

/** Active habits not attached to any run ("standalone"); they are always due, with or without a run. */
internal fun loadStandaloneHabits(database: HabitDatabase): List<Habit> {
    val linked =
        database.schemaQueries
            .selectRuns()
            .executeAsList()
            .flatMap { run ->
                database.schemaQueries
                    .selectRunLinks(run.uid)
                    .executeAsList()
                    .map { it.habit_uid }
            }.toSet()
    return database.schemaQueries
        .selectHabits()
        .executeAsList()
        .filter { it.uid !in linked }
        .map { it.toUiHabit(database) }
}

/** Apply one tap of the check-in cycle for [habitUid] on [date]; returns the new state (null = cleared). */
internal fun HabitDatabase.cycleCheckIn(
    habitUid: String,
    date: String,
): String? {
    val row = schemaQueries.selectCheckInsForDay(date).executeAsList().firstOrNull { it.habit_uid == habitUid }
    val next = nextCheckInState(row?.state)
    when {
        next == null -> if (row != null) schemaQueries.deleteCheckInRow(row.uid)
        row != null -> schemaQueries.updateCheckIn(next, null, habitUid, date)
        else -> schemaQueries.insertCheckIn("$habitUid:$date", habitUid, date, next, null)
    }
    return next
}

private val SCREEN_PADDING = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 28.dp)

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
    onStartNextRun: (() -> Unit)? = null,
    onBuildRun: (Int) -> Unit = {},
) {
    val latestRun = remember(database, revision) { database.currentRun() }
    val standalone = remember(database, revision) { loadStandaloneHabits(database) }
    val runScope = rememberCoroutineScope()
    var openRow by remember { mutableStateOf<String?>(null) }
    if (latestRun == null) {
        LazyColumn(contentPadding = SCREEN_PADDING, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item(key = "empty") { EmptyRunsCard(onBuildRun) }
            item(key = "tip") {
                Banner(palette.surfaceAlt, muted, icon = LinePaths.SPARKLE) {
                    Text(
                        "Tip: start with 3–5 habits. You can add more any time from Edit run.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (standalone.isNotEmpty()) {
                item(key = "standalone") { StandaloneSection(database, standalone, today, dense, onOpenHabit, onLocalChange) }
            }
        }
        return
    }
    val runDays = latestRun.duration_days.toInt()
    val runStart = latestRun.start_date
    val isEnded = remember(latestRun, today) { database.isRunEnded(latestRun, today) }
    val habits = remember(database, revision, latestRun) { loadRunHabits(database, latestRun.uid) }
    val runName = latestRun.name
    val elapsed = if (isEnded) runDays else (0 until runDays).count { addIsoDays(runStart, it) <= today }.coerceIn(1, runDays)
    val todayOffset =
        if (isEnded) {
            runDays - 1
        } else {
            (
                (0 until runDays).firstOrNull { addIsoDays(runStart, it) == today }
                    ?: if (today < runStart) 0 else runDays - 1
            )
        }

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
    val habitIds = remember(habits) { habits.map { it.uid() }.toSet() }
    val daysLogged =
        (0 until runDays).count { offset ->
            records[addIsoDays(runStart, offset)].orEmpty().keys.any { it in habitIds }
        }
    val pointsPerRunDay =
        remember(records, pointsById) {
            (0 until runDays).map { offset ->
                records[addIsoDays(runStart, offset)].orEmpty().entries.filter { it.value == "done" }.sumOf {
                    pointsById[it.key]
                        ?: 0
                }
            }
        }

    var selectedOffset by remember(latestRun?.uid, runStart, runDays) { mutableIntStateOf(todayOffset) }
    val historyStart = historyWindowStart(selectedOffset, if (dense) 7 else historyDays)
    val selectedDay = selectedOffset - historyStart
    var restDaysExpanded by remember(latestRun?.uid) { mutableStateOf(false) }

    val completedDays =
        remember(database, revision, habits) {
            habits.associate { habit ->
                habit.uid() to
                    database.schemaQueries
                        .selectCheckInsForHabit(habit.uid())
                        .executeAsList()
                        .filter { it.state == "done" && it.day >= runStart && it.day <= addIsoDays(runStart, runDays - 1) }
                        .map { it.day }
                        .toSet()
            }
        }
    val runEnd = addIsoDays(runStart, runDays - 1)
    val isUpcoming = today < runStart
    val completionPercent =
        remember(database, revision, habits, today) {
            runCompletion(database, habits, runStart, if (today < runEnd) today else runEnd).percent
        }
    val habitPairs = remember(habits) { habits.map { it.uid() to it.recurrence } }
    val daySummaries =
        remember(records, completedDays, habitPairs) {
            (0 until runDays).map { daySummary(habitPairs, addIsoDays(runStart, it), records, completedDays) }
        }
    val todayInRun = today >= runStart && today <= runEnd
    val todaySummary = if (todayInRun) daySummaries[todayOffset] else DaySummary(0, 0, 0)
    val selectedDate = addIsoDays(runStart, selectedOffset)
    val (notDue, due) =
        habits.partition { habit ->
            records[selectedDate]?.containsKey(habit.uid()) != true &&
                isIntervalRestDay(habit.recurrence, selectedDate, completedDays[habit.uid()].orEmpty())
        }
    val pointsToday = habits.filter { records[today]?.get(it.uid()) == "done" }.sumOf { it.points }
    val canLog = latestRun.status == "active" && !isEnded

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
        if (!canLog) return
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

    fun cycle(habit: Habit) {
        if (!canLog || selectedOffset >= runDays) return
        database.cycleCheckIn(habit.uid(), addIsoDays(runStart, selectedOffset))
        onLocalChange()
    }

    @Composable
    fun HabitRowItem(
        habit: Habit,
        first: Boolean,
        resting: Boolean = false,
    ) {
        val state = records[selectedDate]?.get(habit.uid())
        // A resting interval habit is not missed on past days.
        val visual = checkVisualOf(state, !resting && selectedDate < today)
        val dots = (0 until if (dense) 7 else historyDays).map { stateFor(habit, historyStart + it) }
        if (!first) RowDivider()
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
            enabled = canLog,
            openEnabled = !isEnded,
            visual = visual,
            meta = habitMeta(habit, visual, selectedDate == today),
            earned = if (state == "done" && !hidePointsPref) habit.points else null,
            onCycle = { cycle(habit) },
        )
    }

    LazyColumn(contentPadding = SCREEN_PADDING, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item(key = "run-card") {
            RunHeroCard(
                runName = runName,
                days = runDays,
                habitCount = habits.size,
                today = todaySummary,
                pointsToday = pointsToday,
                completionPercent = completionPercent,
                daysLogged = daysLogged,
                elapsed = elapsed,
                daySummaries = daySummaries,
                pastDays =
                    if (isEnded) {
                        runDays
                    } else if (isUpcoming) {
                        0
                    } else {
                        todayOffset
                    },
                todayIndex = if (todayInRun) todayOffset else -1,
                status = latestRun.status,
                isEnded = isEnded,
                isUpcoming = isUpcoming,
                startDate = runStart,
                onOpenRuns = onManageRuns,
                onStartNextRun = onStartNextRun,
            )
        }
        if (isEnded) {
            item(key = "ended-note") {
                Text(
                    "Run completed · History is read-only",
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        } else {
            item(key = "week") {
                WeekStrip(
                    runStart = runStart,
                    runDays = runDays,
                    today = today,
                    todayOffset = todayOffset,
                    selectedOffset = selectedOffset,
                    daySummaries = daySummaries,
                    onSelect = {
                        selectedOffset = it
                        openRow = null
                    },
                )
            }
        }
        if (latestRun.status != "active" && !isEnded) {
            item(key = "stopped-note") {
                Text(
                    "This run is stopped. Its existing history remains available.",
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (habits.isEmpty()) {
            item(key = "no-habits") { Text("This run has no habits.", color = muted, style = MaterialTheme.typography.bodyMedium) }
        }
        val groups = due.groupBy { if (groupHabits) it.group else "Habits" }
        groups.forEach { (group, inGroup) ->
            item(key = "group-$group") {
                val doneCount = inGroup.count { records[selectedDate]?.get(it.uid()) == "done" }
                GroupSection(group, "$doneCount of ${inGroup.size} done") {
                    inGroup.forEachIndexed { index, habit -> HabitRowItem(habit, index == 0) }
                }
            }
        }
        if (notDue.isNotEmpty()) {
            item(key = "resting") {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RestingRow(
                        text = restingLabel(notDue, selectedDate, selectedDate == today, completedDays),
                        expanded = restDaysExpanded,
                        onToggle = { restDaysExpanded = !restDaysExpanded },
                    )
                    if (restDaysExpanded) {
                        GroupCard { notDue.forEachIndexed { index, habit -> HabitRowItem(habit, index == 0, resting = true) } }
                    }
                }
            }
        }
        if (habits.isNotEmpty() && canLog) {
            item(key = "hint") {
                Text(
                    "Tap a circle: done → partial → skipped. Swipe still works.",
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (standalone.isNotEmpty()) {
            item(key = "standalone-card") { StandaloneSection(database, standalone, today, dense, onOpenHabit, onLocalChange) }
        }
        if (!hidePointsPref) item(key = "points-chart") { PointsPerDayCard(pointsPerRunDay, habits.sumOf { it.points }) }
        if (!hidePointsPref) {
            val chartElapsed = (0 until runDays).count { addIsoDays(runStart, it) <= today }
            item(key = "burnup-chart") { PointsBurnupCard(pointsPerRunDay, chartElapsed, habits.sumOf { it.points }) }
        }
    }
}

/** "3× a week · 2 pts", or "Skipped today" for a skipped row; points text follows the hide-points preference. */
private fun habitMeta(
    habit: Habit,
    visual: CheckVisual,
    isToday: Boolean,
): String =
    if (visual == CheckVisual.Skipped) {
        if (isToday) "Skipped today" else "Skipped"
    } else {
        scheduleLabel(habit.recurrence) + if (hidePointsPref) "" else " · ${habit.points} pts"
    }

/** First day after [date] on which an interval habit is due again, as a weekday name. */
private fun nextDueLabel(
    habit: Habit,
    date: String,
    doneDays: Set<String>,
): String? {
    for (k in 1..30) {
        val next = addIsoDays(date, k)
        if (!isIntervalRestDay(habit.recurrence, next, doneDays)) {
            return if (k ==
                1
            ) {
                "tomorrow"
            } else {
                WEEKDAY_NAMES[isoDayOfWeek(next)].take(3)
            }
        }
    }
    return null
}

private fun restingLabel(
    resting: List<Habit>,
    date: String,
    isToday: Boolean,
    completedDays: Map<String, Set<String>>,
): String {
    val prefix = if (isToday) "Resting today" else "Resting on ${shortDate(date)}"
    if (resting.size > 1) return "$prefix · ${resting.size} habits"
    val habit = resting.first()
    val back = nextDueLabel(habit, date, completedDays[habit.uid()].orEmpty())
    return if (back == null) "$prefix · ${habit.name}" else "$prefix · ${habit.name} is back $back"
}

private val WEEKDAY_NAMES = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")

/** "Thursday, 20 Nov" for the Today header. */
internal fun longDateLabel(date: String): String =
    if (date.length <
        10
    ) {
        date
    } else {
        "${WEEKDAY_NAMES[isoDayOfWeek(date)]}, ${date.substring(8).toInt()} ${MONTHS[date.substring(5, 7).toInt() - 1]}"
    }

@Composable private fun RowDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
}

@Composable private fun GroupCard(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surface)
            .padding(vertical = 4.dp),
    ) { content() }
}

@Composable private fun GroupSection(
    title: String,
    summary: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp).semantics(mergeDescendants = true) {
                heading()
            },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            Text(summary, style = MaterialTheme.typography.bodySmall, color = muted)
        }
        GroupCard(content)
    }
}

@Composable private fun RestingRow(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .dashedBorder(palette.field, 16.dp)
            .clickable(onClickLabel = if (expanded) "Hide resting habits" else "Show resting habits", onClick = onToggle)
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LineIcon(LinePaths.LEAF, muted, size = 16.dp)
        Text(text, color = muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        LineIcon(if (expanded) LinePaths.CHEVRON_DOWN else LinePaths.CHEVRON_RIGHT, muted, size = 16.dp)
    }
}

/** Hero card: today's ring, run headline and per-day bars. Tapping it opens run management. */
@Composable private fun RunHeroCard(
    runName: String,
    days: Int,
    habitCount: Int,
    today: DaySummary,
    pointsToday: Int,
    completionPercent: Int,
    daysLogged: Int,
    elapsed: Int,
    daySummaries: List<DaySummary>,
    pastDays: Int,
    todayIndex: Int,
    status: String,
    isEnded: Boolean,
    isUpcoming: Boolean,
    startDate: String,
    onOpenRuns: () -> Unit,
    onStartNextRun: (() -> Unit)?,
) {
    val progress =
        if (isEnded) {
            completionPercent / 100f
        } else if (today.due == 0) {
            0f
        } else {
            (today.done + today.partial * .5f) / today.due
        }
    val name = runName.uppercase()
    val eyebrow =
        when {
            isEnded -> "$name · COMPLETED"
            isUpcoming -> "$name · STARTS ${shortDate(startDate).uppercase()}"
            status != "active" -> "$name · STOPPED"
            else -> "$name · DAY $elapsed OF $days"
        }
    val headline =
        when {
            isEnded -> "Run complete"
            isUpcoming -> "Starts ${shortDate(startDate)}"
            today.due == 0 -> "Rest day — nothing due"
            today.allDone -> "Perfect day!"
            today.done == 0 -> "Fresh day — let’s go"
            else -> "Nice pace — ${today.due - today.done} to go"
        }
    val meta =
        when {
            isEnded -> "$completionPercent% complete · $daysLogged days logged · $habitCount habits"
            isUpcoming -> "$days days · $habitCount habits"
            else -> (if (hidePointsPref) "" else "+$pointsToday pts today · ") + "$completionPercent% of run so far"
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.hero)
            .clickable(role = Role.Button, onClickLabel = "Manage runs", onClick = onOpenRuns)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgressRing(
                progress = progress,
                track = lerp(palette.hero, palette.accent, .2f),
                color = palette.accent,
                modifier =
                    Modifier.semantics(mergeDescendants = true) {
                        contentDescription =
                            if (isEnded) {
                                "$completionPercent percent of the run complete"
                            } else {
                                "${(progress * 100).roundToInt()} percent of today done"
                            }
                    },
            ) {
                Text(
                    if (isEnded) "$completionPercent%" else "${today.done}/${today.due}",
                    style = MaterialTheme.typography.titleMedium.copy(lineHeight = 18.sp),
                )
                Text(if (isEnded) "run" else "done", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(eyebrow, style = EyebrowStyle, color = palette.accent, maxLines = 2)
                Text(headline, style = MaterialTheme.typography.titleMedium)
                Text(meta, style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
        RunDayBars(daySummaries, pastDays, todayIndex)
        if (isEnded && onStartNextRun != null) {
            PrimaryButton("Start next run", onClick = onStartNextRun, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** One bar per run day: done = accent, partial progress = partial colour, today ringed, future = track. */
@Composable private fun RunDayBars(
    summaries: List<DaySummary>,
    pastDays: Int,
    todayIndex: Int,
) {
    val accent = palette.accent
    val partial = palette.partial
    val track = palette.track
    val kept = summaries.take(pastDays + if (todayIndex >= 0) 1 else 0).count { it.kept }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(12.dp)
            .semantics { contentDescription = "Run days: $kept of ${summaries.size} kept" },
    ) {
        val n = summaries.size.coerceAtLeast(1)
        val gap = if (n > 40) 1.dp.toPx() else 3.dp.toPx()
        val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
        val h = 8.dp.toPx()
        val top = (size.height - h) / 2
        summaries.forEachIndexed { i, s ->
            val counted = i < pastDays || i == todayIndex
            val color =
                when {
                    counted && s.allDone -> accent
                    counted && s.anyProgress -> partial
                    else -> track
                }
            val x = i * (w + gap)
            drawRoundRect(color, Offset(x, top), Size(w, h), CornerRadius(h / 2, h / 2))
            if (i == todayIndex) {
                val r = 1.5.dp.toPx()
                drawRoundRect(
                    accent,
                    Offset(x - r, top - r),
                    Size(w + 2 * r, h + 2 * r),
                    CornerRadius(h / 2 + r, h / 2 + r),
                    style = Stroke(r),
                )
            }
        }
    }
}

/** Seven day buttons for the run week holding the selection, with week navigation and a Today jump. */
@Composable private fun WeekStrip(
    runStart: String,
    runDays: Int,
    today: String,
    todayOffset: Int,
    selectedOffset: Int,
    daySummaries: List<DaySummary>,
    onSelect: (Int) -> Unit,
) {
    val weekStart = historyWindowStart(selectedOffset, 7)
    val weeks = (runDays + 6) / 7
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (weeks > 1 || selectedOffset != todayOffset) {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                if (selectedOffset != todayOffset) {
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(CircleShape)
                            .clickable(role = Role.Button, onClickLabel = "Jump to today") { onSelect(todayOffset) }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Today", style = MaterialTheme.typography.labelLarge, color = palette.accent) }
                }
                if (weeks > 1) {
                    RoundIconButton(LinePaths.CHEVRON_LEFT, "Previous week", onClick = {
                        onSelect((selectedOffset - 7).coerceAtLeast(0))
                    }, enabled = weekStart > 0)
                    RoundIconButton(LinePaths.CHEVRON_RIGHT, "Next week", onClick = {
                        onSelect((selectedOffset + 7).coerceAtMost(runDays - 1))
                    }, enabled = weekStart + 7 < runDays)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (i in 0 until 7) {
                val offset = weekStart + i
                if (offset >= runDays) {
                    Spacer(Modifier.weight(1f))
                    continue
                }
                val date = addIsoDays(runStart, offset)
                val selected = offset == selectedOffset
                val isToday = date == today
                val future = date > today
                val summary = daySummaries[offset]
                val dot =
                    when {
                        future -> Color.Transparent
                        summary.allDone -> palette.accent
                        summary.anyProgress -> palette.partial
                        else -> Color.Transparent
                    }
                val fg =
                    if (selected) {
                        palette.bg
                    } else if (future) {
                        muted
                    } else {
                        ink
                    }
                Column(
                    Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) ink else surface)
                        .then(if (isToday && !selected) Modifier.border(1.5.dp, palette.accent, RoundedCornerShape(12.dp)) else Modifier)
                        .clickable(role = Role.Tab) { onSelect(offset) }
                        .semantics {
                            contentDescription = "${WEEKDAY_NAMES[isoDayOfWeek(date)]} ${shortDate(date)}${if (isToday) ", today" else ""}"
                            stateDescription = if (selected) "Selected" else "Not selected"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Text(
                        WEEKDAY_LETTERS[isoDayOfWeek(date)].toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = fg.copy(alpha = .8f),
                    )
                    Text(
                        date.substring(8).toInt().toString(),
                        style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
                        color = fg,
                    )
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
                }
            }
        }
    }
}

/** Empty state before any run exists: illustration, two start options and "Build a run". */
@Composable private fun EmptyRunsCard(onBuildRun: (Int) -> Unit) {
    var choice by remember { mutableIntStateOf(1) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(
                RoundedCornerShape(28.dp),
            ).background(surface)
            .padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        EmptyIllustration()
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Let's build your first run", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(
                "A run is a short stretch — 7, 14 or 21 days — of habits you want to try. You'll check them off here each day.",
                style = MaterialTheme.typography.bodyMedium,
                color = muted,
                textAlign = TextAlign.Center,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val blue = palette.habitTone("blue")
            val purple = palette.habitTone("purple")
            SourceCard("Start blank", "Pick habits yourself", LinePaths.BLANK, blue.tint, blue.ink, choice == 1, onClick = {
                choice = 1
            }, modifier = Modifier.weight(1f))
            SourceCard("Paste YAML", "Plan it with an AI chat", LinePaths.CODE, purple.tint, purple.ink, choice == 2, onClick = {
                choice = 2
            }, modifier = Modifier.weight(1f))
        }
        PrimaryButton("Build a run", onClick = { onBuildRun(choice) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable private fun EmptyIllustration() {
    val yellow = palette.habitTone("yellow")
    val green = palette.habitTone("green")
    val purple = palette.habitTone("purple")
    Box(Modifier.size(200.dp, 120.dp).clearAndSetSemantics {}) {
        Box(
            Modifier
                .offset(
                    14.dp,
                    26.dp,
                ).size(64.dp)
                .graphicsLayer { rotationZ = -10f }
                .clip(RoundedCornerShape(20.dp))
                .background(yellow.tint),
            contentAlignment = Alignment.Center,
        ) { IconGlyph("weather_sunny", Modifier.size(30.dp), yellow.ink) }
        Box(
            Modifier
                .offset(
                    124.dp,
                    34.dp,
                ).size(64.dp)
                .graphicsLayer { rotationZ = 9f }
                .clip(RoundedCornerShape(20.dp))
                .background(purple.tint),
            contentAlignment = Alignment.Center,
        ) { IconGlyph("book", Modifier.size(30.dp), purple.ink) }
        Box(
            Modifier
                .offset(68.dp, 4.dp)
                .size(72.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(green.solid),
            contentAlignment = Alignment.Center,
        ) { LineIcon(LinePaths.CHECK, green.onSolid, size = 36.dp, strokeWidth = 3f) }
        Box(
            Modifier
                .offset(52.dp, 92.dp)
                .size(14.dp)
                .clip(CircleShape)
                .background(palette.habitTone("orange").solid),
        )
        Box(
            Modifier
                .offset(150.dp, 6.dp)
                .size(10.dp)
                .clip(CircleShape)
                .background(palette.habitTone("indigo").solid),
        )
    }
}

@Composable internal fun RunManagementPage(
    database: HabitDatabase,
    revision: Int,
    onBack: () -> Unit,
    onEdit: (String) -> Unit,
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
            RoundIconButton(LinePaths.BACK, "Back", onClick = onBack)
            Text("All runs", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp).semantics { heading() })
        }
        if (runs.size > 1) {
            TextButton(onClick = { showPastRuns = !showPastRuns }) {
                Text(if (showPastRuns) "Hide past runs" else "Show past runs (${runs.size - 1})")
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visibleRuns, key = { it.uid }) { run ->
                SectionCard(radius = 20.dp, padding = 0.dp) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text(run.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${run.start_date} · ${run.status}",
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            TextButton(onClick = { onEdit(run.uid) }) { Text("Edit") }
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
                    DONE -> palette.completed
                    PARTIAL -> palette.partial
                    SKIPPED -> palette.incomplete
                    OUTSIDE -> palette.emptyDay.copy(alpha = .5f)
                    else -> palette.incomplete
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
    enabled: Boolean = true,
    openEnabled: Boolean = true,
    visual: CheckVisual = CheckVisual.Todo,
    meta: String = "",
    earned: Int? = null,
    onCycle: () -> Unit = {},
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
        if (enabled) {
            SwipeRevealLayer(offset, rowWidthPx, buttonPx, trayOpen, committing) { action ->
                commitLatest(stateOf(action))
                settle(0f, 0f)
            }
        }
        val rowModifier =
            Modifier
                .fillMaxWidth()
                .then(
                    if (enabled) {
                        Modifier.graphicsLayer {
                            translationX = offset.value
                            shape = ROW_SHAPE
                            clip = true
                            shadowElevation = (abs(offset.value) / 24f).coerceIn(0f, 1f) * 10.dp.toPx()
                        }
                    } else {
                        Modifier.clip(ROW_SHAPE)
                    },
                ).background(surface)
                .clickable(enabled = enabled || openEnabled) { if (enabled && trayOpen) settle(0f, 0f) else onOpen() }
                .semantics {
                    contentDescription = if (openEnabled) "${habit.name}, open details" else "${habit.name}, completed run, read-only"
                    if (enabled) {
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
                    }
                }.then(
                    if (enabled) {
                        Modifier.horizontalSwipe(
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
                        )
                    } else {
                        Modifier
                    },
                )
        HabitRowFace(
            habit = habit,
            visual = visual,
            meta = meta,
            earned = earned,
            dense = dense,
            dots = dots,
            selectedDay = selectedDay,
            checkEnabled = enabled,
            onCycle = onCycle,
            modifier = rowModifier,
        )
    }
}

/** Habit row content: icon tile, name + meta (+ history), earned pill and the check circle. */
@Composable private fun HabitRowFace(
    habit: Habit,
    visual: CheckVisual,
    meta: String,
    earned: Int?,
    dense: Boolean,
    dots: List<Int>,
    selectedDay: Int,
    checkEnabled: Boolean,
    onCycle: () -> Unit,
    modifier: Modifier,
) {
    val tone = palette.habitTone(habit.color)
    Row(
        modifier = modifier.heightIn(min = if (dense) 52.dp else 62.dp).padding(start = 10.dp, end = 4.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HabitIconTile(habit, tone, size = if (dense) 34.dp else 42.dp, radius = if (dense) 11.dp else 13.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                habit.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = if (dense) 1 else 2,
                modifier = Modifier.alpha(if (visual == CheckVisual.Skipped) .55f else 1f),
            )
            if (dense) {
                DayDots(dots, selectedDay, 8.dp)
            } else {
                Text(meta, style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                if (dots.isNotEmpty()) HistoryMarks(dots, selectedDay, tone)
            }
        }
        if (earned != null) Pill("+$earned", tone.tint, tone.ink)
        CheckCircle(habit.name, visual, tone, checkEnabled, onCycle)
    }
}

/** Small per-day history squares under a habit (7–21 days, set in Settings). Display only. */
@Composable private fun HistoryMarks(
    dots: List<Int>,
    selectedDay: Int,
    tone: HabitTone,
) {
    Column(Modifier.padding(top = 4.dp).clearAndSetSemantics {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        dots.chunked(7).forEachIndexed { rowIndex, row ->
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                row.forEachIndexed { columnIndex, state ->
                    val index = rowIndex * 7 + columnIndex
                    val fill =
                        when (state) {
                            DONE -> tone.solid
                            PARTIAL -> palette.partial
                            SKIPPED -> palette.track
                            else -> Color.Transparent
                        }
                    val shape = RoundedCornerShape(3.dp)
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(shape)
                            .background(fill)
                            .then(
                                when {
                                    index == selectedDay && state != OUTSIDE -> Modifier.border(1.5.dp, ink, shape)
                                    state == NONE -> Modifier.border(1.dp, palette.field, shape)
                                    else -> Modifier
                                },
                            ),
                    )
                }
            }
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
    SectionCard(radius = 22.dp, padding = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Points per day", style = MaterialTheme.typography.titleSmall)
            val values = perDay
            val top = max(maxDailyPoints, 1)
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val chartWidth = maxOf(maxWidth, (perDay.size * 14).dp)
                Column(Modifier.horizontalScroll(rememberScrollState())) {
                    Canvas(Modifier.width(chartWidth).height(112.dp).padding(top = 12.dp)) {
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
                    Row(Modifier.width(chartWidth)) {
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
}

@Composable private fun PointsBurnupCard(
    perDay: List<Int>,
    elapsed: Int,
    maxDailyPoints: Int,
) {
    SectionCard(radius = 22.dp, padding = 0.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Points burnup", style = MaterialTheme.typography.titleSmall)
            Text(
                "Actual points and forecast at the current daily pace",
                style = MaterialTheme.typography.bodySmall,
                color = muted,
                modifier = Modifier.padding(top = 3.dp),
            )
            val cumulative = perDay.take(elapsed).runningFold(0) { acc, v -> acc + v }
            val forecast = forecastEnd(cumulative.last(), elapsed, perDay.size)
            val peak = max(max(maxDailyPoints * perDay.size, forecast.roundToInt()), 1)
            val forecastColor = MaterialTheme.colorScheme.tertiary
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
                if (elapsed > 0 && elapsed < perDay.size) {
                    val x = left + (right - left) * elapsed / n.toFloat()
                    val y = bottom - (bottom - top) * cumulative.last() / peak
                    val forecastY = bottom - (bottom - top) * forecast / peak
                    drawLine(
                        forecastColor,
                        Offset(x, y),
                        Offset(right, forecastY),
                        3.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())),
                    )
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

/** First day of the history window containing the selection. */
internal fun historyWindowStart(
    selectedOffset: Int,
    windowDays: Int,
): Int = (selectedOffset / windowDays.coerceAtLeast(1)) * windowDays.coerceAtLeast(1)

/** Linear projection from the elapsed daily average; no forecast before the run starts. */
internal fun forecastEnd(
    earned: Int,
    elapsed: Int,
    duration: Int,
): Float = if (elapsed <= 0) 0f else earned.toFloat() * duration / elapsed

/** Habits outside every run, mirroring the browser app's "Standalone habits" section; always logged for today. */
@Composable
private fun StandaloneSection(
    database: HabitDatabase,
    habits: List<Habit>,
    today: String,
    dense: Boolean,
    onOpenHabit: (Habit) -> Unit,
    onLocalChange: () -> Unit,
) {
    if (habits.isEmpty()) return
    val states =
        database.schemaQueries
            .selectCheckInsForDay(today)
            .executeAsList()
            .associate { it.habit_uid to it.state }
    val doneCount = habits.count { states[it.uid()] == "done" }
    GroupSection("Standalone · always due", "$doneCount of ${habits.size} done") {
        habits.forEachIndexed { index, habit ->
            if (index > 0) RowDivider()
            val state = states[habit.uid()]
            val visual = checkVisualOf(state, false)
            HabitRowFace(
                habit = habit,
                visual = visual,
                meta = "${habit.group} · ${habitMeta(habit, visual, true)}",
                earned = if (state == "done" && !hidePointsPref) habit.points else null,
                dense = dense,
                dots = emptyList(),
                selectedDay = -1,
                checkEnabled = true,
                onCycle = {
                    database.cycleCheckIn(habit.uid(), today)
                    onLocalChange()
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = "Open details") { onOpenHabit(habit) }
                        .semantics { contentDescription = "${habit.name}, open details" },
            )
        }
    }
}
