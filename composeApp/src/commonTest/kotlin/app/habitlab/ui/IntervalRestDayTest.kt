package app.habitlab.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IntervalRestDayTest {
    @Test fun intervalOnlyHidesDaysFollowingACompletion() {
        val done = setOf("2026-01-31")
        assertTrue(isIntervalRestDay("Every 3 days", "2026-02-01", done))
        assertTrue(isIntervalRestDay("Every 3 days", "2026-02-02", done))
        assertFalse(isIntervalRestDay("Every 3 days", "2026-02-03", done))
        assertFalse(isIntervalRestDay("Every 3 days", "2026-01-30", done))
        assertFalse(isIntervalRestDay("Every 1 days", "2026-02-01", done))
        assertFalse(isIntervalRestDay("3 days per week", "2026-02-01", done))
    }
}
