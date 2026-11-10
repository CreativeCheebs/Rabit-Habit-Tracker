package app.habitlab.ui

import app.habitlab.db.HabitDatabase

/** Device-local UI preferences, kept in sync_meta; these keys are never synced. */
internal const val PREF_DENSE_LAYOUT = "ui_dense_layout"
internal const val PREF_THEME_MODE = "ui_theme_mode"
internal const val PREF_ACCENT = "ui_accent"
internal const val PREF_HIDE_POINTS = "ui_hide_points"
internal const val PREF_CURRENT_RUN = "ui_current_run"
private const val ICON_PREFIX = "habit_icon:"
private const val SHARE_PREFIX = "habit_share:"
internal const val PREF_SHARE_HABITS = "ui_share_habit_completion"
internal const val PREF_SHARE_TODAY = "ui_share_today_completion"
internal const val PREF_SHARE_RUN = "ui_share_run_completion"

internal fun HabitDatabase.pref(key: String): String? = schemaQueries.selectMetaValue(key).executeAsOneOrNull()

internal fun HabitDatabase.setPref(
    key: String,
    value: String,
) = schemaQueries.upsertMetaValue(key, value)

internal fun HabitDatabase.isHabitShareable(habitUid: String): Boolean = pref(SHARE_PREFIX + habitUid) == "1"

internal fun HabitDatabase.setHabitShareable(
    habitUid: String,
    shareable: Boolean,
) = setPref(SHARE_PREFIX + habitUid, if (shareable) "1" else "0")

internal fun HabitDatabase.currentRun(): app.habitlab.db.Run? {
    val runs = schemaQueries.selectRuns().executeAsList()
    return runs.firstOrNull { it.uid == pref(PREF_CURRENT_RUN) } ?: runs.firstOrNull { it.status == "active" } ?: runs.firstOrNull()
}

internal fun HabitDatabase.activeRuns() = schemaQueries.selectRuns().executeAsList().filter { it.status == "active" }

/** Delete only the run and its links; habits and dated check-ins may be shared. */
internal fun HabitDatabase.deleteRunKeepingHabits(runUid: String) {
    transaction {
        schemaQueries.selectRunLinks(runUid).executeAsList().forEach { schemaQueries.deleteRunHabitRow(it.uid) }
        schemaQueries.deleteRunRow(runUid)
        if (pref(PREF_CURRENT_RUN) == runUid) setPref(PREF_CURRENT_RUN, "")
    }
}

internal fun duplicateHabitTitle(names: List<String>): String? {
    val seen = mutableSetOf<String>()
    for (name in names) {
        val normalized = name.trim().replace(Regex("\\s+"), " ").lowercase()
        if (normalized.isNotEmpty() && !seen.add(normalized)) return name.trim()
    }
    return null
}

internal fun HabitDatabase.habitIcon(habitUid: String): String = pref(ICON_PREFIX + habitUid).orEmpty()

internal fun HabitDatabase.setHabitIcon(
    habitUid: String,
    icon: String,
) {
    if (icon.isNotBlank()) setPref(ICON_PREFIX + habitUid, icon)
}

private const val COLOR_PREFIX = "habit_color:"

internal fun HabitDatabase.habitColor(habitUid: String): String = pref(COLOR_PREFIX + habitUid).orEmpty()

internal fun HabitDatabase.setHabitColor(
    habitUid: String,
    color: String,
) = setPref(COLOR_PREFIX + habitUid, color)
