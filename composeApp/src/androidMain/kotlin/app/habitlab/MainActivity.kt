package app.habitlab
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import app.habitlab.ui.HabitLabApp
import app.habitlab.db.HabitDatabase
import app.habitlab.sync.ensureSyncTriggers
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.time.LocalDate
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val driver = AndroidSqliteDriver(HabitDatabase.Schema, applicationContext, "habitlab.db")
        ensureSyncTriggers(driver)
        val db = HabitDatabase(driver)
        seedIfNeeded(db)
        setContent { HabitLabApp(db, LocalDate.now().toString()) }
    }
    private fun seedIfNeeded(db: HabitDatabase) {
        if (db.schemaQueries.selectHabits().executeAsList().isNotEmpty()) return
        val habits = listOf(
            Triple("morning-light", "Morning light", "Wellbeing"), Triple("sleep-routine", "Sleep before 11:30 PM", "Wellbeing"),
            Triple("plan-day", "Plan the day", "Focus"), Triple("focus-block", "Focused work block", "Focus")
        )
        db.transaction {
            habits.forEachIndexed { i, (uid, name, group) -> db.schemaQueries.insertHabit(uid, name, group, if (uid == "sleep-routine") 2L else 1L, if (i == 2) 3L else if (i == 3) 4L else 2L, if (uid == "sleep-routine") "interval" else "weekly", 3L) }
            db.schemaQueries.insertRun("first-run", "21-day habit run", LocalDate.now().toString(), 21L)
            habits.forEach { (uid, _, _) -> db.schemaQueries.attachHabitToRun("first-run:$uid", "first-run", uid) }
        }
    }
}
