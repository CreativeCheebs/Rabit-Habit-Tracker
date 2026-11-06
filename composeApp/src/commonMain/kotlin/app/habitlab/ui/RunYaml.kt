package app.habitlab.ui

/** One habit parsed from a run YAML file. Exactly one of [weekly] / [interval] is non-zero. */
internal data class YamlHabit(
    val name: String,
    val group: String,
    val points: Int,
    val weekly: Int,
    val interval: Int,
    val icon: String,
)

internal data class YamlRun(val name: String, val days: Int, val start: String, val habits: List<YamlHabit>, val errors: List<String>) {
    val ok: Boolean get() = errors.isEmpty()
}

internal val YAML_ICON_KEYS = listOf("sun", "moon", "target", "lines", "heart", "book", "drop", "bolt")

private class HabitDraft(var name: String = "", var group: String = "General", var points: Int = 1, var weekly: Int = 0, var interval: Int = 0, var icon: String = "", val line: Int)

private fun unquote(raw: String): String {
    val s = raw.trim()
    val quoted = s.length >= 2 && ((s.first() == '"' && s.last() == '"') || (s.first() == '\'' && s.last() == '\''))
    return if (quoted) s.substring(1, s.length - 1) else s
}

private fun isIsoDate(raw: String): Boolean {
    val m = Regex("""^(\d{4})-(\d{2})-(\d{2})$""").find(raw.trim()) ?: return false
    val (y, mo, d) = m.destructured
    val month = mo.toInt()
    val day = d.toInt()
    val year = y.toInt()
    val leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)
    val length = when (month) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    return month in 1..12 && day in 1..length
}

private fun setHabitField(h: HabitDraft, key: String, value: String, errors: MutableList<String>, lineNo: Int) {
    when (key) {
        "name" -> h.name = value
        "group" -> h.group = value.ifBlank { "General" }
        "icon" -> if (value.lowercase() in YAML_ICON_KEYS) h.icon = value.lowercase() else errors.add("line $lineNo: icon must be one of ${YAML_ICON_KEYS.joinToString(", ")}")
        "points" -> value.toIntOrNull()?.takeIf { it >= 0 }?.let { h.points = it } ?: errors.add("line $lineNo: points must be an integer >= 0")
        "days_per_week" -> value.toIntOrNull()?.takeIf { it in 1..7 }?.let { h.weekly = it } ?: errors.add("line $lineNo: days_per_week must be 1..7")
        "every" -> Regex("""^(\d+)\s*days?$""").find(value.trim().lowercase())?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it >= 1 }?.let { h.interval = it }
            ?: errors.add("line $lineNo: every must look like \"2 days\"")
        else -> errors.add("line $lineNo: unknown habit key \"$key\"")
    }
}

/** Kotlin port of the web app's yaml.js subset so a file works on both clients. */
internal fun parseRunYaml(source: String): YamlRun {
    val errors = mutableListOf<String>()
    var name = ""
    var days = 21
    var start = ""
    val drafts = mutableListOf<HabitDraft>()
    var current: HabitDraft? = null
    var inHabits = false
    source.split('\n').forEachIndexed { index, rawLine ->
        val lineNo = index + 1
        val line = rawLine.trimEnd('\r')
        if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachIndexed
        val indent = line.length - line.trimStart().length
        val body = line.trim()
        val dash = body.startsWith("- ") || body == "-"
        if (indent == 0 && !dash) {
            val colon = body.indexOf(':')
            if (colon < 0) { errors.add("line $lineNo: expected \"key: value\""); return@forEachIndexed }
            val key = body.substring(0, colon).trim().lowercase()
            val value = unquote(body.substring(colon + 1))
            if (key == "habits") { inHabits = true; current = null; return@forEachIndexed }
            inHabits = false
            when (key) {
                "name" -> name = value
                "days" -> value.toIntOrNull()?.takeIf { it in 1..365 }?.let { days = it } ?: errors.add("line $lineNo: days must be 1..365")
                "start" -> if (isIsoDate(value)) start = value.trim() else errors.add("line $lineNo: start must be YYYY-MM-DD")
                else -> errors.add("line $lineNo: unknown run key \"$key\"")
            }
            return@forEachIndexed
        }
        if (!inHabits) { errors.add("line $lineNo: habit entries belong under \"habits:\""); return@forEachIndexed }
        val field: String
        if (dash) {
            val draft = HabitDraft(line = lineNo)
            drafts.add(draft)
            current = draft
            field = if (body == "-") "" else body.substring(2).trim()
            if (field.isEmpty()) return@forEachIndexed
        } else {
            field = body
        }
        val target = current ?: run { errors.add("line $lineNo: expected \"- \" to start a habit"); return@forEachIndexed }
        val colon = field.indexOf(':')
        if (colon < 0) { errors.add("line $lineNo: expected \"key: value\""); return@forEachIndexed }
        setHabitField(target, field.substring(0, colon).trim().lowercase(), unquote(field.substring(colon + 1)), errors, lineNo)
    }
    drafts.forEachIndexed { i, h ->
        if (h.name.isBlank()) errors.add("habit ${i + 1} (line ${h.line}): missing name")
        if (h.weekly > 0 && h.interval > 0) errors.add("habit ${i + 1}: use either days_per_week or every, not both")
        if (h.weekly == 0 && h.interval == 0) h.interval = 1
    }
    if (drafts.isEmpty() && errors.isEmpty()) errors.add("no habits found under \"habits:\"")
    return YamlRun(name, days, start, drafts.map { YamlHabit(it.name, it.group, it.points, it.weekly, it.interval, it.icon) }, errors)
}

/** Paste into any AI chat; it replies with a run file the builder can load. */
internal val YAML_GUIDE_PROMPT: String = listOf(
    "You are helping configure a habit run for Habit Lab, a habit tracker.",
    "Output ONLY a YAML run file, no explanations. The user will paste it into the app.",
    "",
    "FORMAT (2-space indent, \"# \" comments allowed):",
    "  name: <run name, optional, default \"<days>-day run\">",
    "  days: <run length 1..365, optional, default 21>",
    "  start: <YYYY-MM-DD start date, optional, default today>",
    "  habits:   # required, at least one",
    "    - name: <habit name, required>",
    "      group: <group label, optional, default General>",
    "      points: <impact points per completion, integer >= 0, optional, default 1>",
    "      icon: <optional, one of ${YAML_ICON_KEYS.joinToString(", ")}>",
    "      days_per_week: <1..7, weekly target the user hits on any days>",
    "      every: <N days, e.g. \"2 days\"; repeat interval, 1 means daily>",
    "  Give each habit EXACTLY ONE of days_per_week / every.",
    "  daily habit -> every: 1 day.  weekdays habit -> days_per_week: 5.",
    "",
    "RULES THE APP ENFORCES (respect them when choosing schedules):",
    "- An \"every N days\" habit stays due until done: if missed today it is",
    "  still due tomorrow. After a Done it hides for N-1 days, then is due again.",
    "- A \"days_per_week\" habit is due every day until done that day.",
    "- Only Done earns impact points. Partial and Skipped earn nothing.",
    "- Keep 2-6 habits per run, 1-4 points each. Ask the user if unclear.",
    "",
    "Ask the user which habits they want (name, how often, group, points),",
    "then output ONLY the YAML file.",
).joinToString("\n")
