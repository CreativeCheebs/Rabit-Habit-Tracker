package app.habitlab.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class RunChartTest {
    @Test
    fun twentyOneDayWindowKeepsAllDaysVisible() {
        assertEquals(0, historyWindowStart(0, 21))
        assertEquals(0, historyWindowStart(14, 21))
        assertEquals(0, historyWindowStart(20, 21))
        assertEquals(21, historyWindowStart(21, 21))
        assertEquals(14, historyWindowStart(20, 7))
    }

    @Test
    fun forecastProjectsEarnedDailyPace() {
        assertEquals(0f, forecastEnd(0, 0, 21))
        assertEquals(210f, forecastEnd(50, 5, 21))
        assertEquals(100f, forecastEnd(100, 21, 21))
    }
}
