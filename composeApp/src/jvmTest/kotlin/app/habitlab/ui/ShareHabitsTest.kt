package app.habitlab.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.habitlab.db.HabitDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShareHabitsTest {
    @Test
    fun shareTextUsesRunDayAndSelectedSections() {
        val snapshot =
            ShareSnapshot(
                12,
                "2026-11-10",
                listOf(ShareHabit("Morning light", "done"), ShareHabit("Plan the day", "partial"), ShareHabit("Focused work block", null)),
                33,
                42,
            )
        assertEquals(
            "# Day 12, 10 November\n\n- ✓ Morning light\n- ◐ Plan the day\n- ✕ Focused work block\n\nToday: 33%\nWhole run: 42%",
            formatShareText(snapshot, true, true, true),
        )
        assertEquals(
            "# Day 12, 10 November\n\n- Morning light\n- Plan the day\n- Focused work block",
            formatShareText(snapshot, false, false, false),
        )
    }

    @Test
    fun shareabilityDefaultsOffAndPercentOnlyCountsEligibleSharedHabits() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HabitDatabase.Schema.create(driver)
        val database = HabitDatabase(driver)
        database.schemaQueries.insertRun("run", "Run", "2026-11-09", 7)
        database.schemaQueries.insertHabit("shared", "Morning light", "Wellbeing", 1, 2, "weekly", 7)
        database.schemaQueries.insertHabit("private", "Journal", "Personal", 1, 4, "weekly", 7)
        database.schemaQueries.attachHabitToRun("run:shared", "run", "shared")
        database.schemaQueries.attachHabitToRun("run:private", "run", "private")
        database.schemaQueries.insertCheckIn("shared:2026-11-09", "shared", "2026-11-09", "done", null)
        database.schemaQueries.insertCheckIn("private:2026-11-10", "private", "2026-11-10", "done", null)
        assertFalse(database.isHabitShareable("shared"))
        database.setHabitShareable("shared", true)
        assertTrue(database.isHabitShareable("shared"))
        val snapshot = buildShareSnapshot(database, "2026-11-10")!!
        assertEquals(2, snapshot.dayNumber)
        assertEquals(listOf("Morning light"), snapshot.habits.map { it.title })
        assertEquals(0, snapshot.todayPercent)
        assertEquals(50, snapshot.runPercent)
        driver.close()
    }
}
