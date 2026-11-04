package app.habitlab.sync

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncEngineLogicTest {
    @Test
    fun higherClockWins() {
        assertTrue(fieldWins(2000, "b", "y", FieldStamp(1000, "a"), "x"))
        assertFalse(fieldWins(1000, "a", "x", FieldStamp(2000, "b"), "y"))
    }

    @Test
    fun equalClockHigherDeviceWins() {
        assertTrue(fieldWins(1000, "b", "x", FieldStamp(1000, "a"), "y"))
        assertFalse(fieldWins(1000, "a", "x", FieldStamp(1000, "b"), "y"))
    }

    @Test
    fun equalStampGreaterValueWins() {
        assertTrue(fieldWins(1000, "a", "y", FieldStamp(1000, "a"), "x"))
        assertFalse(fieldWins(1000, "a", "x", FieldStamp(1000, "a"), "x"))
        assertFalse(fieldWins(1000, "a", "x", FieldStamp(1000, "a"), "y"))
    }

    @Test
    fun unknownFieldIsAccepted() {
        assertTrue(fieldWins(1000, "a", "x", null, null))
        assertTrue(fieldWins(0, "a", "", null, "existing"))
    }

    @Test
    fun wireRoundTripPreservesChange() {
        val change = SyncChange(
            table = "check_in",
            uid = "plan-day:2026-11-04",
            values = mapOf(
                "uid" to JsonPrimitive("plan-day:2026-11-04"),
                "habit_uid" to JsonPrimitive("plan-day"),
                "day" to JsonPrimitive("2026-11-04"),
                "state" to JsonPrimitive("done"),
                "comment" to JsonNull,
            ),
            stamps = mapOf("state" to FieldStamp(1000, "abc123")),
        )
        assertEquals(change, syncChangeFromWire(change.toWire()))
    }

    @Test
    fun wireRoundTripPreservesTombstone() {
        val change = SyncChange(
            table = "habit",
            uid = "gone",
            values = mapOf("_deleted" to JsonPrimitive(true)),
            stamps = mapOf("_deleted" to FieldStamp(2000, "abc123")),
        )
        val parsed = syncChangeFromWire(change.toWire())
        assertEquals(change, parsed)
        assertEquals(true, (parsed.values["_deleted"] as JsonPrimitive).content.toBoolean())
    }
}
