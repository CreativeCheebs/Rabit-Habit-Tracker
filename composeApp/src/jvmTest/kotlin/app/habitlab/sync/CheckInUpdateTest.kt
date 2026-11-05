package app.habitlab.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.habitlab.db.HabitDatabase
import kotlin.test.Test
import kotlin.test.assertEquals

class CheckInUpdateTest {
    private fun freshDb(): HabitDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HabitDatabase.Schema.create(driver)
        ensureSyncTriggers(driver)
        return HabitDatabase(driver)
    }

    @Test
    fun changingStateWithoutCommentKeepsExistingNote() {
        val db = freshDb()
        db.schemaQueries.insertCheckIn("h1:2026-11-05", "h1", "2026-11-05", "done", "felt great")
        db.schemaQueries.updateCheckIn("skipped", null, "h1", "2026-11-05")
        val row = db.schemaQueries.selectCheckInByUid("h1:2026-11-05").executeAsOne()
        assertEquals("skipped", row.state)
        assertEquals("felt great", row.comment)
    }

    @Test
    fun newCommentReplacesOldNote() {
        val db = freshDb()
        db.schemaQueries.insertCheckIn("h1:2026-11-05", "h1", "2026-11-05", "done", "felt great")
        db.schemaQueries.updateCheckIn("partial", "cut short", "h1", "2026-11-05")
        assertEquals("cut short", db.schemaQueries.selectCheckInByUid("h1:2026-11-05").executeAsOne().comment)
    }
}
