package app.habitlab

import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.toArgb
import androidx.core.view.WindowCompat
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.habitlab.db.HabitDatabase
import app.habitlab.sync.ensureSyncTriggers
import app.habitlab.ui.HabitLabApp
import app.habitlab.ui.palette
import java.io.File
import java.time.LocalDate

class MainActivity : ComponentActivity() {
    private lateinit var driver: AndroidSqliteDriver
    private val database = mutableStateOf<HabitDatabase?>(null)
    private val today = mutableStateOf(LocalDate.now().toString())
    private lateinit var exportBackupLauncher: ActivityResultLauncher<String>
    private lateinit var importBackupLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openDatabase()
        exportBackupLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
                if (uri != null) exportBackup(uri)
            }
        importBackupLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) importBackup(uri)
            }
        setContent {
            database.value?.let { db ->
                HabitLabApp(
                    db,
                    today.value,
                    onExportBackup = { exportBackupLauncher.launch("HabitLab-backup.rabitbackup") },
                    onImportBackup = { importBackupLauncher.launch(arrayOf("*/*")) },
                )
            }
            val bars = palette
            SideEffect {
                window.statusBarColor = bars.bg.toArgb()
                window.navigationBarColor = bars.surface.toArgb()
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !bars.dark
                controller.isAppearanceLightNavigationBars = !bars.dark
            }
        }
    }

    override fun onResume() {
        super.onResume()
        today.value = LocalDate.now().toString()
    }

    private fun openDatabase() {
        driver = AndroidSqliteDriver(HabitDatabase.Schema, applicationContext, DATABASE_NAME)
        ensureSyncTriggers(driver)
        database.value = HabitDatabase(driver).also(::seedIfNeeded)
    }

    private fun exportBackup(uri: Uri) {
        val snapshot = File(cacheDir, "habitlab-export.sqlite")
        try {
            driver.close()
            getDatabasePath(DATABASE_NAME).copyTo(snapshot, overwrite = true)
            contentResolver.openOutputStream(uri, "w")?.use { output ->
                snapshot.inputStream().use { input -> input.copyTo(output) }
            } ?: error("Could not open the selected destination")
            Toast.makeText(this, "Backup exported", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Backup export failed: ${e.message ?: "file error"}", Toast.LENGTH_LONG).show()
        } finally {
            snapshot.delete()
            openDatabase()
        }
    }

    private fun importBackup(uri: Uri) {
        val incoming = File(cacheDir, "habitlab-import.sqlite")
        val rollback = File(cacheDir, "habitlab-rollback.sqlite")
        var databaseClosed = false
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                incoming.outputStream().use { output -> copyWithLimit(input, output) }
            } ?: error("Could not read the selected file")
            validateBackup(incoming)

            driver.close()
            databaseClosed = true
            val databaseFile = getDatabasePath(DATABASE_NAME)
            databaseFile.copyTo(rollback, overwrite = true)
            removeSidecars(databaseFile)
            incoming.copyTo(databaseFile, overwrite = true)
            openDatabase()
            databaseClosed = false
            Toast.makeText(this, "Backup restored to this phone", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            if (databaseClosed) runCatching { driver.close() }
            if (databaseClosed && rollback.exists()) {
                val databaseFile = getDatabasePath(DATABASE_NAME)
                removeSidecars(databaseFile)
                rollback.copyTo(databaseFile, overwrite = true)
                openDatabase()
            } else if (databaseClosed) {
                openDatabase()
            }
            Toast.makeText(this, "Backup import failed: ${e.message ?: "invalid backup"}", Toast.LENGTH_LONG).show()
        } finally {
            incoming.delete()
            rollback.delete()
        }
    }

    private fun validateBackup(file: File) {
        require(file.length() >= SQLITE_HEADER.size && file.length() <= MAX_BACKUP_BYTES) { "File size is not valid" }
        file.inputStream().use { input ->
            val header = ByteArray(SQLITE_HEADER.size)
            require(input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)) { "This is not a SQLite backup" }
        }
        SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY).use { imported ->
            imported.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0) == "ok") { "The backup failed its integrity check" }
            }
            imported.rawQuery("PRAGMA user_version", null).use { cursor ->
                require(
                    cursor.moveToFirst() && cursor.getLong(0) == HabitDatabase.Schema.version,
                ) { "This backup is from an incompatible app version" }
            }
            imported
                .rawQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN ('habit', 'sync_meta')",
                    null,
                ).use { cursor ->
                    require(cursor.moveToFirst() && cursor.getInt(0) == 2) { "This is not a Habit Lab backup" }
                }
        }
    }

    private fun removeSidecars(databaseFile: File) {
        File(databaseFile.path + "-wal").delete()
        File(databaseFile.path + "-shm").delete()
        File(databaseFile.path + "-journal").delete()
    }

    private fun copyWithLimit(
        input: java.io.InputStream,
        output: java.io.OutputStream,
    ) {
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            require(total <= MAX_BACKUP_BYTES) { "Backup is larger than 256 MB" }
            output.write(buffer, 0, read)
        }
    }

    private fun seedIfNeeded(db: HabitDatabase) {
        if (db.schemaQueries
                .selectHabits()
                .executeAsList()
                .isNotEmpty()
        ) {
            return
        }
        val habits =
            listOf(
                Triple("morning-light", "Morning light", "Wellbeing"),
                Triple("sleep-routine", "Sleep before 11:30 PM", "Wellbeing"),
                Triple("plan-day", "Plan the day", "Focus"),
                Triple("focus-block", "Focused work block", "Focus"),
            )
        db.transaction {
            habits.forEachIndexed { i, (uid, name, group) ->
                db.schemaQueries.insertHabit(
                    uid,
                    name,
                    group,
                    if (uid ==
                        "sleep-routine"
                    ) {
                        2L
                    } else {
                        1L
                    },
                    if (i ==
                        2
                    ) {
                        3L
                    } else if (i == 3) {
                        4L
                    } else {
                        2L
                    },
                    if (uid == "sleep-routine") "interval" else "weekly",
                    3L,
                )
            }
            db.schemaQueries.insertRun("first-run", "21-day habit run", LocalDate.now().toString(), 21L)
            habits.forEach { (uid, _, _) -> db.schemaQueries.attachHabitToRun("first-run:$uid", "first-run", uid) }
        }
    }

    companion object {
        private const val DATABASE_NAME = "habitlab.db"
        private const val MAX_BACKUP_BYTES = 256L * 1024 * 1024
        private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
    }
}
