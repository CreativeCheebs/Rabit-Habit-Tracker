package app.habitlab.sync

import app.cash.sqldelight.db.SqlDriver

private data class SyncedTable(
    val name: String,
    val columns: List<String>,
)

private val syncedTables =
    listOf(
        SyncedTable(
            "habit",
            listOf("uid", "title", "group_name", "iteration", "impact_points", "recurrence_type", "recurrence_value", "archived"),
        ),
        SyncedTable("run", listOf("uid", "name", "start_date", "duration_days", "status", "copied_from_uid")),
        SyncedTable("run_habit", listOf("uid", "run_uid", "habit_uid")),
        SyncedTable("check_in", listOf("uid", "habit_uid", "day", "state", "comment")),
        SyncedTable("goal", listOf("uid", "description", "start_date", "target_date", "progress")),
        SyncedTable("tracker", listOf("uid", "name", "unit", "cadence")),
        SyncedTable("tracker_entry", listOf("uid", "tracker_uid", "recorded_at", "value", "note")),
    )

private const val GUARD = "(SELECT value FROM sync_meta WHERE key = 'applying') = '0'"
private const val BUMP_SEQ = "UPDATE sync_meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'seq';"
private const val BUMP_CLOCK =
    "UPDATE sync_meta SET value = max(CAST(value AS INTEGER) + 1, " +
        "CAST((julianday('now') - 2440587.5) * 86400000 AS INTEGER)) WHERE key = 'clock';"

private fun recordField(
    table: String,
    key: String,
    field: String,
    condition: String? = null,
): String =
    "INSERT INTO sync_field (table_name, uid, field_name, clock, device, seq)" +
        " SELECT '$table', CAST($key AS TEXT), '$field'," +
        " (SELECT CAST(value AS INTEGER) FROM sync_meta WHERE key = 'clock')," +
        " (SELECT value FROM sync_meta WHERE key = 'device')," +
        " (SELECT CAST(value AS INTEGER) FROM sync_meta WHERE key = 'seq')" +
        (if (condition != null) " WHERE $condition" else "") +
        " ON CONFLICT (table_name, uid, field_name) DO UPDATE" +
        " SET clock = excluded.clock, device = excluded.device, seq = excluded.seq;"

private fun insertTrigger(table: SyncedTable): String =
    """
    CREATE TRIGGER sync_${table.name}_insert AFTER INSERT ON ${table.name}
    WHEN $GUARD
    BEGIN
      $BUMP_SEQ
      $BUMP_CLOCK
      ${recordField(table.name, "NEW.uid", "_row")}
    END;
    """.trimIndent()

private fun updateTrigger(table: SyncedTable): String {
    val perColumn =
        table.columns.filter { it != "uid" }.joinToString("\n      ") { column ->
            recordField(table.name, "NEW.uid", column, "NEW.$column IS NOT OLD.$column")
        }
    return """
        CREATE TRIGGER sync_${table.name}_update AFTER UPDATE ON ${table.name}
        WHEN $GUARD
        BEGIN
          $BUMP_SEQ
          $BUMP_CLOCK
          $perColumn
        END;
        """.trimIndent()
}

private fun deleteTrigger(table: SyncedTable): String =
    """
    CREATE TRIGGER sync_${table.name}_delete AFTER DELETE ON ${table.name}
    WHEN $GUARD
    BEGIN
      $BUMP_SEQ
      $BUMP_CLOCK
      ${recordField(table.name, "OLD.uid", "_deleted")}
    END;
    """.trimIndent()

/**
 * Creates (or recreates) the change-tracking triggers for every synced table.
 * Runs on every database open so new columns are covered automatically; every
 * trigger is a no-op while changes from elsewhere are being applied.
 */
fun ensureSyncTriggers(driver: SqlDriver) {
    driver.execute(null, "INSERT OR IGNORE INTO sync_meta(key, value) VALUES ('applying', '0')", 0)
    for (table in syncedTables) {
        driver.execute(null, "DROP TRIGGER IF EXISTS sync_${table.name}_insert", 0)
        driver.execute(null, "DROP TRIGGER IF EXISTS sync_${table.name}_update", 0)
        driver.execute(null, "DROP TRIGGER IF EXISTS sync_${table.name}_delete", 0)
        driver.execute(null, insertTrigger(table), 0)
        driver.execute(null, updateTrigger(table), 0)
        driver.execute(null, deleteTrigger(table), 0)
    }
}
