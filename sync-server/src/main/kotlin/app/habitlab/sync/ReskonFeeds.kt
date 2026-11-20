package app.habitlab.sync

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/**
 * Reskon data widgets (docs/reskon-plugin.md): an account's synced rows in
 * the shapes Reskon draws. Plain logic over one database file, so it can be
 * tested without running the server.
 */
object ReskonFeeds {
    private val json = Json { ignoreUnknownKeys = true }
    private val dayLabel: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)
    private val isoDay = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val safeUid = Regex("[A-Za-z0-9._:@#+~-]{1,200}")

    /** Reskon's caps (docs/plugins.md section 6.2). */
    const val MAX_ITEMS = 500
    const val MAX_SPAN_DAYS = 400

    data class HabitRow(
        val uid: String,
        val title: String,
        val group: String,
        val recurrenceType: String,
        val recurrenceValue: Int,
        val archived: Boolean,
    )

    data class RunRow(
        val uid: String,
        val status: String,
        val startDate: String,
        val durationDays: Int = 21,
    )
    data class LinkRow(val runUid: String, val habitUid: String)

    /** An account's rows: habit uid -> day -> check-in state. */
    data class AccountRows(
        val habits: List<HabitRow>,
        val runs: List<RunRow>,
        val links: List<LinkRow>,
        val checkIns: Map<String, Map<String, String>>,
    )

    private val emptyRows = AccountRows(emptyList(), emptyList(), emptyList(), emptyMap())

    /** The person's today in their time zone; UTC when the zone is unknown. */
    fun todayIn(tz: String?): String {
        val zone = try {
            if (tz.isNullOrBlank()) ZoneOffset.UTC else ZoneId.of(tz)
        } catch (e: Exception) {
            ZoneOffset.UTC
        }
        return LocalDate.now(zone).toString()
    }

    fun isDay(value: String): Boolean {
        if (!isoDay.matches(value)) return false
        return try {
            LocalDate.parse(value); true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Plain text Reskon accepts: control, direction-changing and invisible
     * format characters removed, at most [maxBytes] UTF-8 bytes without
     * splitting a character, and [fallback] when nothing is left.
     */
    fun cleanText(raw: String?, maxBytes: Int, fallback: String): String {
        if (raw == null) return fallback
        val kept = StringBuilder()
        var bytes = 0
        for (cp in raw.codePoints().toArray()) {
            val type = Character.getType(cp)
            if (Character.isISOControl(cp) || type == Character.FORMAT.toInt()) continue
            val size = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (bytes + size > maxBytes) break
            kept.appendCodePoint(cp)
            bytes += size
        }
        return kept.toString().trim().ifEmpty { fallback }
    }

    /** Reads the four tables the feeds need; a missing file is an empty account. */
    fun load(file: File): AccountRows {
        if (!file.exists()) return emptyRows
        return DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            db.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") }
            val fields = linkedMapOf<Triple<String, String, String>, JsonElement>()
            db.prepareStatement(
                "SELECT table_name, uid, field_name, value_json FROM sync_field " +
                    "WHERE table_name IN ('habit','run','run_habit','check_in')",
            ).use { ps ->
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        val raw = rs.getString(4) ?: continue
                        val value = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: continue
                        fields[Triple(rs.getString(1), rs.getString(2), rs.getString(3))] = value
                    }
                }
            }
            buildRows(fields)
        }
    }

    private fun text(fields: Map<String, JsonElement>, key: String): String? =
        (fields[key] as? JsonPrimitive)?.takeIf { !it.isString || it.content.isNotEmpty() }?.content

    private fun truthy(value: JsonElement?): Boolean {
        val p = value as? JsonPrimitive ?: return false
        return p.content == "true" || p.content == "1" || (p.content.toDoubleOrNull()?.let { it != 0.0 } == true)
    }

    private fun buildRows(fields: Map<Triple<String, String, String>, JsonElement>): AccountRows {
        val grouped = fields.entries.groupBy(
            { it.key.first to it.key.second },
            { it.key.third to it.value },
        )

        fun rowFields(table: String, uid: String): Map<String, JsonElement> = grouped[table to uid].orEmpty().toMap()

        fun alive(row: Map<String, JsonElement>): Boolean = !truthy(row["_deleted"])

        val byTable = grouped.keys.groupBy({ it.first }, { it.second })

        val habits = byTable["habit"].orEmpty().mapNotNull { uid ->
            val row = rowFields("habit", uid)
            if (!alive(row)) return@mapNotNull null
            HabitRow(
                uid = uid,
                title = text(row, "title") ?: "",
                group = text(row, "group_name") ?: "",
                recurrenceType = text(row, "recurrence_type") ?: "weekly",
                recurrenceValue = text(row, "recurrence_value")?.toIntOrNull() ?: 1,
                archived = truthy(row["archived"]),
            )
        }

        val runs = byTable["run"].orEmpty().mapNotNull { uid ->
            val row = rowFields("run", uid)
            if (!alive(row)) return@mapNotNull null
            val dur = text(row, "duration_days")?.toIntOrNull() ?: 21
            RunRow(uid, text(row, "status") ?: "", text(row, "start_date") ?: "", dur)
        }

        val links = byTable["run_habit"].orEmpty().mapNotNull { uid ->
            val row = rowFields("run_habit", uid)
            if (!alive(row)) return@mapNotNull null
            val runUid = text(row, "run_uid") ?: return@mapNotNull null
            val habitUid = text(row, "habit_uid") ?: return@mapNotNull null
            LinkRow(runUid, habitUid)
        }

        val checkIns = linkedMapOf<String, MutableMap<String, String>>()
        for (uid in byTable["check_in"].orEmpty()) {
            val row = rowFields("check_in", uid)
            if (!alive(row)) continue
            val habitUid = text(row, "habit_uid") ?: continue
            val day = text(row, "day") ?: continue
            val state = text(row, "state") ?: continue
            checkIns.getOrPut(habitUid) { linkedMapOf() }[day] = state
        }
        return AccountRows(habits, runs, links, checkIns)
    }

    fun isRunEnded(run: RunRow, today: String): Boolean {
        if (run.startDate.isBlank() || !isDay(run.startDate) || !isDay(today)) return false
        val end = try {
            LocalDate.parse(run.startDate).plusDays(run.durationDays.toLong() - 1)
        } catch (_: Exception) { return false }
        val current = try { LocalDate.parse(today) } catch (_: Exception) { return false }
        return current.isAfter(end)
    }

    /** The newest active run; clients keep exactly one. */
    fun activeRun(rows: AccountRows, today: String? = null): RunRow? =
        rows.runs.filter { it.status == "active" && (today == null || !isRunEnded(it, today)) }
            .sortedWith(compareByDescending<RunRow> { it.startDate }.thenByDescending { it.uid })
            .firstOrNull()

    /**
     * Today's habits, as the web app shows them: habits of the active run
     * plus standalone habits, never archived or stopped-run history.
     */
    fun todayHabits(rows: AccountRows, today: String? = null): List<HabitRow> {
        val activeRun = activeRun(rows, today)
        if (today != null && activeRun == null && rows.runs.any { it.status == "active" && isRunEnded(it, today) }) return emptyList()
        val active = activeRun?.uid
        val linkedAny = rows.links.map { it.habitUid }.toSet()
        val linkedActive = rows.links.filter { it.runUid == active }.map { it.habitUid }.toSet()
        return rows.habits
            .filter { !it.archived && (it.uid !in linkedAny || it.uid in linkedActive) }
            .sortedWith(compareBy({ it.group + it.title }, { it.uid }))
    }

    fun statesOn(rows: AccountRows, day: String): Map<String, String> =
        rows.checkIns.mapNotNull { (habit, days) -> days[day]?.let { habit to it } }.toMap()

    private fun aliveHabits(rows: AccountRows): List<HabitRow> = rows.habits.filter { !it.archived }

    fun recurrenceLabel(habit: HabitRow): String =
        if (habit.recurrenceType == "interval") {
            val n = habit.recurrenceValue.coerceAtLeast(1)
            "Every $n day${if (n == 1) "" else "s"}"
        } else {
            val n = habit.recurrenceValue.coerceAtLeast(1)
            "$n day${if (n == 1) "" else "s"} per week"
        }

    /** The page widget's list: {"day": ..., "habits": [{key,label,done}]}. */
    fun pageList(rows: AccountRows, day: String): JsonObject {
        val states = statesOn(rows, day)
        val active = activeRun(rows, day)
        val hasEndedRun = active == null && rows.runs.any { it.status == "active" && isRunEnded(it, day) }
        val habits = if (hasEndedRun) emptyList() else todayHabits(rows, day).take(MAX_ITEMS)
        return buildJsonObject {
            put("day", day)
            put("run_ended", hasEndedRun)
            put(
                "habits",
                buildJsonArray {
                    for (habit in habits) {
                        add(
                            buildJsonObject {
                                put("key", habit.uid)
                                put("label", cleanText(habit.title, 120, "Habit"))
                                put("done", states[habit.uid] == "done")
                            },
                        )
                    }
                },
            )
        }
    }

    /** data widget "today_list": today's habits, read-only. */
    fun checklist(rows: AccountRows, today: String): JsonObject {
        val states = statesOn(rows, today)
        return buildJsonObject {
            put("shape", "checklist")
            put("label", "Today")
            put(
                "items",
                buildJsonArray {
                    for (habit in todayHabits(rows, today).take(MAX_ITEMS)) {
                        val state = states[habit.uid]
                        val detail = when (state) {
                            "partial" -> "Partial"
                            "skipped" -> "Skipped"
                            else -> recurrenceLabel(habit)
                        }
                        add(
                            buildJsonObject {
                                val key = habit.uid.takeIf { it.toByteArray(Charsets.UTF_8).size <= 100 && safeUid.matches(it) }
                                if (key != null) put("key", key)
                                put("label", cleanText(habit.title, 120, "Habit"))
                                put("detail", cleanText(detail, 200, ""))
                                put("done", state == "done")
                            },
                        )
                    }
                },
            )
        }
    }

    /** data widget "today_list": unique habits linked to the current run. */
    fun todayTable(rows: AccountRows, today: String): JsonObject {
        val activeRunUid = activeRun(rows, today)?.uid
        val currentRunHabitUids = if (activeRunUid == null) emptySet() else rows.links.asSequence()
            .filter { it.runUid == activeRunUid }
            .map { it.habitUid }
            .toSet()
        val habits = rows.habits.asSequence()
            .filter { !it.archived && it.uid in currentRunHabitUids }
            .distinctBy { it.uid }
            .sortedWith(compareBy({ it.group + it.title }, { it.uid }))
            .take(MAX_ITEMS)
            .toList()
        val states = statesOn(rows, today)
        val runEnded = activeRun(rows, today) == null && rows.runs.any { it.status == "active" && isRunEnded(it, today) }
        return buildJsonObject {
            put("shape", "table")
            put("run_ended", runEnded)
            if (runEnded) put("empty_message", "Run completed! Start a new run in Habit Lab.")
            put(
                "columns",
                buildJsonArray {
                    add(buildJsonObject { put("key", "habit"); put("label", "Habit") })
                    add(buildJsonObject { put("key", "status"); put("label", "Today") })
                    add(buildJsonObject { put("key", "schedule"); put("label", "Schedule") })
                },
            )
            put(
                "rows",
                buildJsonArray {
                    for (habit in habits) {
                        val status = when (states[habit.uid]) {
                            "done" -> "Done"
                            "partial" -> "Partial"
                            "skipped" -> "Skipped"
                            else -> "Not logged"
                        }
                        add(
                            buildJsonObject {
                                put("habit", cleanText(habit.title, 120, "Habit"))
                                put("status", status)
                                put("schedule", cleanText(recurrenceLabel(habit), 200, ""))
                            },
                        )
                    }
                },
            )
        }
    }

    /** data widget "last_days": habits done each day over the range. */
    fun series(rows: AccountRows, from: String, to: String, group: String): JsonObject {
        val counts = hashMapOf<String, Int>()
        val alive = aliveHabits(rows).map { it.uid }.toSet()
        for ((habit, days) in rows.checkIns) {
            if (habit !in alive) continue
            for ((day, state) in days) {
                if (state == "done") counts[day] = (counts[day] ?: 0) + 1
            }
        }
        val start = LocalDate.parse(from)
        var end = LocalDate.parse(to)
        if (start > end) {
            return buildJsonObject {
                put("shape", "series")
                put("unit", "count")
                put("points", buildJsonArray { })
                put("total", 0)
            }
        }
        // Reskon asks for at most 400 days; keep the newest ones if asked for more.
        if (start < end.minusDays((MAX_SPAN_DAYS - 1).toLong())) {
            return series(rows, end.minusDays((MAX_SPAN_DAYS - 1).toLong()).toString(), to, group)
        }
        val points = buildJsonArray {
            if (group == "week") {
                val buckets = linkedMapOf<String, Int>()
                var day = start
                while (!day.isAfter(end)) {
                    val monday = day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    val key = monday.toString()
                    buckets[key] = (buckets[key] ?: 0) + (counts[day.toString()] ?: 0)
                    day = day.plusDays(1)
                }
                for ((key, value) in buckets) {
                    add(
                        buildJsonObject {
                            put("key", key)
                            put("label", LocalDate.parse(key).format(dayLabel))
                            put("value", value)
                        },
                    )
                }
            } else {
                var day = start
                while (!day.isAfter(end)) {
                    val key = day.toString()
                    add(
                        buildJsonObject {
                            put("key", key)
                            put("label", day.format(dayLabel))
                            put("value", counts[key] ?: 0)
                        },
                    )
                    day = day.plusDays(1)
                }
            }
        }
        var total = 0
        for (point in points) {
            total += ((point as JsonObject)["value"] as JsonPrimitive).content.toInt()
        }
        return buildJsonObject {
            put("shape", "series")
            put("unit", "count")
            put("points", points)
            put("total", total)
        }
    }

    /** Consecutive done days ending today, or yesterday when today is still open. */
    fun currentStreak(doneDays: Set<String>, today: String): Int {
        if (!isDay(today)) return 0
        var day = LocalDate.parse(today)
        if (today !in doneDays) day = day.minusDays(1)
        var count = 0
        while (day.toString() in doneDays) {
            count++
            day = day.minusDays(1)
        }
        return count
    }

    /** The longest run of consecutive done days anywhere in the history. */
    fun longestStreak(doneDays: Collection<String>): Int {
        val days = doneDays.mapNotNull { day -> runCatching { LocalDate.parse(day) }.getOrNull() }.distinct().sorted()
        var best = 0
        var run = 0
        var previous: LocalDate? = null
        for (day in days) {
            run = if (previous != null && day == previous.plusDays(1)) run + 1 else 1
            best = maxOf(best, run)
            previous = day
        }
        return best
    }

    private fun doneDaysOf(rows: AccountRows, habitUid: String): Set<String> =
        rows.checkIns[habitUid]?.filterValues { it == "done" }?.keys?.toSet() ?: emptySet()

    private class Streak(val habit: HabitRow, val current: Int, val longest: Int)

    private fun streaks(rows: AccountRows, today: String): List<Streak> =
        aliveHabits(rows).map { habit ->
            val done = doneDaysOf(rows, habit.uid)
            Streak(habit, currentStreak(done, today), longestStreak(done))
        }.sortedWith(compareByDescending<Streak> { it.current }.thenByDescending { it.longest }.thenBy { it.habit.title })

    /** data widget "streaks": each habit's current and longest streak. */
    fun streakTable(rows: AccountRows, today: String): JsonObject =
        buildJsonObject {
            put("shape", "table")
            put(
                "columns",
                buildJsonArray {
                    add(buildJsonObject { put("key", "habit"); put("label", "Habit") })
                    add(buildJsonObject { put("key", "current"); put("label", "Current streak"); put("unit", "days") })
                    add(buildJsonObject { put("key", "longest"); put("label", "Longest streak"); put("unit", "days") })
                },
            )
            put(
                "rows",
                buildJsonArray {
                    for (streak in streaks(rows, today).take(MAX_ITEMS)) {
                        add(
                            buildJsonObject {
                                put("habit", cleanText(streak.habit.title, 200, "Habit"))
                                put("current", streak.current)
                                put("longest", streak.longest)
                            },
                        )
                    }
                },
            )
        }

    /** data widget "best_streak": the longest streak of any habit. */
    fun bestStreak(rows: AccountRows, today: String): JsonObject =
        buildJsonObject {
            put("shape", "number")
            put("label", "Longest streak")
            put("unit", "days")
            put("value", streaks(rows, today).maxOfOrNull { it.longest } ?: 0)
        }

    /** The device id check-ins are written under, so clients can tell them apart. */
    private const val RESKON_DEVICE = "reskon-widget"

    /**
     * Writes today's check-in for the page widget, straight into the sync
     * tables with fresh stamps and sequence numbers, so every client sees it
     * exactly like a check-in made in the app. Returns false when the input
     * cannot be trusted.
     */
    fun writeCheckIn(file: File, habitUid: String, day: String, state: String): Boolean {
        if (!safeUid.matches(habitUid) || !isDay(day) || state != "done" && state != "skipped") return false
        file.parentFile?.mkdirs()
        val uid = "$habitUid:$day"
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { db ->
            db.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout = 5000")
                st.execute(
                    "CREATE TABLE IF NOT EXISTS sync_field (table_name TEXT NOT NULL, uid TEXT NOT NULL, field_name TEXT NOT NULL, value_json TEXT, clock INTEGER NOT NULL, device TEXT NOT NULL, seq INTEGER NOT NULL, PRIMARY KEY(table_name,uid,field_name))",
                )
                st.execute("CREATE TABLE IF NOT EXISTS sync_counter (id INTEGER PRIMARY KEY CHECK(id=1), seq INTEGER NOT NULL)")
                st.execute("INSERT OR IGNORE INTO sync_counter VALUES (1,0)")
            }
            val existing = linkedMapOf<String, String>()
            var maxClock = 0L
            db.prepareStatement("SELECT field_name, value_json, clock FROM sync_field WHERE table_name = 'check_in' AND uid = ?").use { ps ->
                ps.setString(1, uid)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        existing[rs.getString(1)] = rs.getString(2) ?: ""
                        maxClock = maxOf(maxClock, rs.getLong(3))
                    }
                }
            }
            val tombstoned = existing["_deleted"] == "true"
            val needsBase = listOf("uid", "habit_uid", "day").any { it !in existing }
            val values = linkedMapOf<String, String>()
            if (needsBase || tombstoned) {
                values["uid"] = JsonPrimitive(uid).toString()
                values["habit_uid"] = JsonPrimitive(habitUid).toString()
                values["day"] = JsonPrimitive(day).toString()
                values["_deleted"] = "false"
            }
            values["state"] = JsonPrimitive(state).toString()
            val clock = maxOf(System.currentTimeMillis(), maxClock + 1)
            db.autoCommit = false
            try {
                db.prepareStatement("UPDATE sync_counter SET seq=seq+1 WHERE id=1").use { increment ->
                    db.prepareStatement(
                        "INSERT INTO sync_field(table_name,uid,field_name,value_json,clock,device,seq) VALUES ('check_in',?,?,?,?,?,?) " +
                            "ON CONFLICT(table_name,uid,field_name) DO UPDATE SET value_json=excluded.value_json,clock=excluded.clock,device=excluded.device,seq=excluded.seq",
                    ).use { save ->
                        for ((field, value) in values) {
                            increment.executeUpdate()
                            val seq = db.createStatement().use { st ->
                                st.executeQuery("SELECT seq FROM sync_counter WHERE id=1").use { rs ->
                                    rs.next(); rs.getLong(1)
                                }
                            }
                            save.setString(1, uid)
                            save.setString(2, field)
                            save.setString(3, value)
                            save.setLong(4, clock)
                            save.setString(5, RESKON_DEVICE)
                            save.setLong(6, seq)
                            save.addBatch()
                        }
                        save.executeBatch()
                    }
                }
                db.commit()
            } catch (e: Exception) {
                db.rollback()
                throw e
            } finally {
                db.autoCommit = true
            }
        }
        return true
    }

    /** Reads one check-in state back, for tests and the page's own answer. */
    fun readCheckIn(db: Connection, habitUid: String, day: String, field: String): String? =
        db.prepareStatement("SELECT value_json FROM sync_field WHERE table_name='check_in' AND uid=? AND field_name=?").use { ps ->
            ps.setString(1, "$habitUid:$day")
            ps.setString(2, field)
            ps.executeQuery().use { rs -> if (rs.next()) rs.getString(1) else null }
        }
}
