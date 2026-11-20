package app.habitlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.habitlab.db.HabitDatabase
import kotlin.random.Random

/** Where a new run's habits come from. */
internal const val SOURCE_COPY = 0
internal const val SOURCE_BLANK = 1
internal const val SOURCE_YAML = 2

private val LENGTH_CHOICES = listOf(7, 14, 21)

internal fun newHabitId(): String = "habit-${Random.nextLong().toULong().toString(16)}"

private fun recurrenceCount(recurrence: String): Int = Regex("\\d+").find(recurrence)?.value?.toIntOrNull() ?: 1

/** "Thursday, 20 Nov 2026". */
private fun startLabel(date: String): String =
    if (isValidRunDate(date)) "${longDateLabel(date)} ${date.substring(0, 4)}" else "Pick a start date"

@Composable private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
        color = muted,
    )
}

@Composable internal fun editorFieldColors() =
    OutlinedTextFieldDefaults.colors(
        focusedBorderColor = palette.accent,
        unfocusedBorderColor = palette.field,
        focusedContainerColor = palette.bg,
        unfocusedContainerColor = palette.bg,
        cursorColor = palette.accent,
    )

internal val FIELD_SHAPE = RoundedCornerShape(14.dp)

/**
 * The one run editor for New and Edit. New: pick a source (copy a run, blank, YAML). Edit: run stats.
 * Both: basics, the habit list (habit sheet, add existing) and a sticky Cancel / Create-or-Save bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RunBuilderPage(
    database: HabitDatabase,
    today: String,
    startWithCopy: Boolean,
    editRunUid: String?,
    initialSource: Int? = null,
    onCreated: () -> Unit,
    onRunsChanged: () -> Unit,
    onDismiss: () -> Unit,
) {
    val editing = editRunUid != null
    var localRevision by remember { mutableIntStateOf(0) }
    val allRuns = remember(database, localRevision) { database.schemaQueries.selectRuns().executeAsList() }
    val editRun = remember(database, editRunUid) { editRunUid?.let { database.schemaQueries.selectRunByUid(it).executeAsOneOrNull() } }
    val copyableRuns =
        remember(database, localRevision) {
            val current = database.currentRun()?.uid
            val withHabits =
                allRuns.filter {
                    database.schemaQueries
                        .selectRunLinks(it.uid)
                        .executeAsList()
                        .isNotEmpty()
                }
            withHabits.filter { it.uid == current } + withHabits.filter { it.uid != current }
        }
    var copyFromUid by remember { mutableStateOf(copyableRuns.firstOrNull()?.uid) }
    val hasPrevious = copyFromUid != null
    val standaloneHabits = remember(database) { loadStandaloneHabits(database) }
    var includeStandalone by remember { mutableStateOf(false) }
    val startSource =
        when {
            editing -> SOURCE_BLANK
            initialSource != null -> if (initialSource == SOURCE_COPY && !hasPrevious) SOURCE_BLANK else initialSource
            startWithCopy && hasPrevious -> SOURCE_COPY
            else -> SOURCE_BLANK
        }
    var source by remember { mutableIntStateOf(startSource) }
    val drafts =
        remember {
            mutableStateListOf<Habit>().apply {
                if (editing) {
                    addAll(loadRunHabits(database, editRunUid!!))
                } else if (startSource == SOURCE_COPY) {
                    addAll(loadRunHabits(database, copyFromUid!!))
                }
            }
        }
    val copyRunDays = allRuns.firstOrNull { it.uid == copyFromUid }?.duration_days?.toInt()
    val initialDays =
        when {
            editing -> editRun?.duration_days?.toInt() ?: 21
            startSource == SOURCE_COPY -> copyRunDays ?: 21
            else -> 21
        }
    var name by remember { mutableStateOf(editRun?.name.orEmpty()) }
    var startDate by remember { mutableStateOf(editRun?.start_date ?: today) }
    var showStartPicker by remember { mutableStateOf(false) }
    var duration by remember { mutableIntStateOf(if (initialDays in LENGTH_CHOICES) initialDays else 21) }
    var custom by remember { mutableStateOf(initialDays !in LENGTH_CHOICES) }
    var customDays by remember { mutableStateOf(if (custom) initialDays.toString() else "28") }
    var yamlText by remember { mutableStateOf("") }
    var yamlErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var builderError by remember { mutableStateOf<String?>(null) }
    var promptCopied by remember { mutableStateOf(false) }
    var yamlCopied by remember { mutableStateOf(false) }
    var sheetOpen by remember { mutableStateOf(false) }
    var sheetHabit by remember { mutableStateOf<Habit?>(null) }
    var addExistingOpen by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf<app.habitlab.db.Run?>(null) }
    var copyMenuOpen by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val activeNow = remember(database, localRevision, today) { if (editing) emptyList() else database.activeRuns(today) }
    val days = if (custom) customDays.toIntOrNull()?.coerceIn(1, 365) ?: 21 else duration
    val duplicate = duplicateHabitTitle(drafts.map { it.name })
    val duplicateKey = duplicate?.let { normalizedTitle(it) }

    fun applyLength(value: Int) {
        custom = value !in LENGTH_CHOICES
        if (custom) customDays = value.toString() else duration = value
    }

    fun loadCopy() {
        drafts.clear()
        copyFromUid?.let { drafts.addAll(loadRunHabits(database, it)) }
        if (includeStandalone) drafts.addAll(standaloneHabits.filter { s -> drafts.none { it.uid() == s.uid() } })
        allRuns.firstOrNull { it.uid == copyFromUid }?.let { applyLength(it.duration_days.toInt()) }
    }

    fun chooseSource(next: Int) {
        if (next == source) return
        source = next
        builderError = null
        drafts.clear()
        if (next == SOURCE_COPY) loadCopy()
    }

    fun loadYaml() {
        val parsed = parseRunYaml(yamlText)
        if (!parsed.ok) {
            yamlErrors = parsed.errors.take(5)
            return
        }
        name = parsed.name
        if (parsed.start.isNotEmpty()) startDate = parsed.start
        applyLength(parsed.days)
        drafts.clear()
        parsed.habits.forEach { y ->
            val recurrence = if (y.weekly > 0) "${y.weekly} days per week" else "Every ${y.interval} days"
            drafts.add(Habit(y.name, y.group, y.points, recurrence, "I", newHabitId(), y.icon))
        }
        yamlErrors = emptyList()
        source = SOURCE_BLANK
    }

    fun copyYaml() {
        val values =
            drafts.map { h ->
                val n = recurrenceCount(h.recurrence)
                val every = h.recurrence.startsWith("Every ")
                YamlHabit(h.name, h.group, h.points, if (every) 0 else n, if (every) n else 0, h.icon)
            }
        clipboard.setText(AnnotatedString(exportRunYaml(name, days, startDate, values)))
        yamlCopied = true
    }

    fun save() {
        if (!editing && database.activeRuns(today).isNotEmpty()) {
            builderError = "Stop all active runs before starting a new run."
            return
        }
        val duplicateName = duplicateHabitTitle(drafts.map { it.name })
        if (duplicateName != null) {
            builderError = "Habit ‘$duplicateName’ appears more than once. Remove or rename the duplicate."
            return
        }
        if (!isValidRunDate(startDate)) {
            builderError = "Pick a valid start date."
            return
        }
        val runDays = days.toLong()
        val runName = name.ifBlank { "$runDays-day run" }
        val runId = if (editing) editRun!!.uid else "run-$startDate-${Random.nextLong().toULong().toString(16)}"
        database.transaction {
            if (editing) {
                database.schemaQueries.updateRunDetails(runName, startDate, runDays, runId)
            } else {
                database.activeRuns().forEach { database.schemaQueries.stopRun(it.uid) }
                database.schemaQueries.insertRun(runId, runName, startDate, runDays)
            }
            val oldLinks = if (editing) database.schemaQueries.selectRunLinks(runId).executeAsList() else emptyList()
            val keep = drafts.map { it.uid() }.toSet()
            oldLinks.filter { it.habit_uid !in keep }.forEach { database.schemaQueries.deleteRunHabitRow(it.uid) }
            drafts.forEach { habit ->
                val count = recurrenceCount(habit.recurrence).toLong()
                val type = if (habit.recurrence.startsWith("Every ")) "interval" else "weekly"
                // Existing habits (this run's, a copied run's, attached or standalone) are edited in place for every run that uses them.
                if (database.schemaQueries.selectHabitByUid(habit.uid()).executeAsOneOrNull() != null) {
                    database.schemaQueries.updateHabitDetails(habit.name, habit.group, habit.points.toLong(), type, count, habit.uid())
                } else {
                    database.schemaQueries.insertHabit(habit.uid(), habit.name, habit.group, 1L, habit.points.toLong(), type, count)
                }
                database.setHabitIcon(habit.uid(), habit.icon)
                database.setHabitColor(habit.uid(), habit.color)
            }
            drafts
                .filter { h -> oldLinks.none { it.habit_uid == h.uid() } }
                .forEach {
                    database.schemaQueries.attachHabitToRun(
                        "$runId:${it.uid()}:${Random.nextLong().toULong().toString(16)}",
                        runId,
                        it.uid(),
                    )
                }
            database.setPref(PREF_CURRENT_RUN, runId)
        }
        onCreated()
        onDismiss()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(60.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIconButton(LinePaths.BACK, "Back", onClick = onDismiss)
            Text(
                if (editing) "Edit run" else "New run",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            )
            RoundIconButton(LinePaths.DOWNLOAD, "Copy run as YAML", onClick = { copyYaml() }, enabled = drafts.isNotEmpty())
        }
        if (yamlCopied) {
            Text(
                "Run YAML copied to the clipboard.",
                color = muted,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
        }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            PreviewCard(database, editRun, name, days, startDate, today, drafts)
            if (editing && editRun != null) {
                EditStats(database, editRun, today)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Start from",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 4.dp).semantics { heading() },
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val green = palette.habitTone("green")
                        val blue = palette.habitTone("blue")
                        val purple = palette.habitTone("purple")
                        val copySub =
                            allRuns.firstOrNull { it.uid == copyFromUid }?.let { run ->
                                "${run.name} · ${database.schemaQueries.selectRunLinks(run.uid).executeAsList().size} habits"
                            } ?: "No runs to copy yet"
                        SourceCard(
                            "Copy a run",
                            copySub,
                            LinePaths.COPY,
                            green.tint,
                            green.ink,
                            source == SOURCE_COPY,
                            onClick = { chooseSource(SOURCE_COPY) },
                            modifier = Modifier.weight(1f),
                            enabled = hasPrevious,
                        )
                        SourceCard(
                            "Blank",
                            "Pick habits yourself",
                            LinePaths.BLANK,
                            blue.tint,
                            blue.ink,
                            source == SOURCE_BLANK,
                            onClick = { chooseSource(SOURCE_BLANK) },
                            modifier = Modifier.weight(1f),
                        )
                        SourceCard(
                            "Paste YAML",
                            "Plan it with an AI chat",
                            LinePaths.CODE,
                            purple.tint,
                            purple.ink,
                            source == SOURCE_YAML,
                            onClick = { chooseSource(SOURCE_YAML) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    InfoLine(
                        when (source) {
                            SOURCE_COPY -> "Copied habits stay linked to the original run — editing one here updates both."
                            SOURCE_BLANK -> "Start empty, then add new habits or attach ones you already have."
                            else -> "Ask an AI chat for a run file, paste it below, then fine-tune it here."
                        } + " Switching clears the habits below.",
                    )
                    if (source == SOURCE_COPY && hasPrevious) {
                        SectionCard(radius = 18.dp, padding = 14.dp) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    FieldLabel("Copy from")
                                    Box(Modifier.weight(1f)) {
                                        val chosen = allRuns.firstOrNull { it.uid == copyFromUid }
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .heightIn(min = 48.dp)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(palette.bg)
                                                .border(1.5.dp, palette.field, RoundedCornerShape(12.dp))
                                                .clickable(role = Role.DropdownList, onClickLabel = "Choose a run to copy") {
                                                    copyMenuOpen =
                                                        true
                                                }.padding(horizontal = 12.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                chosen?.let { "${it.name} · ${it.duration_days} days" }.orEmpty(),
                                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp),
                                                modifier = Modifier.weight(1f),
                                                maxLines = 1,
                                            )
                                            LineIcon(LinePaths.CHEVRON_DOWN, muted, size = 16.dp)
                                        }
                                        DropdownMenu(expanded = copyMenuOpen, onDismissRequest = { copyMenuOpen = false }) {
                                            copyableRuns.forEach { run ->
                                                DropdownMenuItem(
                                                    text = { Text("${run.name} · ${run.duration_days} days") },
                                                    onClick = {
                                                        copyMenuOpen = false
                                                        if (run.uid != copyFromUid) {
                                                            copyFromUid = run.uid
                                                            loadCopy()
                                                        }
                                                    },
                                                )
                                            }
                                        }
                                    }
                                }
                                if (standaloneHabits.isNotEmpty()) {
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .heightIn(min = 48.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .clickable(role = Role.Checkbox) {
                                                includeStandalone = !includeStandalone
                                                val ids = standaloneHabits.map { it.uid() }.toSet()
                                                if (includeStandalone) {
                                                    drafts.addAll(standaloneHabits.filter { s -> drafts.none { it.uid() == s.uid() } })
                                                } else {
                                                    drafts.removeAll { it.uid() in ids }
                                                }
                                            },
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Checkbox(
                                            checked = includeStandalone,
                                            onCheckedChange = null,
                                            colors =
                                                CheckboxDefaults.colors(
                                                    checkedColor = palette.accent,
                                                    checkmarkColor = palette.onAccent,
                                                ),
                                        )
                                        Text(
                                            "Also include standalone habits (${standaloneHabits.size})",
                                            style =
                                                MaterialTheme.typography.bodyMedium.copy(
                                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                                ),
                                            modifier = Modifier.padding(start = 8.dp),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            if (!editing && activeNow.isNotEmpty()) {
                val active = activeNow.first()
                Banner(palette.warnBg, palette.warnIcon) {
                    Text(
                        "${active.name} is still running. Stop it before you create a new run.",
                        color = palette.warnInk,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                    )
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(palette.surface)
                            .clickable(role = Role.Button) { confirmStop = active }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Stop ${active.name}", color = palette.warnInk, style = MaterialTheme.typography.labelLarge) }
                }
            }
            if (!editing && source == SOURCE_YAML) {
                SectionCard {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("1 · Copy the prompt into any AI chat", style = MaterialTheme.typography.titleSmall)
                        Text(
                            YAML_GUIDE_PROMPT.lines().take(3).joinToString("\n") + "\n…",
                            color = muted,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 4,
                        )
                        Box(
                            Modifier
                                .heightIn(min = 44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(palette.accentSoft)
                                .clickable(role = Role.Button) {
                                    clipboard.setText(AnnotatedString(YAML_GUIDE_PROMPT))
                                    promptCopied = true
                                }.padding(horizontal = 14.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                if (promptCopied) "Copied ✓" else "Copy AI prompt",
                                color = palette.accent,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Text("2 · Paste the answer", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                        OutlinedTextField(
                            value = yamlText,
                            onValueChange = {
                                yamlText = it
                                yamlErrors = emptyList()
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).semantics { contentDescription = "Run YAML" },
                            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = ink),
                            placeholder = {
                                Text(
                                    "name: Reset week\ndays: 14\nhabits:\n  - name: Morning light\n    every: 1 day",
                                    fontFamily = FontFamily.Monospace,
                                )
                            },
                            shape = FIELD_SHAPE,
                            colors = editorFieldColors(),
                        )
                        yamlErrors.forEach { Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall) }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(FIELD_SHAPE)
                                .background(if (yamlText.isNotBlank()) ink else palette.track)
                                .clickable(enabled = yamlText.isNotBlank(), role = Role.Button) { loadYaml() },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                "Load into editor",
                                color = if (yamlText.isNotBlank()) palette.bg else muted,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
            }
            SectionCard {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Run name")
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            singleLine = true,
                            placeholder = { Text("$days-day run") },
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Run name" },
                            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            shape = FIELD_SHAPE,
                            colors = editorFieldColors(),
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        FieldLabel("Starts")
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(FIELD_SHAPE)
                                .background(palette.bg)
                                .border(1.5.dp, if (isValidRunDate(startDate)) palette.field else palette.error, FIELD_SHAPE)
                                .clickable(role = Role.Button, onClickLabel = "Change start date") { showStartPicker = true }
                                .semantics { contentDescription = "Start date, ${startLabel(startDate)}" }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            LineIcon(LinePaths.CALENDAR, muted, size = 18.dp)
                            Text(
                                startLabel(startDate),
                                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FieldLabel("Length")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            LENGTH_CHOICES.forEach { d ->
                                ChoicePill("$d days", !custom && duration == d, onClick = {
                                    duration = d
                                    custom = false
                                })
                            }
                            ChoicePill("Custom", custom, onClick = { custom = true })
                        }
                        if (custom) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = customDays,
                                    onValueChange = { customDays = it.filter(Char::isDigit).take(3) },
                                    singleLine = true,
                                    modifier = Modifier.width(96.dp).semantics { contentDescription = "Custom length in days" },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    isError = customDays.toIntOrNull()?.let { it !in 1..365 } ?: true,
                                    shape = RoundedCornerShape(12.dp),
                                    colors = editorFieldColors(),
                                )
                                Text("days (1–365)", color = muted, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Habits · ${drafts.size}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Box(
                        Modifier
                            .heightIn(min = 40.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(palette.accentSoft)
                            .clickable(role = Role.Button) { addExistingOpen = true }
                            .padding(horizontal = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("Add existing", color = palette.accent, style = MaterialTheme.typography.labelLarge) }
                }
                if (duplicate != null) {
                    Banner(palette.errorSoft, palette.error) {
                        Text(
                            "‘$duplicate’ appears more than once. Rename or remove one of them.",
                            color = palette.error,
                            style =
                                MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                                ),
                        )
                    }
                }
                if (drafts.isEmpty()) {
                    Text(
                        "No habits yet. Add a new one, or attach habits you already have.",
                        color = muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(surface)
                            .padding(vertical = 4.dp),
                    ) {
                        drafts.forEachIndexed { index, habit ->
                            if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
                            EditorHabitRow(habit, flagged = duplicateKey != null && normalizedTitle(habit.name) == duplicateKey) {
                                sheetHabit = habit
                                sheetOpen = true
                            }
                        }
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .dashedBorder(palette.field, 18.dp, 2.dp)
                        .clickable(role = Role.Button) {
                            sheetHabit = null
                            sheetOpen = true
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LineIcon(LinePaths.PLUS, palette.accent, size = 18.dp, strokeWidth = 2.6f)
                    Spacer(Modifier.width(8.dp))
                    Text("New habit", color = palette.accent, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.5.sp))
                }
            }
        }
        HorizontalDivider(color = palette.divider)
        Column(Modifier.fillMaxWidth().background(surface).padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp)) {
            builderError?.let {
                Text(it, color = palette.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(bottom = 8.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .heightIn(min = 52.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(role = Role.Button, onClick = onDismiss)
                        .padding(horizontal = 18.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Cancel", color = palette.accent, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp)) }
                PrimaryButton(
                    if (editing) "Save changes" else "Create run",
                    onClick = { save() },
                    enabled = drafts.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    if (showStartPicker) {
        StartDatePickerDialog(
            initial = startDate,
            fallback = today,
            onPick = {
                startDate = it
                builderError = null
                showStartPicker = false
            },
            onDismiss = { showStartPicker = false },
        )
    }
    if (sheetOpen) {
        val editingHabit = sheetHabit
        val groups =
            remember(drafts.toList()) {
                (
                    drafts.map { it.group } +
                        database.schemaQueries
                            .selectHabits()
                            .executeAsList()
                            .map { it.group_name } + "General"
                ).filter { it.isNotBlank() }
                    .distinct()
            }
        HabitSheet(
            initial = editingHabit,
            groups = groups,
            onDone = { habit ->
                val index = drafts.indexOfFirst { it.uid() == habit.uid() }
                if (index >= 0) drafts[index] = habit else drafts.add(habit)
                builderError = null
                sheetOpen = false
            },
            onRemove =
                editingHabit?.let { h ->
                    {
                        drafts.removeAll { it.uid() == h.uid() }
                        sheetOpen = false
                    }
                },
            onDismiss = { sheetOpen = false },
        )
    }
    if (addExistingOpen) {
        AddExistingSheet(
            database = database,
            exclude = drafts.map { it.uid() }.toSet(),
            onAdd = { drafts.add(it) },
            onDismiss = { addExistingOpen = false },
        )
    }
    confirmStop?.let { run ->
        AlertDialog(
            onDismissRequest = { confirmStop = null },
            title = { Text("Stop run?") },
            text = { Text("Stop ${run.name}? Its habits and check-ins will be kept.") },
            confirmButton = {
                TextButton(onClick = {
                    database.schemaQueries.stopRun(run.uid)
                    confirmStop = null
                    builderError = null
                    localRevision++
                    onRunsChanged()
                }) { Text("Stop") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = null }) { Text("Cancel") } },
        )
    }
}

private fun normalizedTitle(name: String): String = name.trim().replace(Regex("\\s+"), " ").lowercase()

@Composable private fun InfoLine(text: String) {
    Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LineIcon(LinePaths.INFO, muted, size = 16.dp, modifier = Modifier.padding(top = 1.dp))
        Text(text, color = muted, style = MaterialTheme.typography.bodySmall)
    }
}

/** Hero preview of the run being built or edited. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PreviewCard(
    database: HabitDatabase,
    editRun: app.habitlab.db.Run?,
    name: String,
    days: Int,
    startDate: String,
    today: String,
    habits: List<Habit>,
) {
    val eyebrow =
        if (editRun == null) {
            "NEW RUN · PREVIEW"
        } else {
            val end = addIsoDays(editRun.start_date, editRun.duration_days.toInt() - 1)
            when {
                database.isRunEnded(editRun, today) -> "COMPLETED"
                today < editRun.start_date -> "UPCOMING · STARTS ${shortDate(editRun.start_date).uppercase()}"
                editRun.status != "active" -> "STOPPED"
                else -> "ACTIVE · DAY ${(0 until editRun.duration_days.toInt()).count {
                    addIsoDays(
                        editRun.start_date,
                        it,
                    ) <= minOf(today, end)
                }} OF ${editRun.duration_days}"
            }
        }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(palette.hero)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(eyebrow, style = EyebrowStyle, color = palette.accent)
        Text(name.ifBlank { "$days-day run" }, style = MaterialTheme.typography.headlineSmall.copy(fontSize = 24.sp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Pill("$days days", surface, ink)
            if (isValidRunDate(startDate)) Pill("${shortDate(startDate)} – ${shortDate(addIsoDays(startDate, days - 1))}", surface, ink)
            Pill("${habits.size} ${if (habits.size == 1) "habit" else "habits"}", surface, ink)
        }
        if (habits.isNotEmpty()) {
            Row(Modifier.padding(top = 2.dp)) {
                habits.take(8).forEachIndexed { index, habit ->
                    val tone = palette.habitTone(habit.color)
                    Box(
                        Modifier
                            .offset(x = (-6 * index).dp)
                            .size(34.dp)
                            .clip(RoundedCornerShape(11.dp))
                            .background(palette.hero)
                            .padding(2.5.dp)
                            .clip(RoundedCornerShape(9.dp))
                            .background(tone.tint),
                        contentAlignment = Alignment.Center,
                    ) { HabitGlyph(habit, Modifier.size(17.dp), tone.tint, tone.ink) }
                }
                if (habits.size > 8) {
                    Text(
                        "+${habits.size - 8}",
                        style = MaterialTheme.typography.labelLarge,
                        color = muted,
                        modifier = Modifier.padding(start = 4.dp).align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

/** Edit mode: three stat cards (day, completion, streak or points) and the shared-habit note. */
@Composable private fun EditStats(
    database: HabitDatabase,
    run: app.habitlab.db.Run,
    today: String,
) {
    val stats =
        remember(database, run, today) {
            val habits = loadRunHabits(database, run.uid)
            val runDays = run.duration_days.toInt()
            val end = addIsoDays(run.start_date, runDays - 1)
            val last = if (today < end) today else end
            val percent = if (today < run.start_date) 0 else runCompletion(database, habits, run.start_date, last).percent
            val earned =
                habits.sumOf { habit ->
                    database.schemaQueries
                        .selectCheckInsForHabit(habit.uid())
                        .executeAsList()
                        .count { it.state == "done" && it.day >= run.start_date && it.day <= last } * habit.points
                }
            val streak = if (database.currentRun(today)?.uid == run.uid) currentRunStreak(database, today) else 0
            val dayIndex = (0 until runDays).count { addIsoDays(run.start_date, it) <= today }
            Triple(Triple(dayIndex, runDays, percent), earned, streak)
        }
    val (dayInfo, earned, streak) = stats
    val (dayIndex, runDays, percent) = dayInfo
    val blue = palette.habitTone("blue")
    val green = palette.habitTone("green")
    val orange = palette.habitTone("orange")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "This run so far",
            style = MaterialTheme.typography.labelMedium,
            modifier =
                Modifier.padding(horizontal = 4.dp).semantics {
                    heading()
                },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val dayTitle =
                when {
                    dayIndex == 0 -> "Not started"
                    dayIndex >= runDays -> "All $runDays days"
                    else -> "Day $dayIndex of $runDays"
                }
            SourceCard(
                dayTitle,
                "Started ${shortDate(run.start_date)}",
                LinePaths.CALENDAR,
                blue.tint,
                blue.ink,
                false,
                null,
                Modifier.weight(1f),
            )
            SourceCard(
                "$percent% so far",
                if (hidePointsPref) "Run completion" else "$earned pts earned",
                LinePaths.CHART,
                green.tint,
                green.ink,
                false,
                null,
                Modifier.weight(1f),
            )
            SourceCard(
                if (streak > 0) "$streak-day streak" else "No streak yet",
                "Days in a row",
                LinePaths.FLAME,
                orange.tint,
                orange.ink,
                false,
                null,
                Modifier.weight(1f),
            )
        }
        InfoLine("Your check-ins are kept. Editing a habit updates every run that uses it.")
    }
}

@Composable private fun EditorHabitRow(
    habit: Habit,
    flagged: Boolean,
    onClick: () -> Unit,
) {
    val tone = palette.habitTone(habit.color)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 62.dp)
            .clickable(role = Role.Button, onClickLabel = "Edit habit", onClick = onClick)
            .semantics { contentDescription = "Edit ${habit.name}${if (flagged) ", duplicate name" else ""}" }
            .padding(start = 10.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        HabitIconTile(habit, tone)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(habit.name, style = MaterialTheme.typography.titleSmall, color = if (flagged) palette.error else ink, maxLines = 2)
            Text(
                if (flagged) "Duplicate name" else "${habit.group} · ${scheduleLabel(habit.recurrence)}",
                style = MaterialTheme.typography.bodySmall,
                color = if (flagged) palette.error else muted,
                maxLines = 1,
            )
        }
        if (!hidePointsPref) Pill("${habit.points} pts", tone.tint, tone.ink)
        LineIcon(LinePaths.CHEVRON_RIGHT, muted, size = 16.dp)
    }
}

/** Attach habits that already exist (from other runs or standalone) to this run. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddExistingSheet(
    database: HabitDatabase,
    exclude: Set<String>,
    onAdd: (Habit) -> Unit,
    onDismiss: () -> Unit,
) {
    val candidates =
        remember(database, exclude) {
            database.schemaQueries
                .selectHabits()
                .executeAsList()
                .filter { it.uid !in exclude }
                .map { it.toUiHabit(database) }
        }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Add existing habits", style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
            Text(
                "Attached habits are shared: editing one updates every run that uses it.",
                color = muted,
                style = MaterialTheme.typography.bodySmall,
            )
            if (candidates.isEmpty()) {
                Text(
                    "Every habit you have is already in this run.",
                    color = muted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            }
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                candidates.forEachIndexed { index, habit ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(palette.divider))
                    val tone = palette.habitTone(habit.color)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp)
                            .clickable(role = Role.Button, onClickLabel = "Add to run") { onAdd(habit) }
                            .semantics { contentDescription = "Add ${habit.name}" }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        HabitIconTile(habit, tone)
                        Column(Modifier.weight(1f)) {
                            Text(habit.name, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${habit.group} · ${scheduleLabel(habit.recurrence)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = muted,
                            )
                        }
                        LineIcon(LinePaths.PLUS, palette.accent, size = 18.dp, strokeWidth = 2.6f)
                    }
                }
            }
            PrimaryButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
        }
    }
}
