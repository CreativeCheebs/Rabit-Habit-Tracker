package app.habitlab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunYamlTest {
    @Test fun exportRoundTripsRunAndHabitSettings() {
        val habit = YamlHabit("Read \"books\"", "Home", 4, 0, 2, "book_open")
        val decoded = parseRunYaml(exportRunYaml("My run", 7, "2026-11-07", listOf(habit)))
        assertTrue(decoded.ok, decoded.errors.toString())
        assertEquals(habit, decoded.habits.single())
        assertEquals("My run", decoded.name)
        assertEquals(7, decoded.days)
    }

    @Test fun parsesEscapedStringsFromWebExport() {
        val run =
            parseRunYaml(
                """
                name: "A \"quoted\" run"
                habits:
                  - name: "Path C:\\books"
                    group: "Line\nTwo"
                    every: 1 day
                """.trimIndent(),
            )
        assertTrue(run.ok, run.errors.toString())
        assertEquals("A \"quoted\" run", run.name)
        assertEquals("Path C:\\books", run.habits.single().name)
        assertEquals("Line\nTwo", run.habits.single().group)
    }

    @Test fun parsesRunAndHabits() {
        val run =
            parseRunYaml(
                """
                name: Reset week
                days: 14
                start: 2026-11-06
                habits:
                  - name: Morning light
                    group: Wellbeing
                    points: 2
                    icon: sun
                    every: 1 day
                  - name: Plan the day
                    days_per_week: 5
                """.trimIndent(),
            )
        assertTrue(run.ok, run.errors.toString())
        assertEquals("Reset week", run.name)
        assertEquals(14, run.days)
        assertEquals("2026-11-06", run.start)
        assertEquals(2, run.habits.size)
        assertEquals(YamlHabit("Morning light", "Wellbeing", 2, 0, 1, "sun"), run.habits[0])
        assertEquals(5, run.habits[1].weekly)
        assertEquals("General", run.habits[1].group)
    }

    @Test fun habitWithoutScheduleDefaultsToDaily() {
        val run = parseRunYaml("habits:\n  - name: Stretch")
        assertTrue(run.ok)
        assertEquals(1, run.habits.single().interval)
    }

    @Test fun reportsProblems() {
        assertFalse(parseRunYaml("days: 400\nhabits:\n  - name: A").ok)
        assertFalse(parseRunYaml("habits:\n  - name: A\n    every: 2 days\n    days_per_week: 3").ok)
        assertFalse(parseRunYaml("habits:\n  - name: A\n    icon: unicorn").ok)
        assertFalse(parseRunYaml("start: 2026-02-31\nhabits:\n  - name: A").ok)
        assertFalse(parseRunYaml("name: Empty").ok)
    }

    @Test fun guidePromptMentionsEveryKey() {
        listOf("name:", "days:", "start:", "habits:", "group:", "points:", "icon:", "days_per_week:", "every:").forEach {
            assertTrue(YAML_GUIDE_PROMPT.contains(it), it)
        }
    }
}
