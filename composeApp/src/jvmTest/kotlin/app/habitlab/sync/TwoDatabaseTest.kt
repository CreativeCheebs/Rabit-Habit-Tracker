package app.habitlab.sync

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.habitlab.db.HabitDatabase
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.long
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwoDatabaseTest {
    private fun freshDb(): HabitDatabase {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HabitDatabase.Schema.create(driver)
        ensureSyncTriggers(driver)
        return HabitDatabase(driver)
    }

    private fun deviceOf(db: HabitDatabase): String =
        db.schemaQueries.selectMetaValue("device").executeAsOne()

    /** One full exchange in each direction, like two devices syncing via the server. */
    private fun syncPair(a: HabitDatabase, b: HabitDatabase) {
        val outA = exportChanges(a)
        applyChanges(b, outA.changes)
        markExported(a, outA.upto)
        val outB = exportChanges(b)
        applyChanges(a, outB.changes)
        markExported(b, outB.upto)
    }

    private fun habitTitles(db: HabitDatabase): Map<String, String> =
        db.schemaQueries.selectHabits().executeAsList().associate { it.uid to it.title }

    private fun checkInStates(db: HabitDatabase, day: String): Map<String, String> =
        db.schemaQueries.selectCheckInsForDay(day).executeAsList().associate { it.habit_uid to it.state }

    @Test
    fun offlineInsertsConvergeBothWays() {
        val a = freshDb()
        val b = freshDb()
        a.schemaQueries.insertHabit("h1", "Morning light", "Wellbeing", 1L, 2L, "weekly", 3L)
        a.schemaQueries.insertRun("r1", "Run A", "2026-11-04", 21L)
        a.schemaQueries.attachHabitToRun("r1:h1", "r1", "h1")
        a.schemaQueries.insertCheckIn("h1:2026-11-04", "h1", "2026-11-04", "done", null)
        b.schemaQueries.insertHabit("h2", "Plan the day", "Focus", 1L, 3L, "weekly", 3L)

        syncPair(a, b)
        syncPair(a, b)

        assertEquals(habitTitles(a), habitTitles(b))
        assertEquals(setOf("h1", "h2"), habitTitles(a).keys)
        assertEquals(checkInStates(a, "2026-11-04"), checkInStates(b, "2026-11-04"))
        assertEquals("done", checkInStates(b, "2026-11-04")["h1"])
        assertEquals("Morning light", habitTitles(b)["h1"])
    }

    @Test
    fun concurrentEditsConvergeOnHigherClock() {
        val a = freshDb()
        val b = freshDb()
        a.schemaQueries.insertHabit("h1", "Title A", "Wellbeing", 1L, 2L, "weekly", 3L)
        b.schemaQueries.insertHabit("h1", "Title B", "Wellbeing", 1L, 2L, "weekly", 3L)
        // Pin deterministic stamps: A's edit is later.
        a.schemaQueries.upsertFieldStamp("habit", "h1", "title", 2000L, deviceOf(a), 99L)
        b.schemaQueries.upsertFieldStamp("habit", "h1", "title", 1000L, deviceOf(b), 99L)

        syncPair(a, b)
        syncPair(a, b)

        assertEquals("Title A", habitTitles(a)["h1"])
        assertEquals(habitTitles(a), habitTitles(b))
    }

    @Test
    fun equalClockFallsBackToHigherDeviceId() {
        val a = freshDb()
        val b = freshDb()
        val deviceA = deviceOf(a)
        val deviceB = deviceOf(b)
        a.schemaQueries.insertHabit("h1", "Title A", "Wellbeing", 1L, 2L, "weekly", 3L)
        b.schemaQueries.insertHabit("h1", "Title B", "Wellbeing", 1L, 2L, "weekly", 3L)
        a.schemaQueries.upsertFieldStamp("habit", "h1", "title", 3000L, deviceA, 99L)
        b.schemaQueries.upsertFieldStamp("habit", "h1", "title", 3000L, deviceB, 99L)
        val expected = if (deviceA > deviceB) "Title A" else "Title B"

        syncPair(a, b)
        syncPair(a, b)

        assertEquals(expected, habitTitles(a)["h1"])
        assertEquals(habitTitles(a), habitTitles(b))
    }

    @Test
    fun deleteOnOneSideWinsOverEditOnTheOther() {
        val a = freshDb()
        val b = freshDb()
        a.schemaQueries.insertHabit("h1", "Morning light", "Wellbeing", 1L, 2L, "weekly", 3L)
        a.schemaQueries.insertCheckIn("h1:2026-11-04", "h1", "2026-11-04", "done", null)
        syncPair(a, b)

        a.schemaQueries.deleteCheckInRow("h1:2026-11-04")
        b.schemaQueries.updateCheckIn("skipped", "tired", "h1", "2026-11-04")

        syncPair(a, b)
        syncPair(a, b)

        assertNull(a.schemaQueries.selectCheckInByUid("h1:2026-11-04").executeAsOneOrNull())
        assertNull(b.schemaQueries.selectCheckInByUid("h1:2026-11-04").executeAsOneOrNull())
        for (db in listOf(a, b)) {
            val tombstone = db.schemaQueries.selectFieldStamps("check_in", "h1:2026-11-04").executeAsList()
                .firstOrNull { it.field_name == "_deleted" }
            assertTrue(tombstone != null)
        }
        // The habit itself is untouched on both sides.
        assertEquals(habitTitles(a), habitTitles(b))
    }

    @Test
    fun exportOnlySendsOwnChangesSincePushed() {
        val a = freshDb()
        val b = freshDb()
        a.schemaQueries.insertHabit("h1", "Morning light", "Wellbeing", 1L, 2L, "weekly", 3L)

        val first = exportChanges(a)
        assertTrue(first.changes.isNotEmpty())
        markExported(a, first.upto)
        assertTrue(exportChanges(a).changes.isEmpty())

        applyChanges(b, first.changes)
        // Foreign rows are never re-exported as our own changes.
        assertTrue(exportChanges(b).changes.isEmpty())
    }

    @Test
    fun triggersRecordInsertsUpdatesAndDeletes() {
        val a = freshDb()
        a.schemaQueries.insertHabit("h1", "Morning light", "Wellbeing", 1L, 2L, "weekly", 3L)
        a.schemaQueries.insertCheckIn("h1:2026-11-04", "h1", "2026-11-04", "done", null)
        val inserted = exportChanges(a)
        assertTrue(inserted.changes.any { it.uid == "h1" && it.stamps.containsKey("_row") })
        markExported(a, inserted.upto)

        a.schemaQueries.updateCheckIn("skipped", "tired", "h1", "2026-11-04")
        val updated = exportChanges(a)
        val change = updated.changes.single { it.uid == "h1:2026-11-04" }
        assertTrue(change.stamps.containsKey("state"))
        assertTrue(change.stamps.containsKey("comment"))
        assertEquals("skipped", (change.values["state"] as JsonPrimitive).content)
        assertEquals("tired", (change.values["comment"] as JsonPrimitive).content)
        markExported(a, updated.upto)

        // Rewriting identical values records nothing new.
        a.schemaQueries.updateCheckIn("skipped", "tired", "h1", "2026-11-04")
        assertTrue(exportChanges(a).changes.isEmpty())

        a.schemaQueries.deleteCheckInRow("h1:2026-11-04")
        val deleted = exportChanges(a)
        assertEquals(1, deleted.changes.size)
        assertEquals(true, (deleted.changes.single().values["_deleted"] as JsonPrimitive).boolean)
    }

    @Test
    fun applyingGuardKeepsAppliedRowsOutOfExport() {
        val a = freshDb()
        val b = freshDb()
        a.schemaQueries.insertHabit("h1", "Morning light", "Wellbeing", 1L, 2L, "weekly", 3L)
        val out = exportChanges(a)
        applyChanges(b, out.changes)
        // B stored A's stamps but has no changes of its own.
        assertTrue(exportChanges(b).changes.isEmpty())
        assertEquals("Morning light", habitTitles(b)["h1"])
    }

    @Test
    fun localClockAdvancesToLargestStampSeen() {
        val a = freshDb()
        val b = freshDb()
        b.schemaQueries.insertHabit("h1", "Late edit", "Wellbeing", 1L, 2L, "weekly", 3L)
        b.schemaQueries.upsertFieldStamp("habit", "h1", "title", 9_000_000_000_000L, deviceOf(b), 99L)
        val out = exportChanges(b)
        applyChanges(a, out.changes)
        val clock = a.schemaQueries.selectMetaValue("clock").executeAsOne().toLong()
        assertTrue(clock >= 9_000_000_000_000L)
        assertEquals("0", a.schemaQueries.selectMetaValue("applying").executeAsOne())
    }

    @Test
    fun impactPointsSurviveTheRoundTrip() {
        val a = freshDb()
        val b = freshDb()
        a.schemaQueries.insertHabit("h1", "Plan the day", "Focus", 1L, 3L, "weekly", 3L)
        syncPair(a, b)
        val points = b.schemaQueries.selectHabits().executeAsList().first { it.uid == "h1" }.impact_points
        assertEquals(3L, points)
    }
}
