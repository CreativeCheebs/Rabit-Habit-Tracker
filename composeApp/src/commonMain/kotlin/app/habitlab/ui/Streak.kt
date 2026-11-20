package app.habitlab.ui

import app.habitlab.db.HabitDatabase

/** Tap-to-cycle check-in: not done → done → partial → skipped → not done. Null means "no check-in row". */
internal fun nextCheckInState(current: String?): String? =
    when (current) {
        null -> "done"
        "done" -> "partial"
        "partial" -> "skipped"
        else -> null
    }

/** How one day went for a set of habits; skipped habits are not counted as due. */
internal data class DaySummary(
    val due: Int,
    val done: Int,
    val partial: Int,
) {
    val isEmpty: Boolean get() = due == 0

    /** Every due habit was done or partly done. */
    val kept: Boolean get() = due > 0 && done + partial == due

    val allDone: Boolean get() = due > 0 && done == due

    val anyProgress: Boolean get() = done + partial > 0
}

/**
 * Due habits for [date] use the app's existing rule: a habit is due unless it is an interval habit resting
 * after a recent completion; a habit logged that day is always counted. Skipped habits are excluded.
 */
internal fun daySummary(
    habits: List<Pair<String, String>>,
    date: String,
    records: Map<String, Map<String, String>>,
    doneDays: Map<String, Set<String>>,
): DaySummary {
    val states = records[date].orEmpty()
    var due = 0
    var done = 0
    var partial = 0
    habits.forEach { (uid, recurrence) ->
        val state = states[uid]
        val resting = state == null && isIntervalRestDay(recurrence, date, doneDays[uid].orEmpty())
        if (resting || state == "skipped") return@forEach
        due++
        if (state == "done") done++
        if (state == "partial") partial++
    }
    return DaySummary(due, done, partial)
}

/** Done dates per habit uid, from date → (habit uid → state). */
internal fun doneDaysOf(records: Map<String, Map<String, String>>): Map<String, Set<String>> {
    val result = HashMap<String, MutableSet<String>>()
    records.forEach { (date, states) ->
        states.forEach { (uid, state) -> if (state == "done") result.getOrPut(uid) { mutableSetOf() }.add(date) }
    }
    return result
}

/**
 * Consecutive kept days ending today, or yesterday while today is still open. Days with nothing due are
 * skipped over: they neither break nor extend the streak. Counting never goes before [firstDay].
 */
internal fun currentStreak(
    habits: List<Pair<String, String>>,
    records: Map<String, Map<String, String>>,
    firstDay: String,
    today: String,
): Int {
    if (habits.isEmpty() || today < firstDay) return 0
    val doneDays = doneDaysOf(records)
    var streak = 0
    var date = today
    if (!daySummary(habits, today, records, doneDays).kept) date = addIsoDays(today, -1)
    while (date >= firstDay) {
        val summary = daySummary(habits, date, records, doneDays)
        if (!summary.isEmpty) {
            if (!summary.kept) break
            streak++
        }
        date = addIsoDays(date, -1)
    }
    return streak
}

/** Streak of the current run's habits, counted from the run start up to today (or the run's last day). */
internal fun currentRunStreak(
    database: HabitDatabase,
    today: String,
): Int {
    val run = database.currentRun(today) ?: return 0
    if (run.start_date.isBlank() || today.isBlank()) return 0
    val habits = database.schemaQueries.selectHabitsForRun(run.uid).executeAsList()
    if (habits.isEmpty()) return 0
    val runEnd = addIsoDays(run.start_date, run.duration_days.toInt() - 1)
    val last = if (today < runEnd) today else runEnd
    val ids = habits.map { it.uid }.toSet()
    val records = HashMap<String, Map<String, String>>()
    habits.forEach { habit ->
        database.schemaQueries.selectCheckInsForHabit(habit.uid).executeAsList().forEach { row ->
            if (row.day >= run.start_date && row.day <= last && row.habit_uid in ids) {
                records[row.day] = records[row.day].orEmpty() + (row.habit_uid to row.state)
            }
        }
    }
    val pairs =
        habits.map {
            it.uid to
                if (it.recurrence_type == "interval") "Every ${it.recurrence_value} days" else "${it.recurrence_value} days per week"
        }
    return currentStreak(pairs, records, run.start_date, last)
}
