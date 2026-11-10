package app.habitlab.ui

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.habitlab.db.HabitDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RunHabitsTest {
    @Test fun duplicateTitlesIgnoreCaseAndWhitespace() {
        assertEquals("MORNING   light", duplicateHabitTitle(listOf("Morning light", "  MORNING   light ")))
        assertEquals(null, duplicateHabitTitle(listOf("Morning light", "Evening light")))
    }

    @Test fun stoppingAndDeletingRunKeepsHabitsAndCheckIns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HabitDatabase.Schema.create(driver)
        val db = HabitDatabase(driver)
        db.schemaQueries.insertRun("run", "Run", "2026-11-07", 7)
        db.schemaQueries.insertHabit("habit", "Habit", "General", 1, 1, "interval", 1)
        db.schemaQueries.attachHabitToRun("link", "run", "habit")
        db.schemaQueries.insertCheckIn("check", "habit", "2026-11-07", "done", null)
        db.schemaQueries.stopRun("run")
        assertEquals(
            "stopped",
            db.schemaQueries
                .selectRunByUid("run")
                .executeAsOne()
                .status,
        )
        db.deleteRunKeepingHabits("run")
        assertEquals(null, db.schemaQueries.selectRunByUid("run").executeAsOneOrNull())
        assertEquals(
            "Habit",
            db.schemaQueries
                .selectHabitByUid("habit")
                .executeAsOne()
                .title,
        )
        assertEquals(
            "done",
            db.schemaQueries
                .selectCheckInByUid("check")
                .executeAsOne()
                .state,
        )
    }

    @Test fun editingKeepsExistingLinksAndCheckIns() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HabitDatabase.Schema.create(driver)
        val db = HabitDatabase(driver)
        db.schemaQueries.insertRun("run", "Before", "2026-11-07", 7)
        db.schemaQueries.insertHabit("habit", "Before", "General", 1, 2, "interval", 2)
        db.schemaQueries.attachHabitToRun("link", "run", "habit")
        db.schemaQueries.insertCheckIn("check", "habit", "2026-11-07", "done", null)
        db.schemaQueries.updateRunDetails("After", "2026-11-07", 14, "run")
        db.schemaQueries.updateHabitDetails("After", "General", 3, "weekly", 4, "habit")
        assertEquals(
            "After",
            db.schemaQueries
                .selectRunByUid("run")
                .executeAsOne()
                .name,
        )
        assertEquals(
            "After",
            db.schemaQueries
                .selectHabitsForRun("run")
                .executeAsOne()
                .title,
        )
        assertEquals(
            "done",
            db.schemaQueries
                .selectCheckInsForHabit("habit")
                .executeAsOne()
                .state,
        )
        assertEquals(
            "link",
            db.schemaQueries
                .selectRunLinks("run")
                .executeAsOne()
                .uid,
        )
    }

    @Test fun parallelActiveRunsAreStoppedWithoutDeletingHistory() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HabitDatabase.Schema.create(driver)
        val db = HabitDatabase(driver)
        db.schemaQueries.insertRun("older", "Older", "2026-11-07", 21)
        db.schemaQueries.insertRun("newer", "Newer", "2026-11-07", 21)
        db.setPref(PREF_CURRENT_RUN, "newer")
        assertEquals("older", db.currentRun()?.uid)
        assertEquals(1, db.ensureSingleActiveRun())
        assertEquals(listOf("older"), db.activeRuns().map { it.uid })
        assertEquals(
            "stopped",
            db.schemaQueries
                .selectRunByUid("newer")
                .executeAsOne()
                .status,
        )
        assertEquals(0, db.ensureSingleActiveRun())
        driver.close()
    }

    @Test fun emptyRealRunNeverDisplaysExampleOrUnrelatedHabits() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HabitDatabase.Schema.create(driver)
        val db = HabitDatabase(driver)
        db.schemaQueries.insertRun("run-one", "Empty", "2026-11-01", 21)
        db.schemaQueries.insertHabit("unrelated", "Other", "General", 1, 1, "interval", 1)
        assertTrue(loadRunHabits(db, "run-one").isEmpty())
        db.schemaQueries.attachHabitToRun("link", "run-one", "unrelated")
        assertEquals(listOf("Other"), loadRunHabits(db, "run-one").map { it.name })
    }
}
