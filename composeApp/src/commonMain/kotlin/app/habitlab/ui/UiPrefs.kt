package app.habitlab.ui

import app.habitlab.db.HabitDatabase

/** Device-local UI preferences, kept in sync_meta; these keys are never synced. */
internal const val PREF_DENSE_LAYOUT = "ui_dense_layout"
internal const val PREF_THEME_MODE = "ui_theme_mode"
internal const val PREF_ACCENT = "ui_accent"
internal const val PREF_HIDE_POINTS = "ui_hide_points"
internal const val PREF_CURRENT_RUN = "ui_current_run"
private const val ICON_PREFIX = "habit_icon:"

internal fun HabitDatabase.pref(key: String): String? = schemaQueries.selectMetaValue(key).executeAsOneOrNull()
internal fun HabitDatabase.setPref(key: String, value: String) = schemaQueries.upsertMetaValue(key, value)
internal fun HabitDatabase.currentRun(): app.habitlab.db.Run? {
    val runs = schemaQueries.selectRuns().executeAsList()
    return runs.firstOrNull { it.uid == pref(PREF_CURRENT_RUN) } ?: runs.firstOrNull()
}
internal fun HabitDatabase.habitIcon(habitUid: String): String = pref(ICON_PREFIX + habitUid).orEmpty()
internal fun HabitDatabase.setHabitIcon(habitUid: String, icon: String) {
    if (icon.isNotBlank()) setPref(ICON_PREFIX + habitUid, icon)
}

private const val COLOR_PREFIX = "habit_color:"
internal fun HabitDatabase.habitColor(habitUid: String): String = pref(COLOR_PREFIX + habitUid).orEmpty()
internal fun HabitDatabase.setHabitColor(habitUid: String, color: String) = setPref(COLOR_PREFIX + habitUid, color)
