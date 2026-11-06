package app.habitlab.ui

import app.habitlab.db.HabitDatabase

/** Device-local UI preferences, kept in sync_meta; these keys are never synced. */
internal const val PREF_DENSE_LAYOUT = "ui_dense_layout"
private const val ICON_PREFIX = "habit_icon:"

internal fun HabitDatabase.pref(key: String): String? = schemaQueries.selectMetaValue(key).executeAsOneOrNull()
internal fun HabitDatabase.setPref(key: String, value: String) = schemaQueries.upsertMetaValue(key, value)
internal fun HabitDatabase.habitIcon(habitUid: String): String = pref(ICON_PREFIX + habitUid).orEmpty()
internal fun HabitDatabase.setHabitIcon(habitUid: String, icon: String) {
    if (icon.isNotBlank()) setPref(ICON_PREFIX + habitUid, icon)
}
