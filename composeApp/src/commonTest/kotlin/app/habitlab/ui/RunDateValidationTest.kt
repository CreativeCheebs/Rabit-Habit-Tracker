package app.habitlab.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunDateValidationTest {
    @Test
    fun acceptsRealIsoDatesAndRejectsInvalidDates() {
        assertTrue(isValidRunDate("2024-02-29"))
        assertFalse(isValidRunDate("2023-02-29"))
        assertFalse(isValidRunDate("2026-04-31"))
        assertFalse(isValidRunDate("2026-1-01"))
        assertFalse(isValidRunDate("0000-01-01"))
    }
}
