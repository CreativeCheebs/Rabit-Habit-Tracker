package app.habitlab.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.habitlab.db.HabitDatabase
import app.habitlab.sync.ensureSyncTriggers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CheckInCycleTest {
    private fun freshDb(): HabitDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HabitDatabase.Schema.create(driver)
        ensureSyncTriggers(driver)
        return HabitDatabase(driver)
    }

    private fun HabitDatabase.stateOf(
        habit: String,
        day: String,
    ): String? =
        schemaQueries
            .selectCheckInsForDay(day)
            .executeAsList()
            .firstOrNull { it.habit_uid == habit }
            ?.state

    @Test fun tapsCycleThroughStatesAndFinallyDeleteTheRow() {
        val db = freshDb()
        db.schemaQueries.insertHabit("h1", "Walk", "General", 1, 2, "weekly", 3)
        val day = "2026-11-20"
        assertEquals("done", db.cycleCheckIn("h1", day))
        assertEquals("done", db.stateOf("h1", day))
        assertEquals("partial", db.cycleCheckIn("h1", day))
        assertEquals("partial", db.stateOf("h1", day))
        assertEquals("skipped", db.cycleCheckIn("h1", day))
        assertEquals("skipped", db.stateOf("h1", day))
        assertNull(db.cycleCheckIn("h1", day))
        assertNull(db.stateOf("h1", day))
        assertEquals("done", db.cycleCheckIn("h1", day))
    }

    @Test fun cyclingUsesTheExistingRowEvenWithASyncedUid() {
        val db = freshDb()
        db.schemaQueries.insertHabit("h1", "Walk", "General", 1, 2, "weekly", 3)
        db.schemaQueries.insertCheckIn("from-server-42", "h1", "2026-11-20", "skipped", "rainy")
        assertNull(db.cycleCheckIn("h1", "2026-11-20"))
        assertEquals(
            0,
            db.schemaQueries
                .selectCheckInsForDay("2026-11-20")
                .executeAsList()
                .size,
        )
    }
}
