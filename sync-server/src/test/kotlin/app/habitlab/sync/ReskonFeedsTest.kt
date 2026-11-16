package app.habitlab.sync

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReskonFeedsTest {
    private fun withAccount(build: (File) -> Unit): File {
        val dir = Files.createTempDirectory("habitlab-reskon-").toFile()
        val file = File(dir, "account.sqlite")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            db.createStatement().use { st ->
                st.execute("CREATE TABLE sync_field (table_name TEXT NOT NULL, uid TEXT NOT NULL, field_name TEXT NOT NULL, value_json TEXT, clock INTEGER NOT NULL, device TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(table_name,uid,field_name))")
                st.execute("CREATE TABLE sync_counter (id INTEGER PRIMARY KEY CHECK(id=1), seq INTEGER NOT NULL)")
                st.execute("INSERT INTO sync_counter VALUES (1,0)")
            }
        }
        build(file)
        return file
    }

    private fun put(file: File, table: String, uid: String, field: String, value: String, clock: Long = 1L) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            db.prepareStatement(
                "INSERT INTO sync_field(table_name,uid,field_name,value_json,clock,device,seq) VALUES (?,?,?,?,?,'test',1) " +
                    "ON CONFLICT(table_name,uid,field_name) DO UPDATE SET value_json=excluded.value_json",
            ).use { ps ->
                ps.setString(1, table); ps.setString(2, uid); ps.setString(3, field)
                ps.setString(4, value); ps.setLong(5, clock); ps.executeUpdate()
            }
        }
    }

    private fun habit(file: File, uid: String, title: String, archived: Boolean = false, group: String = "General") {
        put(file, "habit", uid, "uid", "\"$uid\"")
        put(file, "habit", uid, "title", "\"$title\"")
        put(file, "habit", uid, "group_name", "\"$group\"")
        put(file, "habit", uid, "recurrence_type", "\"weekly\"")
        put(file, "habit", uid, "recurrence_value", "1")
        put(file, "habit", uid, "archived", archived.toString())
    }

    private fun checkIn(file: File, habitUid: String, day: String, state: String) {
        val uid = "$habitUid:$day"
        put(file, "check_in", uid, "uid", "\"$uid\"")
        put(file, "check_in", uid, "habit_uid", "\"$habitUid\"")
        put(file, "check_in", uid, "day", "\"$day\"")
        put(file, "check_in", uid, "state", "\"$state\"")
    }

    @Test
    fun todayUsesTheGivenTimeZone() {
        assertTrue(ReskonFeeds.isDay(ReskonFeeds.todayIn("Asia/Kolkata")))
        assertEquals(ReskonFeeds.todayIn("UTC"), ReskonFeeds.todayIn("UTC"))
        assertTrue(ReskonFeeds.isDay(ReskonFeeds.todayIn("not-a-zone")))
        assertFalse(ReskonFeeds.isDay("2026-13-45"))
        assertFalse(ReskonFeeds.isDay("2026-01-01' OR 1=1"))
    }

    @Test
    fun cleanTextStripsControlsAndCapsBytes() {
        assertEquals("hello", ReskonFeeds.cleanText("he\u0000l\u001flo", 100, "Habit"))
        assertEquals("Habit", ReskonFeeds.cleanText("\u202E", 100, "Habit"))
        assertEquals("evil", ReskonFeeds.cleanText("\u202Eevil", 100, "Habit"))
        assertEquals("Habit", ReskonFeeds.cleanText(null, 100, "Habit"))
        assertEquals("Habit", ReskonFeeds.cleanText("   ", 100, "Habit"))
        val long = "é".repeat(50)
        val capped = ReskonFeeds.cleanText(long, 10, "Habit")
        assertTrue(capped.toByteArray(Charsets.UTF_8).size <= 10)
        assertEquals(5, capped.length)
    }

    @Test
    fun checklistMatchesThePromisedShape() {
        val file = withAccount {
            habit(it, "meditate", "Meditate")
            habit(it, "walk", "Walk for a while")
            habit(it, "old", "Retired", archived = true)
            checkIn(it, "meditate", "2026-11-16", "done")
        }
        val answer = ReskonFeeds.checklist(ReskonFeeds.load(file), "2026-11-16")
        assertEquals("checklist", answer["shape"]!!.jsonPrimitive.content)
        val items = answer["items"]!!.jsonArray
        assertEquals(2, items.size, "archived habits are not shown")
        val first = items[0].jsonObject
        assertTrue(first.containsKey("key"))
        assertEquals("Meditate", first["label"]!!.jsonPrimitive.content)
        assertTrue(first["done"]!!.jsonPrimitive.content == "true")
        assertTrue(items.none { it.jsonObject["label"]!!.jsonPrimitive.content == "Retired" })
        file.deleteRecursively()
    }

    @Test
    fun seriesZeroFillsTheRangeAndTotals() {
        val file = withAccount {
            habit(it, "meditate", "Meditate")
            checkIn(it, "meditate", "2026-11-14", "done")
            checkIn(it, "meditate", "2026-11-15", "done")
            checkIn(it, "meditate", "2026-11-16", "skipped")
        }
        val rows = ReskonFeeds.load(file)
        val answer = ReskonFeeds.series(rows, "2026-11-13", "2026-11-16", "day")
        assertEquals("series", answer["shape"]!!.jsonPrimitive.content)
        val points = answer["points"]!!.jsonArray
        assertEquals(4, points.size)
        assertEquals(listOf("0", "1", "1", "0"), points.map { it.jsonObject["value"]!!.jsonPrimitive.content })
        assertEquals(2, answer["total"]!!.jsonPrimitive.content.toInt())
        // An empty range is an empty answer, not an error.
        val none = ReskonFeeds.series(rows, "2030-01-02", "2030-01-01", "day")
        assertEquals(0, none["points"]!!.jsonArray.size)
        assertEquals(0, none["total"]!!.jsonPrimitive.content.toInt())
        file.deleteRecursively()
    }

    @Test
    fun seriesGroupsByWeekOnMondayKeys() {
        val file = withAccount {
            habit(it, "meditate", "Meditate")
            checkIn(it, "meditate", "2026-11-14", "done") // Saturday
            checkIn(it, "meditate", "2026-11-15", "done") // Sunday, same week
        }
        val points = ReskonFeeds.series(ReskonFeeds.load(file), "2026-11-09", "2026-11-15", "week")["points"]!!.jsonArray
        assertEquals(1, points.size)
        assertEquals("2026-11-09", points[0].jsonObject["key"]!!.jsonPrimitive.content)
        assertEquals(2, points[0].jsonObject["value"]!!.jsonPrimitive.content.toInt())
        file.deleteRecursively()
    }

    @Test
    fun seriesNeverExceedsTheSpanCap() {
        val points = ReskonFeeds.series(
            ReskonFeeds.AccountRows(emptyList(), emptyList(), emptyList(), emptyMap()),
            "2000-01-01", "2026-11-16", "day",
        )["points"]!!.jsonArray
        assertEquals(ReskonFeeds.MAX_SPAN_DAYS, points.size)
    }

    @Test
    fun streaksCountConsecutiveDoneDays() {
        val done = setOf("2026-11-14", "2026-11-15", "2026-11-16")
        assertEquals(3, ReskonFeeds.currentStreak(done, "2026-11-16"))
        assertEquals(3, ReskonFeeds.currentStreak(done, "2026-11-17"), "today is still open")
        assertEquals(0, ReskonFeeds.currentStreak(done, "2026-11-20"))
        assertEquals(3, ReskonFeeds.longestStreak(done + "2026-11-10" + "2026-11-11" + "2026-11-12" + "2026-11-25"))
        assertEquals(0, ReskonFeeds.longestStreak(emptySet()))
    }

    @Test
    fun streakTableHasPromisedColumnsAndNumbers() {
        val file = withAccount {
            habit(it, "meditate", "Meditate")
            habit(it, "walk", "Walk")
            checkIn(it, "meditate", "2026-11-15", "done")
            checkIn(it, "meditate", "2026-11-16", "done")
            checkIn(it, "walk", "2026-11-01", "done")
        }
        val answer = ReskonFeeds.streakTable(ReskonFeeds.load(file), "2026-11-16")
        assertEquals("table", answer["shape"]!!.jsonPrimitive.content)
        val columns = answer["columns"]!!.jsonArray
        assertTrue(columns.size in 1..12)
        val rows = answer["rows"]!!.jsonArray
        assertEquals(2, rows.size)
        val meditate = rows.map { it.jsonObject }.first { it["habit"]!!.jsonPrimitive.content == "Meditate" }
        assertEquals("2", meditate["current"]!!.jsonPrimitive.content)
        assertEquals("2", meditate["longest"]!!.jsonPrimitive.content)
        val walk = rows.map { it.jsonObject }.first { it["habit"]!!.jsonPrimitive.content == "Walk" }
        assertEquals("0", walk["current"]!!.jsonPrimitive.content)
        assertEquals("1", walk["longest"]!!.jsonPrimitive.content)
        file.deleteRecursively()
    }

    @Test
    fun bestStreakIsTheLongestOfAnyHabit() {
        val file = withAccount {
            habit(it, "meditate", "Meditate")
            habit(it, "walk", "Walk")
            checkIn(it, "meditate", "2026-11-16", "done")
            for (day in listOf("2026-11-13", "2026-11-14", "2026-11-15", "2026-11-16")) checkIn(it, "walk", day, "done")
        }
        val answer = ReskonFeeds.bestStreak(ReskonFeeds.load(file), "2026-11-16")
        assertEquals("number", answer["shape"]!!.jsonPrimitive.content)
        assertEquals("4", answer["value"]!!.jsonPrimitive.content)
        assertEquals("days", answer["unit"]!!.jsonPrimitive.content)
        file.deleteRecursively()
    }

    @Test
    fun pageListShowsTodaysHabits() {
        val file = withAccount {
            habit(it, "meditate", "Meditate")
            checkIn(it, "meditate", "2026-11-16", "done")
        }
        val page = ReskonFeeds.pageList(ReskonFeeds.load(file), "2026-11-16")
        assertEquals("2026-11-16", page["day"]!!.jsonPrimitive.content)
        val habits = page["habits"]!!.jsonArray
        assertEquals(1, habits.size)
        assertEquals("meditate", habits[0].jsonObject["key"]!!.jsonPrimitive.content)
        assertTrue(habits[0].jsonObject["done"]!!.jsonPrimitive.content == "true")
        file.deleteRecursively()
    }

    @Test
    fun pageListCapsTheAnswer() {
        val file = withAccount {
            for (i in 0 until ReskonFeeds.MAX_ITEMS + 50) habit(it, "h$i", "Habit $i")
        }
        val page = ReskonFeeds.pageList(ReskonFeeds.load(file), "2026-11-16")
        assertTrue(page["habits"]!!.jsonArray.size <= ReskonFeeds.MAX_ITEMS)
        file.deleteRecursively()
    }

    @Test
    fun writingACheckInUsesFreshStampsAndSurvivesReload() {
        val file = withAccount { habit(it, "meditate", "Meditate") }
        assertTrue(ReskonFeeds.writeCheckIn(file, "meditate", "2026-11-16", "done"))
        val rows = ReskonFeeds.load(file)
        assertEquals("done", rows.checkIns["meditate"]?.get("2026-11-16"))
        // Writing again must not go backwards, so a client that wrote later still loses nothing.
        assertTrue(ReskonFeeds.writeCheckIn(file, "meditate", "2026-11-16", "skipped"))
        assertEquals("skipped", ReskonFeeds.load(file).checkIns["meditate"]?.get("2026-11-16"))
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            db.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM sync_field WHERE table_name = 'check_in' AND device <> 'reskon-widget'").use { rs ->
                    assertTrue(rs.next()); assertEquals(0, rs.getInt(1))
                }
                st.executeQuery("SELECT COUNT(DISTINCT seq) FROM sync_field WHERE device = 'reskon-widget'").use { rs ->
                    assertTrue(rs.next())
                    val distinct = rs.getInt(1)
                    db.createStatement().use { inner ->
                        inner.executeQuery("SELECT COUNT(*) FROM sync_field WHERE device = 'reskon-widget'").use { count ->
                            count.next()
                            assertEquals(count.getInt(1), distinct, "every write carries its own sequence number")
                        }
                    }
                }
            }
        }
        file.deleteRecursively()
    }

    @Test
    fun badTickInputIsRefused() {
        val file = withAccount { habit(it, "meditate", "Meditate") }
        assertFalse(ReskonFeeds.writeCheckIn(file, "not a habit", "2026-11-16", "done"))
        assertFalse(ReskonFeeds.writeCheckIn(file, "meditate", "soon", "done"))
        assertFalse(ReskonFeeds.writeCheckIn(file, "meditate", "2026-11-16", "maybe"))
        assertEquals(0, ReskonFeeds.load(file).checkIns.size)
        file.deleteRecursively()
    }

    @Test
    fun textInAnswersIsPlainAndBounded() {
        val file = withAccount {
            habit(it, "meditate", "Me\u0000dit\u202Eate <b>x</b>")
            checkIn(it, "meditate", "2026-11-16", "done")
        }
        val answer = ReskonFeeds.checklist(ReskonFeeds.load(file), "2026-11-16")
        val label = answer["items"]!!.jsonArray[0].jsonObject["label"]!!.jsonPrimitive.content
        assertFalse(label.contains('\u0000'))
        assertFalse(label.contains('\u202E'))
        assertTrue(label.toByteArray(Charsets.UTF_8).size <= 120)
        file.deleteRecursively()
    }

    @Test
    fun missingFileIsAnEmptyAccount() {
        val missing = File(Files.createTempDirectory("habitlab-reskon-none").toFile(), "absent.sqlite")
        val rows = ReskonFeeds.load(missing)
        assertTrue(rows.habits.isEmpty())
        assertTrue(ReskonFeeds.checklist(rows, "2026-11-16")["items"]!!.jsonArray.isEmpty())
        assertTrue((ReskonFeeds.series(rows, "2026-11-10", "2026-11-16", "day")["total"] as JsonPrimitive).content == "0")
    }
}
