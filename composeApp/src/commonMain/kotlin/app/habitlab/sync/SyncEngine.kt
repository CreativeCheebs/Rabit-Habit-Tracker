package app.habitlab.sync

import app.habitlab.db.HabitDatabase
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull

data class FieldStamp(
    val clock: Long,
    val device: String,
)

data class SyncChange(
    val table: String,
    val uid: String,
    val values: Map<String, kotlinx.serialization.json.JsonElement>,
    val stamps: Map<String, FieldStamp>,
)

data class ExportResult(
    val changes: List<SyncChange>,
    val upto: Long,
)

private enum class ColType { TEXT, INT, REAL }

private data class Col(
    val name: String,
    val type: ColType,
    val nullable: Boolean = false,
)

private fun Col.orDefault(value: Any?): Any? = value ?: if (nullable) null else defaultFor(this)

private val tableCols: Map<String, List<Col>> =
    mapOf(
        "habit" to
            listOf(
                Col("uid", ColType.TEXT),
                Col("title", ColType.TEXT),
                Col("group_name", ColType.TEXT),
                Col("iteration", ColType.INT),
                Col("impact_points", ColType.INT),
                Col("recurrence_type", ColType.TEXT),
                Col("recurrence_value", ColType.INT),
                Col("archived", ColType.INT),
            ),
        "run" to
            listOf(
                Col("uid", ColType.TEXT),
                Col("name", ColType.TEXT),
                Col("start_date", ColType.TEXT),
                Col("duration_days", ColType.INT),
                Col("status", ColType.TEXT),
                Col("copied_from_uid", ColType.TEXT, nullable = true),
            ),
        "goal" to
            listOf(
                Col("uid", ColType.TEXT),
                Col("description", ColType.TEXT),
                Col("start_date", ColType.TEXT),
                Col("target_date", ColType.TEXT, nullable = true),
                Col("progress", ColType.REAL),
            ),
        "tracker" to
            listOf(
                Col("uid", ColType.TEXT),
                Col("name", ColType.TEXT),
                Col("unit", ColType.TEXT),
                Col("cadence", ColType.TEXT),
            ),
        "run_habit" to listOf(Col("uid", ColType.TEXT), Col("run_uid", ColType.TEXT), Col("habit_uid", ColType.TEXT)),
        "check_in" to
            listOf(
                Col("uid", ColType.TEXT),
                Col("habit_uid", ColType.TEXT),
                Col("day", ColType.TEXT),
                Col("state", ColType.TEXT),
                Col("comment", ColType.TEXT, nullable = true),
            ),
        "tracker_entry" to
            listOf(
                Col("uid", ColType.TEXT),
                Col("tracker_uid", ColType.TEXT),
                Col("recorded_at", ColType.TEXT),
                Col("value", ColType.REAL),
                Col("note", ColType.TEXT, nullable = true),
            ),
    )

/** Parents before children so inserts never dangle. */
private val tableOrder = listOf("habit", "run", "goal", "tracker", "run_habit", "check_in", "tracker_entry")

/**
 * Field-level last-writer-wins: higher clock wins, then higher device id, then
 * greater value so every side picks the same winner. A field nobody here has a
 * stamp for is accepted (the sender wins).
 */
fun fieldWins(
    inClock: Long,
    inDevice: String,
    inValue: String,
    current: FieldStamp?,
    currentValue: String?,
): Boolean {
    if (current == null) return true
    if (inClock != current.clock) return inClock > current.clock
    if (inDevice != current.device) return inDevice > current.device
    return inValue > (currentValue ?: "")
}

private fun HabitDatabase.meta(key: String): String? = schemaQueries.selectMetaValue(key).executeAsOneOrNull()

private fun HabitDatabase.setMeta(
    key: String,
    value: String,
) = schemaQueries.upsertMetaValue(key, value)

private fun encode(
    col: Col,
    value: Any?,
): kotlinx.serialization.json.JsonElement =
    when {
        value == null -> JsonNull
        col.type == ColType.TEXT -> JsonPrimitive(value as String)
        col.type == ColType.INT -> JsonPrimitive(value as Long)
        else -> JsonPrimitive(value as Double)
    }

private fun decode(
    col: Col,
    element: kotlinx.serialization.json.JsonElement?,
): Any? {
    if (element == null || element is JsonNull) return null
    val primitive = element.jsonPrimitive
    return when (col.type) {
        ColType.TEXT -> primitive.content
        ColType.INT -> primitive.longOrNull ?: primitive.content.toLongOrNull()
        ColType.REAL -> primitive.doubleOrNull ?: primitive.content.toDoubleOrNull()
    }
}

private fun defaultFor(col: Col): Any? =
    when (col.type) {
        ColType.TEXT -> ""
        ColType.INT -> 0L
        ColType.REAL -> 0.0
    }

internal fun HabitDatabase.readRowValues(
    table: String,
    uid: String,
): Map<String, kotlinx.serialization.json.JsonElement>? {
    val cols = tableCols[table] ?: return null

    fun build(values: List<Any?>): Map<String, kotlinx.serialization.json.JsonElement> =
        cols.mapIndexed { index, col -> col.name to encode(col, values[index]) }.toMap()
    return when (table) {
        "habit" ->
            schemaQueries.selectHabitByUid(uid).executeAsOneOrNull()?.let {
                build(
                    listOf(
                        it.uid,
                        it.title,
                        it.group_name,
                        it.iteration,
                        it.impact_points,
                        it.recurrence_type,
                        it.recurrence_value,
                        it.archived,
                    ),
                )
            }
        "run" ->
            schemaQueries.selectRunByUid(uid).executeAsOneOrNull()?.let {
                build(listOf(it.uid, it.name, it.start_date, it.duration_days, it.status, it.copied_from_uid))
            }
        "run_habit" ->
            schemaQueries.selectRunHabitByUid(uid).executeAsOneOrNull()?.let {
                build(listOf(it.uid, it.run_uid, it.habit_uid))
            }
        "check_in" ->
            schemaQueries.selectCheckInByUid(uid).executeAsOneOrNull()?.let {
                build(listOf(it.uid, it.habit_uid, it.day, it.state, it.comment))
            }
        "goal" ->
            schemaQueries.selectGoalByUid(uid).executeAsOneOrNull()?.let {
                build(listOf(it.uid, it.description, it.start_date, it.target_date, it.progress))
            }
        "tracker" ->
            schemaQueries.selectTrackerByUid(uid).executeAsOneOrNull()?.let {
                build(listOf(it.uid, it.name, it.unit, it.cadence))
            }
        "tracker_entry" ->
            schemaQueries.selectTrackerEntryByUid(uid).executeAsOneOrNull()?.let {
                build(listOf(it.uid, it.tracker_uid, it.recorded_at, it.value_, it.note))
            }
        else -> null
    }
}

private fun HabitDatabase.writeRowValues(
    table: String,
    values: Map<String, Any?>,
) {
    fun arg(name: String): Any? = values[name]
    when (table) {
        "habit" ->
            schemaQueries.upsertHabitRow(
                arg("uid") as String,
                arg("title") as String,
                arg("group_name") as String,
                arg("iteration") as Long,
                arg("impact_points") as Long,
                arg("recurrence_type") as String,
                arg("recurrence_value") as Long,
                arg("archived") as Long,
            )
        "run" ->
            schemaQueries.upsertRunRow(
                arg("uid") as String,
                arg("name") as String,
                arg("start_date") as String,
                arg("duration_days") as Long,
                arg("status") as String,
                arg("copied_from_uid") as String?,
            )
        "run_habit" -> schemaQueries.upsertRunHabitRow(arg("uid") as String, arg("run_uid") as String, arg("habit_uid") as String)
        "check_in" ->
            schemaQueries.upsertCheckInRow(
                arg("uid") as String,
                arg("habit_uid") as String,
                arg("day") as String,
                arg("state") as String,
                arg("comment") as String?,
            )
        "goal" ->
            schemaQueries.upsertGoalRow(
                arg("uid") as String,
                arg("description") as String,
                arg("start_date") as String,
                arg("target_date") as String?,
                arg("progress") as Double,
            )
        "tracker" ->
            schemaQueries.upsertTrackerRow(
                arg("uid") as String,
                arg("name") as String,
                arg("unit") as String,
                arg("cadence") as String,
            )
        "tracker_entry" ->
            schemaQueries.upsertTrackerEntryRow(
                arg("uid") as String,
                arg("tracker_uid") as String,
                arg("recorded_at") as String,
                arg("value") as Double,
                arg("note") as String?,
            )
    }
}

private fun HabitDatabase.deleteRow(
    table: String,
    uid: String,
) {
    when (table) {
        "habit" -> schemaQueries.deleteHabitRow(uid)
        "run" -> schemaQueries.deleteRunRow(uid)
        "run_habit" -> schemaQueries.deleteRunHabitRow(uid)
        "check_in" -> schemaQueries.deleteCheckInRow(uid)
        "goal" -> schemaQueries.deleteGoalRow(uid)
        "tracker" -> schemaQueries.deleteTrackerRow(uid)
        "tracker_entry" -> schemaQueries.deleteTrackerEntryRow(uid)
    }
}

private fun HabitDatabase.knownStamps(
    table: String,
    uid: String,
): Map<String, FieldStamp> =
    schemaQueries
        .selectFieldStamps(table, uid)
        .executeAsList()
        .associate { it.field_name to FieldStamp(it.clock, it.device) }

private fun HabitDatabase.storeStamp(
    table: String,
    uid: String,
    field: String,
    stamp: FieldStamp,
) = schemaQueries.upsertFieldStamp(table, uid, field, stamp.clock, stamp.device, 0)

/**
 * Everything this device changed since [pushed], with full current row values
 * and all known stamps per row. Call [markExported] with [ExportResult.upto]
 * only after the other side has acknowledged the changes.
 */
fun exportChanges(db: HabitDatabase): ExportResult {
    val device = db.meta("device") ?: error("sync_meta.device is missing")
    val pushed = db.meta("pushed")?.toLongOrNull() ?: 0L
    val upto = db.meta("seq")?.toLongOrNull() ?: 0L
    val touched =
        db.schemaQueries
            .selectChangedFields(pushed, device)
            .executeAsList()
            .map { it.table_name to it.uid }
            .distinct()
    val changes =
        touched.mapNotNull { (table, uid) ->
            if (table !in tableCols) return@mapNotNull null
            val current = db.readRowValues(table, uid)
            val known = db.knownStamps(table, uid)
            if (current == null) {
                val tombstone = known["_deleted"] ?: return@mapNotNull null
                SyncChange(table, uid, mapOf("_deleted" to JsonPrimitive(true)), mapOf("_deleted" to tombstone))
            } else {
                // A live row is definitionally not deleted: a stale tombstone
                // stamp from an earlier delete must never travel with it. `_row`
                // stays so the other side can stamp fields that have no
                // per-field stamp yet.
                SyncChange(table, uid, current, known.filterKeys { it != "_deleted" })
            }
        }
    return ExportResult(changes, upto)
}

fun markExported(
    db: HabitDatabase,
    upto: Long,
) {
    db.setMeta("pushed", upto.toString())
}

private fun isTombstone(values: Map<String, kotlinx.serialization.json.JsonElement>): Boolean =
    (values["_deleted"] as? JsonPrimitive)?.booleanOrNull == true

/**
 * Applies incoming rows with the guide's merge rules: unknown rows are
 * inserted, known rows merge field by field with last-writer-wins, incoming
 * tombstones delete, and a locally deleted row stays deleted. Runs inside one
 * transaction with the change triggers muted, and advances the local clock to
 * the largest stamp seen.
 */
fun applyChanges(
    db: HabitDatabase,
    changes: List<SyncChange>,
) {
    db.transaction {
        db.setMeta("applying", "1")
        try {
            var maxClock = db.meta("clock")?.toLongOrNull() ?: 0L
            for (table in tableOrder) {
                for (change in changes.filter { it.table == table }) {
                    val cols = tableCols[table] ?: continue
                    for (stamp in change.stamps.values) {
                        if (stamp.clock > maxClock) maxClock = stamp.clock
                    }
                    val local = db.readRowValues(table, change.uid)
                    val localStamps = db.knownStamps(table, change.uid)
                    if (local == null) {
                        if (localStamps["_deleted"] != null) {
                            // Deleted here: the delete wins, skip incoming edits.
                            val incomingTombstone = change.stamps["_deleted"]
                            val current = localStamps["_deleted"]
                            if (incomingTombstone != null &&
                                current != null &&
                                fieldWins(incomingTombstone.clock, incomingTombstone.device, "true", current, "true")
                            ) {
                                db.storeStamp(table, change.uid, "_deleted", incomingTombstone)
                            }
                            continue
                        }
                        if (!isTombstone(change.values)) {
                            val filled =
                                cols.associate { col ->
                                    col.name to col.orDefault(decode(col, change.values[col.name]))
                                }
                            db.writeRowValues(table, filled)
                        }
                        change.stamps.forEach { (field, stamp) ->
                            if (field != "_row") db.storeStamp(table, change.uid, field, stamp)
                        }
                    } else {
                        if (isTombstone(change.values)) {
                            db.deleteRow(table, change.uid)
                            change.stamps["_deleted"]?.let { db.storeStamp(table, change.uid, "_deleted", it) }
                            continue
                        }
                        var dirty = false
                        val merged =
                            local.mapValues { (field, element) ->
                                val col = cols.first { it.name == field }
                                val stamp = change.stamps[field]
                                if (stamp == null) return@mapValues col.orDefault(decode(col, element))
                                val incoming = change.values[field] ?: JsonNull
                                if (fieldWins(stamp.clock, stamp.device, incoming.toString(), localStamps[field], element.toString())) {
                                    db.storeStamp(table, change.uid, field, stamp)
                                    dirty = true
                                    col.orDefault(decode(col, incoming))
                                } else {
                                    col.orDefault(decode(col, element))
                                }
                            }
                        if (dirty) db.writeRowValues(table, merged)
                    }
                }
            }
            db.setMeta("clock", maxClock.toString())
        } finally {
            db.setMeta("applying", "0")
        }
    }
}

fun SyncChange.toWire(): JsonObject =
    JsonObject(
        mapOf(
            "t" to JsonPrimitive(table),
            "u" to JsonPrimitive(uid),
            "v" to JsonObject(values),
            "s" to
                JsonObject(
                    stamps.mapValues { JsonObject(mapOf("c" to JsonPrimitive(it.value.clock), "d" to JsonPrimitive(it.value.device))) },
                ),
        ),
    )

fun syncChangeFromWire(row: JsonObject): SyncChange {
    val stamps =
        (row["s"] as? JsonObject)
            ?.mapValues { (_, element) ->
                val obj = element as JsonObject
                FieldStamp((obj["c"] as JsonPrimitive).long, (obj["d"] as JsonPrimitive).content)
            }.orEmpty()
    return SyncChange(
        table = (row["t"] as JsonPrimitive).content,
        uid = (row["u"] as JsonPrimitive).content,
        values = (row["v"] as? JsonObject)?.toMap().orEmpty(),
        stamps = stamps,
    )
}
