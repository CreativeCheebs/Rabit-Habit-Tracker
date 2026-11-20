package app.habitlab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreakTest {
    private val daily = "a" to "7 days per week"
    private val other = "b" to "3 days per week"
    private val interval = "c" to "Every 3 days"

    @Test fun tapCycleGoesDonePartialSkippedThenClears() {
        assertEquals("done", nextCheckInState(null))
        assertEquals("partial", nextCheckInState("done"))
        assertEquals("skipped", nextCheckInState("partial"))
        assertNull(nextCheckInState("skipped"))
    }

    @Test fun tapCycleReturnsToStartAfterFourTaps() {
        var state: String? = null
        val seen = mutableListOf<String?>()
        repeat(4) {
            state = nextCheckInState(state)
            seen += state
        }
        assertEquals(listOf("done", "partial", "skipped", null), seen)
    }

    @Test fun openTodayDoesNotBreakTheStreak() {
        val records =
            mapOf(
                "2026-11-18" to mapOf("a" to "done", "b" to "done"),
                "2026-11-19" to mapOf("a" to "done", "b" to "partial"),
                "2026-11-20" to mapOf("a" to "done"),
            )
        assertEquals(2, currentStreak(listOf(daily, other), records, "2026-11-01", "2026-11-20"))
    }

    @Test fun keptTodayCountsToday() {
        val records =
            mapOf(
                "2026-11-19" to mapOf("a" to "done"),
                "2026-11-20" to mapOf("a" to "done"),
            )
        assertEquals(2, currentStreak(listOf(daily), records, "2026-11-01", "2026-11-20"))
    }

    @Test fun aMissedDayBreaksTheStreak() {
        val records =
            mapOf(
                "2026-11-17" to mapOf("a" to "done"),
                "2026-11-19" to mapOf("a" to "done"),
                "2026-11-20" to mapOf("a" to "done"),
            )
        assertEquals(2, currentStreak(listOf(daily), records, "2026-11-01", "2026-11-20"))
    }

    @Test fun skippedHabitsAreExcludedFromDue() {
        val records =
            mapOf(
                "2026-11-19" to mapOf("a" to "done", "b" to "skipped"),
                "2026-11-20" to mapOf("a" to "done", "b" to "done"),
            )
        assertEquals(2, currentStreak(listOf(daily, other), records, "2026-11-19", "2026-11-20"))
    }

    @Test fun daysWithNothingDueAreSkippedOver() {
        // Only an interval habit: done on the 17th, so the 18th and 19th are rest days.
        val records =
            mapOf(
                "2026-11-14" to mapOf("c" to "done"),
                "2026-11-17" to mapOf("c" to "done"),
                "2026-11-20" to mapOf("c" to "done"),
            )
        assertEquals(3, currentStreak(listOf(interval), records, "2026-11-14", "2026-11-20"))
    }

    @Test fun intervalRestDaysDoNotBreakAMixedStreak() {
        val records =
            mapOf(
                "2026-11-18" to mapOf("a" to "done", "c" to "done"),
                "2026-11-19" to mapOf("a" to "done"),
                "2026-11-20" to mapOf("a" to "done"),
            )
        assertEquals(3, currentStreak(listOf(daily, interval), records, "2026-11-18", "2026-11-20"))
    }

    @Test fun streakNeverCountsBeforeTheFirstDay() {
        val records = (10..20).associate { "2026-11-$it" to mapOf("a" to "done") }
        assertEquals(5, currentStreak(listOf(daily), records, "2026-11-16", "2026-11-20"))
    }

    @Test fun noHabitsOrNoProgressIsZero() {
        assertEquals(0, currentStreak(emptyList(), emptyMap(), "2026-11-01", "2026-11-20"))
        assertEquals(0, currentStreak(listOf(daily), emptyMap(), "2026-11-01", "2026-11-20"))
    }

    @Test fun daySummaryCountsDonePartialAndRestingHabits() {
        val records = mapOf("2026-11-18" to mapOf("c" to "done"), "2026-11-19" to mapOf("a" to "partial"))
        val summary = daySummary(listOf(daily, interval), "2026-11-19", records, doneDaysOf(records))
        assertEquals(DaySummary(due = 1, done = 0, partial = 1), summary)
        assertEquals(true, summary.kept)
        assertEquals(false, summary.allDone)
    }
}
