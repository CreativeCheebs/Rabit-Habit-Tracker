package app.habitlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.launch
import kotlin.random.Random
import app.habitlab.db.HabitDatabase
import app.habitlab.sync.SignInProblem
import app.habitlab.sync.SYNC_MODE_OFFLINE
import app.habitlab.sync.SYNC_MODE_SERVER
import app.habitlab.sync.SyncClient
import app.habitlab.sync.SyncOutcome
import app.habitlab.sync.createHttpClient

private val ink = androidx.compose.ui.graphics.Color(0xFFE5ECE6)
private val muted = androidx.compose.ui.graphics.Color(0xFFA3AFA6)
private val surface = androidx.compose.ui.graphics.Color(0xFF1C241E)
private val green = androidx.compose.ui.graphics.Color(0xFFA4D0AD)
private val hero = androidx.compose.ui.graphics.Color(0xFF18231B)
private data class Habit(val name: String, val group: String, val points: Int, val recurrence: String, val iteration: String = "I", val id: String = "")
private val sampleHabits = listOf(
    Habit("Morning light", "Wellbeing", 2, "Daily"), Habit("Sleep before 11:30 PM", "Wellbeing", 2, "Every 3 days", "II"),
    Habit("Plan the day", "Focus", 3, "3 days per week"), Habit("Focused work block", "Focus", 4, "3 days per week")
)
private fun Habit.uid(): String = id.ifBlank { when (name) { "Morning light" -> "morning-light"; "Sleep before 11:30 PM" -> "sleep-routine"; "Plan the day" -> "plan-day"; else -> "focus-block" } }
private fun addIsoDays(date: String, amount: Int): String {
    val parts = date.split("-")
    var year = parts.getOrNull(0)?.toIntOrNull() ?: return date
    var month = parts.getOrNull(1)?.toIntOrNull() ?: return date
    var day = parts.getOrNull(2)?.toIntOrNull() ?: return date
    fun monthLength(y: Int, m: Int) = when (m) { 2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
    if (amount >= 0) repeat(amount) { day++; if (day > monthLength(year, month)) { day = 1; month++; if (month > 12) { month = 1; year++ } } }
    else repeat(-amount) { day--; if (day < 1) { month--; if (month < 1) { month = 12; year-- }; day = monthLength(year, month) } }
    return "%04d-%02d-%02d".format(year, month, day)
}
private enum class Destination(val title: String, val glyph: String) {
    Runs("Runs", "◷"), Analytics("Analytics", "▥"), QuickLog("Quick Log", "⇆"), Goals("Goals", "◎"), Trackers("Trackers", "⌁")
}

@Composable
fun HabitLabApp(database: HabitDatabase, today: String) {
    var page by remember { mutableStateOf(Destination.Runs) }
    var week by remember { mutableIntStateOf(0) }
    var showNewRun by remember { mutableStateOf(false) }
    var runsRevision by remember { mutableIntStateOf(0) }
    var builderCopy by remember { mutableStateOf<Boolean?>(null) }
    val syncClient = remember(database) { SyncClient(database, createHttpClient()) }
    var syncMode by remember(database) { mutableStateOf(syncClient.mode()) }
    var syncing by remember { mutableStateOf(false) }
    var syncDirty by remember { mutableStateOf(false) }
    var syncStatus by remember { mutableStateOf<String?>(null) }
    var syncProgress by remember { mutableStateOf<Pair<Long, Long?>?>(null) }
    var showFirstStart by remember(database) { mutableStateOf(syncClient.mode() == null) }
    var showSignIn by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }
    fun openSignIn() {
        signInError = null
        showFirstStart = false
        showSignIn = true
    }
    val syncScope = rememberCoroutineScope()
    fun describeOutcome(outcome: SyncOutcome): String = when (outcome) {
        is SyncOutcome.Success -> "Synced · ${outcome.sent} sent · ${outcome.received} received"
        SyncOutcome.NotConfigured -> "Sync is not set up."
        is SyncOutcome.LocalProblem -> outcome.message
        is SyncOutcome.Unreachable -> outcome.message
        is SyncOutcome.ServerRefused -> outcome.message
    }
    fun doSync() {
        if (syncing || syncMode != SYNC_MODE_SERVER) return
        syncScope.launch {
            syncing = true
            syncProgress = 0L to null
            val outcome = syncClient.syncOnce { downloaded, total -> syncProgress = downloaded to total }
            syncProgress = null
            syncing = false
            syncStatus = describeOutcome(outcome)
            if (outcome is SyncOutcome.Success) {
                syncDirty = false
                if (outcome.received > 0) runsRevision++
            }
        }
    }
    fun noteLocalChange() {
        syncDirty = true
        doSync()
    }
    LaunchedEffect(syncMode) {
        if (syncMode != SYNC_MODE_SERVER) return@LaunchedEffect
        doSync()
        while (true) {
            kotlinx.coroutines.delay(20_000)
            if (syncDirty) doSync()
        }
    }
    MaterialTheme(colorScheme = darkColorScheme(primary = green, onPrimary = androidx.compose.ui.graphics.Color(0xFF173721), background = androidx.compose.ui.graphics.Color(0xFF111713), surface = surface, onSurface = ink, onSurfaceVariant = muted, secondary = androidx.compose.ui.graphics.Color(0xFFB9CBBE))) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            NavigationBar(containerColor = surface) {
                Destination.entries.forEach { dest -> NavigationBarItem(selected = page == dest, onClick = { page = dest }, icon = { DockGlyph(dest) }, label = { Text(dest.title) }, alwaysShowLabel = true)
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (page == Destination.Runs) "Habits" else page.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    if (page == Destination.Runs && syncMode == SYNC_MODE_SERVER) IconButton(
                        onClick = { doSync() },
                        enabled = !syncing,
                        modifier = Modifier.size(42.dp).clip(RoundedCornerShape(50)).background(surface)
                            .semantics { contentDescription = "Sync now" }
                    ) { Text(if (syncing) "…" else "⟳", color = green, style = MaterialTheme.typography.headlineMedium) }
                    if (page == Destination.Runs) IconButton(onClick = { showNewRun = true }, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(50)).background(surface)) { Text("＋", color = green, style = MaterialTheme.typography.headlineMedium) }
                    if (page == Destination.Runs && syncMode == SYNC_MODE_OFFLINE) TextButton(
                        onClick = { openSignIn() },
                        modifier = Modifier.semantics { contentDescription = "Connect to sync server" }
                    ) { Text("Offline · connect", style = MaterialTheme.typography.labelSmall, color = muted) }
                    if (page == Destination.QuickLog) Text("~5 min", style = MaterialTheme.typography.labelMedium, color = muted)
                }
                if (page == Destination.Runs && syncMode == SYNC_MODE_SERVER) {
                    syncStatus?.let { status ->
                        Text(
                            if (syncing) "Syncing…" else status,
                            color = muted, style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                        )
                    }
                    val progress = syncProgress
                    if (syncing && progress != null && progress.second != null && progress.second!! > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.first.toFloat() / progress.second!!.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            color = green, trackColor = androidx.compose.ui.graphics.Color(0xFF3B5141)
                        )
                    }
                }
                when (page) {
                    Destination.Runs -> RunsScreen(database, runsRevision, today, week, onWeekChange = { week = (week + it).coerceIn(0, 3) }, onLocalChange = { noteLocalChange() })
                    Destination.QuickLog -> QuickLogScreen(database, today, runsRevision, onLocalChange = { noteLocalChange() })
                    Destination.Goals -> ListingScreen("Your goals", listOf("Start focused work by 9:30", "Build a steady sleep routine", "Run 5 km comfortably"), "Progress history starts from each goal’s start date.")
                    Destination.Trackers -> ListingScreen("Personal measurements", listOf("Sleep quality · /10", "Daily energy · /10", "Body weight · kg"), "Dated entries and simple history graphs.")
                    Destination.Analytics -> ListingScreen("Analytics preview", listOf("Cycle analytics arrive in Phase 2", "Example data is not personal data"), "Analytics is a Phase 2 preview.")
                }
            }
            if (showNewRun) AlertDialog(
                onDismissRequest = { showNewRun = false },
                title = { Text("Build a new run") },
                text = { Text("Start with a blank run or copy the latest run’s habits and settings.") },
                confirmButton = { TextButton(onClick = { showNewRun = false; builderCopy = false }) { Text("Start from scratch") } },
                dismissButton = { TextButton(onClick = { showNewRun = false; builderCopy = true }) { Text("Copy previous") } }
            )
            builderCopy?.let { copy ->
                RunBuilderDialog(
                    database, today, copy,
                    onCreated = { runsRevision++; noteLocalChange() },
                    onDismiss = { builderCopy = null }
                )
            }
            if (showFirstStart) {
                AlertDialog(
                    onDismissRequest = {},
                    title = { Text("Sync your habits?") },
                    text = { Text("Connect to your sync server to keep every device up to date, or keep using this phone offline. You can change this later.") },
                    confirmButton = {
                        TextButton(onClick = { openSignIn() }) { Text("Connect to a server") }
                    },
                    dismissButton = {
                        TextButton(onClick = {
                            syncClient.setMode(SYNC_MODE_OFFLINE)
                            syncMode = SYNC_MODE_OFFLINE
                            showFirstStart = false
                        }) { Text("Use offline") }
                    }
                )
            }
            if (showSignIn) {
                SignInDialog(
                    initialUrl = syncClient.serverUrl() ?: "",
                    initialUsername = syncClient.account() ?: "",
                    error = signInError,
                    onSignIn = { url, username, password ->
                        syncScope.launch {
                            try {
                                syncClient.signIn(url, username, password)
                                syncMode = SYNC_MODE_SERVER
                                signInError = null
                                showSignIn = false
                            } catch (e: SignInProblem) {
                                signInError = e.message
                            } catch (e: IllegalArgumentException) {
                                signInError = e.message
                            }
                        }
                    },
                    onOffline = {
                        syncClient.setMode(SYNC_MODE_OFFLINE)
                        syncMode = SYNC_MODE_OFFLINE
                        showSignIn = false
                        showFirstStart = false
                    },
                    onDismiss = { showSignIn = false }
                )
            }
        }
    }
}

@Composable private fun SignInDialog(
    initialUrl: String,
    initialUsername: String,
    error: String?,
    onSignIn: (String, String, String) -> Unit,
    onOffline: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Connect to sync server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("Server address") }, placeholder = { Text("http://192.168.0.8:8788") }, singleLine = true)
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation())
                if (error != null) Text(error, color = androidx.compose.ui.graphics.Color(0xFFE7B4AD), style = MaterialTheme.typography.bodySmall)
                Text("First connection downloads everything; later syncs are small and silent.", color = muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSignIn(url.trim(), username.trim(), password) },
                enabled = url.isNotBlank() && username.isNotBlank() && password.isNotEmpty()
            ) { Text("Connect") }
        },
        dismissButton = { TextButton(onClick = onOffline) { Text("Use offline") } }
    )
}

@Composable private fun DockGlyph(destination: Destination) {
    val tint = androidx.compose.material3.LocalContentColor.current
    Canvas(Modifier.size(24.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2f * u)
        val c = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        when (destination) {
            Destination.Runs -> {
                drawCircle(tint, radius = 9f * u, center = c, style = stroke)
                drawLine(tint, c, androidx.compose.ui.geometry.Offset(c.x, c.y - 5f * u), 2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, c, androidx.compose.ui.geometry.Offset(c.x + 4f * u, c.y + 2f * u), 2f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Destination.Analytics -> {
                drawLine(tint, androidx.compose.ui.geometry.Offset(3f*u, 20f*u), androidx.compose.ui.geometry.Offset(21f*u, 20f*u), 2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                listOf(7f to 9f, 12f to 5f, 17f to 2f).forEach { (x, h) -> drawLine(tint, androidx.compose.ui.geometry.Offset(x*u, 19f*u), androidx.compose.ui.geometry.Offset(x*u, (19f-h)*u), 3f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round) }
            }
            Destination.QuickLog -> {
                drawLine(tint, androidx.compose.ui.geometry.Offset(4f*u, 8f*u), androidx.compose.ui.geometry.Offset(20f*u, 8f*u), 2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(16f*u, 4f*u), androidx.compose.ui.geometry.Offset(20f*u, 8f*u), 2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(16f*u, 12f*u), androidx.compose.ui.geometry.Offset(20f*u, 8f*u), 2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(20f*u, 16f*u), androidx.compose.ui.geometry.Offset(4f*u, 16f*u), 2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(8f*u, 12f*u), androidx.compose.ui.geometry.Offset(4f*u, 16f*u), 2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(8f*u, 20f*u), androidx.compose.ui.geometry.Offset(4f*u, 16f*u), 2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
            Destination.Goals -> {
                drawCircle(tint, 9f*u, c, style = stroke)
                drawCircle(tint, 5f*u, c, style = stroke)
                drawCircle(tint, 1.7f*u, c)
            }
            Destination.Trackers -> {
                val path = Path().apply { moveTo(3f*u, 15f*u); lineTo(8f*u, 10f*u); lineTo(12f*u, 13f*u); lineTo(18f*u, 5f*u); lineTo(21f*u, 7f*u) }
                drawPath(path, tint, style = Stroke(width = 2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}

@Composable private fun RunBuilderDialog(database: HabitDatabase, today: String, copyPrevious: Boolean, onCreated: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var duration by remember { mutableIntStateOf(21) }
    var custom by remember { mutableStateOf(false) }
    var customDays by remember { mutableStateOf("28") }
    val existing = remember(database) { database.schemaQueries.selectHabits().executeAsList() }
    val latestRun = remember(database) { database.schemaQueries.selectRuns().executeAsList().firstOrNull() }
    val previousHabitIds = remember(database, latestRun) { latestRun?.let { database.schemaQueries.selectHabitsForRun(it.uid).executeAsList().map { row -> row.uid } }.orEmpty() }
    val selected = remember { mutableStateListOf<String>().apply { if (copyPrevious) addAll(previousHabitIds.ifEmpty { sampleHabits.map { it.uid() } }) } }
    val addedHabits = remember { mutableStateListOf<Habit>() }
    var newHabitName by remember { mutableStateOf("") }
    var newHabitPoints by remember { mutableStateOf("1") }
    var newHabitGroup by remember { mutableStateOf("General") }
    var intervalMode by remember { mutableStateOf(false) }
    var recurrenceValue by remember { mutableStateOf("3") }
    var editingSourceId by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Build a new run") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (copyPrevious) "Copy previous · choose habits to reuse" else "Start from scratch · add habits", color = muted, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Run name") }, singleLine = true)
                Text("Run length", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(7, 14, 21).forEach { days -> FilterChip(selected = !custom && duration == days, onClick = { duration = days; custom = false }, label = { Text("$days d") }) }
                    FilterChip(selected = custom, onClick = { custom = true }, label = { Text("Custom") })
                }
                if (custom) OutlinedTextField(value = customDays, onValueChange = { customDays = it.filter(Char::isDigit).take(3) }, label = { Text("Days") }, singleLine = true)
                Text("Habits", style = MaterialTheme.typography.labelLarge)
                existing.forEach { row ->
                    Row(Modifier.fillMaxWidth().clickable { if (row.uid in selected) selected.remove(row.uid) else selected.add(row.uid) }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = row.uid in selected, onCheckedChange = { checked -> if (checked) selected.add(row.uid) else selected.remove(row.uid) })
                        Column(Modifier.weight(1f)) { Text(row.title, style = MaterialTheme.typography.bodyMedium); Text("${row.impact_points} impact pts · ${row.recurrence_type} ${row.recurrence_value}", style = MaterialTheme.typography.labelSmall, color = muted) }
                        TextButton(onClick = {
                            editingSourceId = row.uid
                            newHabitName = row.title
                            newHabitGroup = row.group_name
                            newHabitPoints = row.impact_points.toString()
                            intervalMode = row.recurrence_type == "interval"
                            recurrenceValue = row.recurrence_value.toString()
                        }) { Text("Edit") }
                    }
                }
                addedHabits.forEach { Text("${it.name} · ${it.points} pts · ${it.recurrence}", style = MaterialTheme.typography.bodySmall, color = green) }
                HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF303A32))
                Text("Add a habit", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = newHabitName, onValueChange = { newHabitName = it }, label = { Text("Habit name") }, singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newHabitGroup, onValueChange = { newHabitGroup = it }, label = { Text("Group") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = newHabitPoints, onValueChange = { newHabitPoints = it.filter(Char::isDigit).take(3) }, label = { Text("Impact pts") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !intervalMode, onClick = { intervalMode = false }, label = { Text("Days per week") })
                    FilterChip(selected = intervalMode, onClick = { intervalMode = true }, label = { Text("Every N days") })
                }
                OutlinedTextField(value = recurrenceValue, onValueChange = { recurrenceValue = it.filter(Char::isDigit).take(2) }, label = { Text(if (intervalMode) "Interval days" else "Target days per week") }, singleLine = true)
                TextButton(onClick = {
                    val habitName = newHabitName.trim()
                    if (habitName.isNotEmpty()) {
                        editingSourceId?.let { selected.remove(it) }
                        val id = "habit-${Random.nextLong().toULong().toString(16)}"
                        val count = recurrenceValue.toIntOrNull()?.coerceIn(1, if (intervalMode) 365 else 7) ?: 3
                        addedHabits.add(Habit(habitName, newHabitGroup.ifBlank { "General" }, newHabitPoints.toIntOrNull()?.coerceAtLeast(0) ?: 1, if (intervalMode) "Every $count days" else "$count days per week", "I", id))
                        newHabitName = ""
                        editingSourceId = null
                    }
                }, enabled = newHabitName.isNotBlank()) { Text("＋ Add habit to this run") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val days = if (custom) customDays.toLongOrNull()?.coerceIn(1, 365) ?: 21L else duration.toLong()
                val runName = name.ifBlank { "${days}-day run" }
                val runId = "run-$today-${Random.nextLong().toULong().toString(16)}"
                database.transaction {
                    database.schemaQueries.insertRun(runId, runName, today, days)
                    addedHabits.forEach { habit ->
                        val count = Regex("\\d+").find(habit.recurrence)?.value?.toLongOrNull() ?: 3L
        database.schemaQueries.insertHabit(habit.uid(), habit.name, habit.group, 1L, habit.points.toLong(), if (habit.recurrence.startsWith("Every ")) "interval" else "weekly", count)
                    }
                    selected.forEach { database.schemaQueries.attachHabitToRun("$runId:$it", runId, it) }
                    addedHabits.forEach { database.schemaQueries.attachHabitToRun("$runId:${it.uid()}", runId, it.uid()) }
                }
                onCreated()
                onDismiss()
            }) { Text("Create run") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun RunsScreen(database: HabitDatabase, revision: Int, today: String, week: Int, onWeekChange: (Int) -> Unit, onLocalChange: () -> Unit = {}) {
    val latestRun = remember(database, revision) { database.schemaQueries.selectRuns().executeAsList().firstOrNull() }
    val currentHabits = remember(database, revision, latestRun) {
        latestRun?.let { run -> database.schemaQueries.selectHabitsForRun(run.uid).executeAsList().map { row ->
            val recur = if (row.recurrence_type == "interval") "Every ${row.recurrence_value} days" else "${row.recurrence_value} days per week"
            Habit(row.title, row.group_name, row.impact_points.toInt(), recur, "I", row.uid)
        } }?.takeIf { it.isNotEmpty() } ?: sampleHabits
    }
    val runName = latestRun?.name ?: "21-day habit run"
    val runDays = latestRun?.duration_days?.toInt() ?: 21
    val maxWeek = ((runDays - 1) / 7).coerceIn(0, 3)
    val runStart = latestRun?.start_date ?: today
    val elapsed = (0 until runDays).count { addIsoDays(runStart, it) <= today }.coerceIn(1, runDays)
    val earnedPoints = remember(database, revision, latestRun, currentHabits, today) {
        val pointsById = currentHabits.associate { it.uid() to it.points }
        (0 until runDays).sumOf { dayOffset ->
            database.schemaQueries.selectCheckInsForDay(addIsoDays(runStart, dayOffset)).executeAsList()
                .filter { it.state == "done" && it.habit_uid in pointsById }
                .sumOf { pointsById[it.habit_uid] ?: 0 }
        }
    }
    val avgImpact = earnedPoints.toFloat() / elapsed
    var selectedDay by remember(week) { mutableIntStateOf(0) }
    var dragTotal by remember { mutableFloatStateOf(0f) }
    val runScope = rememberCoroutineScope()
    LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { CompactRunSummaryCard(runName, runDays, currentHabits.size, earnedPoints, avgImpact, elapsed) }
        item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text("Run Habits", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f)); Text("21 days", style = MaterialTheme.typography.labelSmall, color = muted) } }
        item { Row(Modifier.fillMaxWidth().pointerInput(week, maxWeek) { detectHorizontalDragGestures(onHorizontalDrag = { change, amount -> change.consume(); dragTotal += amount }, onDragEnd = { if (dragTotal < -55f && week < maxWeek) onWeekChange(1) else if (dragTotal > 55f && week > 0) onWeekChange(-1); dragTotal = 0f }) }, horizontalArrangement = Arrangement.spacedBy(6.dp)) { (0..3).forEach { i -> FilterChip(selected = week == i, enabled = i <= maxWeek, onClick = { onWeekChange(i - week) }, label = { Text("Week ${i + 1}") }, modifier = Modifier.weight(1f)) } } }
        item { Text("Select a day, then swipe a habit: right for done · left for skipped · Partial for partial", color = muted, style = MaterialTheme.typography.labelSmall) }
        items(currentHabits.groupBy { it.group }.toList()) { (group, habits) ->
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.padding(14.dp)) {
                Text("${group.uppercase()} · ${habits.size} HABITS", style = MaterialTheme.typography.labelMedium, color = green)
                habits.forEachIndexed { index, h ->
                    if (index > 0) HorizontalDivider(color = androidx.compose.ui.graphics.Color(0xFF303A32), modifier = Modifier.padding(start = 48.dp, top = 7.dp))
                    val runStart = latestRun?.start_date ?: today
                    val days = remember(h.uid(), week, revision, today) { mutableStateListOf<Int>().apply {
                        repeat(7) { dayIndex ->
                            val offset = week * 7 + dayIndex
                            if (offset >= runDays) add(-1) else {
                                val date = addIsoDays(runStart, offset)
                                val logged = database.schemaQueries.selectCheckInsForDay(date).executeAsList().firstOrNull { it.habit_uid == h.uid() }
                                add(when (logged?.state) { "done" -> 1; "partial" -> 2; "skipped" -> 3; else -> if (date < today) 3 else 0 })
                            }
                        }
                    } }
                    val runSwipeOffset = remember(h.uid(), week) { Animatable(0f) }
                    fun commitSwipe(state: Int) {
                        val day = selectedDay.coerceIn(0, days.lastIndex)
                        if (days[day] >= 0) {
                            val firstLog = days[day] == 0
                            days[day] = state
                            val date = addIsoDays(runStart, week * 7 + day)
                            val dbState = when (state) { 1 -> "done"; 2 -> "partial"; else -> "skipped" }
                            if (firstLog) {
                                database.schemaQueries.insertCheckIn("${h.uid()}:$date", h.uid(), date, dbState, null)
                            } else {
                                database.schemaQueries.updateCheckIn(dbState, null, h.uid(), date)
                            }
                            onLocalChange()
                        }
                    }
                    Box(Modifier.fillMaxWidth().padding(top = 11.dp, bottom = 7.dp)) {
                        SwipeActionBackground(
                            action = previewSwipeAction(runSwipeOffset.value, QuickSwipeAction.Done, QuickSwipeAction.Skipped),
                            progress = swipeProgress(runSwipeOffset.value, 55f),
                            positive = runSwipeOffset.value >= 0f,
                            shape = RoundedCornerShape(16.dp)
                        )
                    Column(Modifier.fillMaxWidth()
                        .graphicsLayer { translationX = runSwipeOffset.value; rotationZ = runSwipeOffset.value / 90f }
                        .semantics { customActions = listOf(
                            CustomAccessibilityAction("Mark done") { commitSwipe(1); runScope.launch { runSwipeOffset.snapTo(0f) }; true },
                            CustomAccessibilityAction("Mark skipped") { commitSwipe(3); runScope.launch { runSwipeOffset.snapTo(0f) }; true }
                        ) }
                        .pointerInput(selectedDay, h.name, week) {
                            var drag = 0f
                            detectHorizontalDragGestures(onHorizontalDrag = { change, amount ->
                                change.consume(); drag += amount
                                runScope.launch { runSwipeOffset.snapTo(runSwipeOffset.value + amount) }
                            }, onDragEnd = {
                                val direction = when (resolveSwipeAction(drag, 55f, QuickSwipeAction.Done, QuickSwipeAction.Skipped)) { QuickSwipeAction.Done -> 1; QuickSwipeAction.Skipped -> -1; else -> 0 }
                                if (direction == 0) runScope.launch { runSwipeOffset.animateTo(0f, spring()) }
                                else runScope.launch {
                                    runSwipeOffset.animateTo(direction * size.width.toFloat() * 1.15f, tween(190))
                                    commitSwipe(if (direction > 0) 1 else 3)
                                    runSwipeOffset.snapTo(-direction * size.width.toFloat() * .38f)
                                    runSwipeOffset.animateTo(0f, spring(dampingRatio = .78f, stiffness = 420f))
                                }
                                drag = 0f
                            })
                        }) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color(0xFF29352D)), contentAlignment = Alignment.Center) { HabitGlyph(h, Modifier.size(22.dp), androidx.compose.ui.graphics.Color(0xFF29352D)) }
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(h.name, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                    Text(h.iteration, modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(androidx.compose.ui.graphics.Color(0xFF344C39)).padding(horizontal = 4.dp, vertical = 1.dp), style = MaterialTheme.typography.labelSmall, color = green)
                                }
                                Text("${h.points} impact pts · ${h.recurrence}", style = MaterialTheme.typography.bodySmall, color = muted, maxLines = 1)
                            }
                            TextButton(onClick = { commitSwipe(2) }, contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)) { Text("Partial", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFFE6BD76)) }
                        }
                        Row(Modifier.fillMaxWidth().padding(start = 49.dp, top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            days.forEachIndexed { day, state ->
                                Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp)).then(if (selectedDay == day && state >= 0) Modifier.border(2.dp, ink, RoundedCornerShape(6.dp)) else Modifier).then(when (state) { 1 -> Modifier.background(green); 2 -> Modifier.background(androidx.compose.ui.graphics.Color(0xFFE6BD76)); 3 -> Modifier.background(androidx.compose.ui.graphics.Color(0xFF754B4B)); -1 -> Modifier.background(androidx.compose.ui.graphics.Color(0xFF252C27)); else -> Modifier.border(1.dp, muted, RoundedCornerShape(6.dp)) }).clickable(enabled = state >= 0) { selectedDay = day })
                            }
                        }
                        }
                    }
                }
            } }
        }
        item { ImpactByDayCard() }
        item { BurnupCard() }
    }
}

@Composable private fun CompactRunSummaryCard(runName: String, days: Int, habitCount: Int, earned: Int, average: Float, elapsed: Int) {
    Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF24352A)), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ACTIVE RUN", style = MaterialTheme.typography.labelSmall, color = green)
                    Text(runName, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 2.dp), maxLines = 1)
                    Text("Day $elapsed of $days · $habitCount habits", style = MaterialTheme.typography.labelSmall, color = muted)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("$earned pts", style = MaterialTheme.typography.titleMedium, color = green)
                    Text("${"%.1f".format(average)} avg / day", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
            LinearProgressIndicator(progress = { (elapsed.toFloat() / days.coerceAtLeast(1)).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = green, trackColor = androidx.compose.ui.graphics.Color(0xFF3B5141))
        }
    }
}

@Composable private fun SectionCard(title: String, subtitle: String, detail: String) {
    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) { Column(Modifier.fillMaxWidth().padding(16.dp)) { Text(title, style = MaterialTheme.typography.titleSmall); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 4.dp)); Text(detail, color = green, modifier = Modifier.padding(top = 12.dp)) } }
}

@Composable private fun ImpactByDayCard() {
    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Impact points by day", style = MaterialTheme.typography.titleSmall)
                Text("SAMPLE", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Text("Earned impact points · this week", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 3.dp))
            val values = listOf(4f, 7f, 3f, 8f, 6f, 9f, 5f)
            Canvas(Modifier.fillMaxWidth().height(112.dp).padding(top = 12.dp)) {
                val max = 10f
                val slot = size.width / values.size
                val barWidth = slot * .46f
                for (line in 1..3) {
                    val y = size.height * line / 4f
                    drawLine(androidx.compose.ui.graphics.Color(0xFF303A32), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), 1.dp.toPx())
                }
                values.forEachIndexed { i, value ->
                    val height = (size.height - 8.dp.toPx()) * value / max
                    drawRoundRect(green, androidx.compose.ui.geometry.Offset(i * slot + (slot - barWidth) / 2, size.height - height), androidx.compose.ui.geometry.Size(barWidth, height), androidx.compose.ui.geometry.CornerRadius(5.dp.toPx()))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { listOf("M", "T", "W", "T", "F", "S", "S").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) } }
        }
    }
}

@Composable private fun BurnupCard() {
    Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Impact points burnup", style = MaterialTheme.typography.titleSmall)
                Text("EXAMPLE FORECAST", style = MaterialTheme.typography.labelSmall, color = muted)
            }
            Text("Cumulative points · actual and predicted", style = MaterialTheme.typography.bodySmall, color = muted, modifier = Modifier.padding(top = 3.dp))
            Canvas(Modifier.fillMaxWidth().height(130.dp).padding(top = 12.dp)) {
                val left = 8.dp.toPx(); val right = size.width - 6.dp.toPx(); val top = 5.dp.toPx(); val bottom = size.height - 8.dp.toPx()
                val actual = listOf(4f, 9f, 16f, 22f, 28f, 35f, 42f)
                val predicted = listOf(42f, 48f, 53f, 59f, 64f, 69f, 74f)
                fun point(i: Int, value: Float, n: Int, end: Float = 1f): androidx.compose.ui.geometry.Offset {
                    val x = left + (right - left) * end * i / (n - 1).toFloat()
                    val y = bottom - (bottom - top) * value / 80f
                    return androidx.compose.ui.geometry.Offset(x, y)
                }
                for (line in 1..3) { val y = top + (bottom - top) * line / 4f; drawLine(androidx.compose.ui.graphics.Color(0xFF303A32), androidx.compose.ui.geometry.Offset(left, y), androidx.compose.ui.geometry.Offset(right, y), 1.dp.toPx()) }
                val actualPath = Path().apply { actual.forEachIndexed { i, value -> val p = point(i, value, 7, .62f); if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) } }
                drawPath(actualPath, green, style = Stroke(width = 3.dp.toPx()))
                val forecastPath = Path().apply { predicted.forEachIndexed { i, value -> val x = left + (right-left) * (.62f + .38f * i / 6f); val y = bottom - (bottom-top) * value / 80f; if (i == 0) moveTo(x, y) else lineTo(x, y) } }
                drawPath(forecastPath, androidx.compose.ui.graphics.Color(0xFFDFBD82), style = Stroke(width = 3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(7.dp.toPx(), 5.dp.toPx()))))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("━ Actual", style = MaterialTheme.typography.labelSmall, color = green); Text("┄ Predicted · sample", style = MaterialTheme.typography.labelSmall, color = androidx.compose.ui.graphics.Color(0xFFDFBD82)) }
        }
    }
}

@Composable private fun RunSummaryCard() {
    Card(colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF24352A)), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp)) {
            Text("ACTIVE RUN · EXAMPLE DATA", style = MaterialTheme.typography.labelMedium, color = green)
            Text("21-day habit run", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 7.dp))
            Text("Day 13 of 21 · 4 habits", style = MaterialTheme.typography.bodySmall, color = muted)
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) { Text("42 / 68 points", style = MaterialTheme.typography.titleMedium); Text("62%", style = MaterialTheme.typography.titleLarge, color = green) }
            LinearProgressIndicator(progress = { .62f }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp), color = green, trackColor = androidx.compose.ui.graphics.Color(0xFF3B5141))
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryMetric("Total impact", "42 pts"); SummaryMetric("Avg. impact", "3.2 / day"); SummaryMetric("Effort", "Setup pending")
            }
        }
    }
}

@Composable private fun RowScope.SummaryMetric(label: String, value: String) {
    Column(Modifier.weight(1f)) { Text(label, style = MaterialTheme.typography.labelSmall, color = muted); Text(value, style = MaterialTheme.typography.bodyMedium, color = ink, modifier = Modifier.padding(top = 3.dp)) }
}

private fun loadQuickSwipeAction(database: HabitDatabase, key: String, fallback: QuickSwipeAction): QuickSwipeAction =
    try {
        QuickSwipeAction.parse(database.schemaQueries.selectMetaValue(key).executeAsOneOrNull(), fallback)
    } catch (_: Exception) {
        fallback
    }

private fun saveQuickSwipeAction(database: HabitDatabase, key: String, action: QuickSwipeAction) {
    try {
        database.schemaQueries.upsertMetaValue(key, action.key)
    } catch (_: Exception) {
    }
}

private fun swipeActionGlyph(action: QuickSwipeAction): String =
    when (action) {
        QuickSwipeAction.Done -> "✓"
        QuickSwipeAction.Partial -> "◐"
        QuickSwipeAction.Skipped -> "✕"
        QuickSwipeAction.Comment -> "✎"
    }

private fun swipeActionLabel(action: QuickSwipeAction): String =
    when (action) {
        QuickSwipeAction.Done -> "Done"
        QuickSwipeAction.Partial -> "Partial"
        QuickSwipeAction.Skipped -> "Skipped"
        QuickSwipeAction.Comment -> "Comment"
    }

private fun swipeActionContainer(action: QuickSwipeAction): androidx.compose.ui.graphics.Color =
    when (action) {
        QuickSwipeAction.Done -> androidx.compose.ui.graphics.Color(0xFF284632)
        QuickSwipeAction.Partial -> androidx.compose.ui.graphics.Color(0xFF4A402A)
        QuickSwipeAction.Skipped -> androidx.compose.ui.graphics.Color(0xFF5A3532)
        QuickSwipeAction.Comment -> androidx.compose.ui.graphics.Color(0xFF24352A)
    }

private fun swipeActionContent(action: QuickSwipeAction): androidx.compose.ui.graphics.Color =
    when (action) {
        QuickSwipeAction.Done -> androidx.compose.ui.graphics.Color(0xFFCBE8D0)
        QuickSwipeAction.Partial -> androidx.compose.ui.graphics.Color(0xFFFFD18F)
        QuickSwipeAction.Skipped -> androidx.compose.ui.graphics.Color(0xFFE7B4AD)
        QuickSwipeAction.Comment -> androidx.compose.ui.graphics.Color(0xFFA4D0AD)
    }

/** Gmail-style action background revealed behind a swiped card. Transparent at rest. */
@Composable private fun SwipeActionBackground(
    action: QuickSwipeAction?,
    progress: Float,
    positive: Boolean,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
) {
    if (action == null || progress <= 0.02f) {
        Spacer(Modifier.fillMaxSize())
        return
    }
    Box(Modifier.fillMaxSize().clip(shape).background(swipeActionContainer(action).copy(alpha = 0.35f + 0.65f * progress.coerceIn(0f, 1f)))) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (positive) Arrangement.Start else Arrangement.End
        ) {
            Text(
                "${swipeActionGlyph(action)}  ${swipeActionLabel(action)}",
                color = swipeActionContent(action).copy(alpha = progress.coerceIn(0f, 1f)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Bordered corner stamp, matching the web prototype's DONE/SKIP stamp treatment. */
@Composable private fun BoxScope.SwipeStamp(action: QuickSwipeAction, alpha: Float, positive: Boolean) {
    val content = swipeActionContent(action)
    Text(
        swipeStampLabel(action),
        Modifier.align(if (positive) Alignment.TopEnd else Alignment.TopStart)
            .padding(top = 18.dp, start = 18.dp, end = 18.dp)
            .graphicsLayer { rotationZ = if (positive) 12f else -12f; this.alpha = alpha.coerceIn(0f, 1f) }
            .border(3.dp, content, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = content,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
}

/** Full next-habit preview so the layer behind a swiped card is never empty gray. */
@Composable private fun NextHabitPreview(habit: Habit) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().weight(0.45f)
                .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF344B3A), androidx.compose.ui.graphics.Color(0xFF1B2A21)))),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${habit.group.uppercase()} · UP NEXT",
                Modifier.align(Alignment.TopStart).padding(start = 17.dp, top = 15.dp),
                color = green, style = MaterialTheme.typography.labelMedium
            )
            Box(Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)).background(green.copy(alpha = .9f)), contentAlignment = Alignment.Center) {
                HabitGlyph(habit, Modifier.size(30.dp), green, androidx.compose.ui.graphics.Color(0xFF173721))
            }
        }
        Column(Modifier.fillMaxWidth().weight(0.55f).padding(horizontal = 23.dp, vertical = 16.dp)) {
            Text("Up next", color = muted, style = MaterialTheme.typography.labelMedium)
            Text(habit.name, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 6.dp), maxLines = 2)
            Text("${habit.points} impact points · ${habit.recurrence}", color = green, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable private fun SwipeSettingsDialog(
    currentRight: QuickSwipeAction,
    currentLeft: QuickSwipeAction,
    onPick: (QuickSwipeAction, QuickSwipeAction) -> Unit,
    onDismiss: () -> Unit,
) {
    var right by remember(currentRight) { mutableStateOf(currentRight) }
    var left by remember(currentLeft) { mutableStateOf(currentLeft) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Swipe actions") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Swipe right", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickSwipeAction.entries.forEach { option ->
                        FilterChip(selected = right == option, onClick = { right = option }, label = { Text(swipeActionLabel(option)) })
                    }
                }
                Text("Swipe left", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    QuickSwipeAction.entries.forEach { option ->
                        FilterChip(selected = left == option, onClick = { left = option }, label = { Text(swipeActionLabel(option)) })
                    }
                }
                Text("Default: right Done · left Skipped. Comment opens the note sheet without logging.", color = muted, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onPick(right, left) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable private fun QuickLogScreen(database: HabitDatabase, today: String, runsRevision: Int, onLocalChange: () -> Unit = {}) {
    var index by remember { mutableIntStateOf(0) }
    var comment by remember { mutableStateOf("") }
    var commentOpen by remember { mutableStateOf(false) }
    var done by remember { mutableIntStateOf(0) }
    var partial by remember { mutableIntStateOf(0) }
    var skipped by remember { mutableIntStateOf(0) }
    var animating by remember { mutableStateOf(false) }
    var swipeSettingsOpen by remember { mutableStateOf(false) }
    var swipeRight by remember(database) { mutableStateOf(loadQuickSwipeAction(database, SWIPE_RIGHT_KEY, QuickSwipeAction.Done)) }
    var swipeLeft by remember(database) { mutableStateOf(loadQuickSwipeAction(database, SWIPE_LEFT_KEY, QuickSwipeAction.Skipped)) }
    val offsetX = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val commitThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val runHabits = remember(database, runsRevision) {
        val latestRun = database.schemaQueries.selectRuns().executeAsList().firstOrNull()
        latestRun?.let { database.schemaQueries.selectHabitsForRun(it.uid).executeAsList().map { row ->
            val recurrence = if (row.recurrence_type == "interval") "Every ${row.recurrence_value} days" else "${row.recurrence_value} days per week"
            Habit(row.title, row.group_name, row.impact_points.toInt(), recurrence, "I", row.uid)
        } }?.takeIf { it.isNotEmpty() } ?: sampleHabits
    }
    val due = remember(database, today, runHabits) {
        val completed = database.schemaQueries.selectCheckInsForDay(today).executeAsList().filter { it.state == "done" }.map { it.habit_uid }.toSet()
        runHabits.filter { habit ->
            if (habit.uid() in completed) false
            else if (habit.recurrence.startsWith("Every ")) {
                val interval = Regex("\\d+").find(habit.recurrence)?.value?.toIntOrNull() ?: 1
                val recentCompletion = database.schemaQueries.selectCheckInsForHabit(habit.uid()).executeAsList().firstOrNull { it.state == "done" }
                val daysSince = recentCompletion?.let { entry -> (0 until interval).firstOrNull { addIsoDays(today, -it) == entry.day } }
                daysSince == null
            } else true
        }
    }
    fun record(state: String, habit: Habit) {
        val uid = habit.uid()
        val note = comment.takeIf { it.isNotBlank() }
        if (database.schemaQueries.selectCheckInByUid("$uid:$today").executeAsOneOrNull() == null) {
            database.schemaQueries.insertCheckIn("$uid:$today", uid, today, state, note)
        } else {
            database.schemaQueries.updateCheckIn(state, note, uid, today)
        }
        when (state) { "done" -> done++; "partial" -> partial++; "skipped" -> skipped++ }
        index++; comment = ""; commentOpen = false
        onLocalChange()
    }
    fun animateCommitAction(action: QuickSwipeAction, habit: Habit, targetX: Float) {
        if (animating) return
        val state = swipeCheckInState(action)
        if (state == null) {
            commentOpen = true
            scope.launch { offsetX.animateTo(0f, spring()) }
            return
        }
        scope.launch {
            animating = true
            offsetX.animateTo(targetX, tween(230))
            record(state, habit)
            offsetX.snapTo(if (targetX > 0f) -320f else 320f)
            offsetX.animateTo(0f, spring(dampingRatio = .78f, stiffness = 420f))
            animating = false
        }
    }
    if (index >= due.size) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Quick Log complete\n$done done · $partial partial · $skipped skipped", style = MaterialTheme.typography.titleLarge) }; return }
    val h = due[index]
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column { Text("Today’s due habits", style = MaterialTheme.typography.titleMedium); Text("Quick check-in", color = muted, style = MaterialTheme.typography.bodySmall) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip(onClick = {}, label = { Text("${index + 1} of ${due.size}") })
                IconButton(onClick = { swipeSettingsOpen = true }, enabled = !animating, modifier = Modifier.size(40.dp).semantics { contentDescription = "Swipe actions" }) { Text("⚙", color = muted) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            SwipeActionBackground(
                action = previewSwipeAction(offsetX.value, swipeRight, swipeLeft),
                progress = swipeProgress(offsetX.value, commitThresholdPx),
                positive = offsetX.value >= 0f
            )
            if (index + 1 < due.size) {
                Card(
                    Modifier.fillMaxSize().padding(top = 9.dp, start = 9.dp, end = 9.dp).zIndex(0f)
                        .graphicsLayer { scaleX = .96f; scaleY = .96f; alpha = .85f },
                    colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(26.dp)
                ) {
                    NextHabitPreview(due[index + 1])
                }
            }
            Card(
                Modifier.fillMaxSize().zIndex(1f)
                    .graphicsLayer { translationX = offsetX.value; rotationZ = offsetX.value / 36f }
                    .semantics { customActions = listOf(
                        CustomAccessibilityAction("Mark done") { animateCommitAction(QuickSwipeAction.Done, h, 900f); true },
                        CustomAccessibilityAction("Mark partial") { animateCommitAction(QuickSwipeAction.Partial, h, -900f); true },
                        CustomAccessibilityAction("Mark skipped") { animateCommitAction(QuickSwipeAction.Skipped, h, -900f); true },
                        CustomAccessibilityAction("Add comment") { commentOpen = true; true }
                    ) }
                    .pointerInput(index, animating, swipeRight, swipeLeft) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, drag -> change.consume(); scope.launch { if (!animating) offsetX.snapTo(offsetX.value + drag) } },
                            onDragEnd = {
                                val action = resolveSwipeAction(offsetX.value, commitThresholdPx, swipeRight, swipeLeft)
                                if (action != null) {
                                    animateCommitAction(action, h, if (offsetX.value > 0f) size.width.toFloat() * 1.25f else -size.width.toFloat() * 1.25f)
                                } else {
                                    scope.launch { offsetX.animateTo(0f, spring()) }
                                }
                            }
                        )
                    },
                colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(26.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Box(Modifier.fillMaxWidth().weight(0.45f).background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.Color(0xFF344B3A), androidx.compose.ui.graphics.Color(0xFF1B2A21)))), contentAlignment = Alignment.Center) {
                            Canvas(Modifier.fillMaxSize()) {
                                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                                val maxRadius = size.minDimension * .47f
                                listOf(.55f, .78f, 1f).forEach { ratio -> drawCircle(androidx.compose.ui.graphics.Color(0xFF53665A).copy(alpha = .28f), radius = maxRadius * ratio, center = center, style = Stroke(width = 1.dp.toPx())) }
                            }
                            Text("${h.group.uppercase()}  ·  DUE", Modifier.align(Alignment.TopStart).padding(start = 17.dp, top = 15.dp), color = green, style = MaterialTheme.typography.labelMedium)
                            Box(Modifier.size(142.dp).clip(RoundedCornerShape(36.dp)).background(green), contentAlignment = Alignment.Center) { HabitGlyph(h, Modifier.size(56.dp), green, androidx.compose.ui.graphics.Color(0xFF173721)) }
                        }
                        Column(Modifier.fillMaxWidth().weight(0.55f).verticalScroll(rememberScrollState()).padding(horizontal = 23.dp, vertical = 19.dp)) {
                            Text("${h.recurrence}  ·  due today", color = muted, style = MaterialTheme.typography.labelMedium)
                            Text(h.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
                            Text("${h.iteration}  ·  ${h.recurrence}", color = muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp))
                            Text("${h.points} impact points", color = green, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                            Text("Swipe right for ${swipeRight.key} · left for ${swipeLeft.key}", color = muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
                        }
                    }
                    val stampAction = resolveSwipeAction(offsetX.value, commitThresholdPx, swipeRight, swipeLeft)
                        ?: previewSwipeAction(offsetX.value, swipeRight, swipeLeft)
                    if (stampAction != null && kotlin.math.abs(offsetX.value) > 8f) {
                        SwipeStamp(
                            action = stampAction,
                            alpha = (kotlin.math.abs(offsetX.value) / 90f).coerceIn(0f, 1f),
                            positive = offsetX.value >= 0f
                        )
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { animateCommitAction(QuickSwipeAction.Partial, h, -900f) }, enabled = !animating, modifier = Modifier.weight(1f).height(54.dp).semantics { contentDescription = "Partial" }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF4A402A), contentColor = androidx.compose.ui.graphics.Color(0xFFFFD18F))) { Text("◐  Partial") }
            FilledTonalButton(onClick = { commentOpen = true }, enabled = !animating, modifier = Modifier.weight(1f).height(54.dp).semantics { contentDescription = "Comment" }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF24352A), contentColor = green)) { Text("✎  Comment") }
        }
        Text("Swipe right for ${swipeRight.key} · left for ${swipeLeft.key}. Skipped interval habits stay due until completed.", Modifier.fillMaxWidth().padding(horizontal = 12.dp), color = muted, style = MaterialTheme.typography.bodySmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
    if (swipeSettingsOpen) {
        SwipeSettingsDialog(
            currentRight = swipeRight,
            currentLeft = swipeLeft,
            onPick = { right, left ->
                swipeRight = right
                swipeLeft = left
                saveQuickSwipeAction(database, SWIPE_RIGHT_KEY, right)
                saveQuickSwipeAction(database, SWIPE_LEFT_KEY, left)
                swipeSettingsOpen = false
            },
            onDismiss = { swipeSettingsOpen = false }
        )
    }
    if (commentOpen) {
        CommentSheet(
            habit = h,
            comment = comment,
            onCommentChange = { comment = it },
            onClose = { commentOpen = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun CommentSheet(habit: Habit, comment: String, onCommentChange: (String) -> Unit, onClose: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(sheetState.currentValue) {
        if (sheetState.currentValue == SheetValue.Expanded) {
            kotlinx.coroutines.delay(220)
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    ModalBottomSheet(
        onDismissRequest = { keyboard?.hide(); onClose() },
        sheetState = sheetState,
        containerColor = surface,
        contentColor = ink,
        dragHandle = { BottomSheetDefaults.DragHandle(color = muted) }
    ) {
        Column(Modifier.fillMaxWidth().imePadding().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Add a comment", style = MaterialTheme.typography.headlineSmall)
            Text("${habit.name} · today", style = MaterialTheme.typography.bodyMedium, color = muted)
            OutlinedTextField(
                value = comment,
                onValueChange = onCommentChange,
                label = { Text("Comment for today") },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                minLines = 3
            )
            Text("This note will be attached to today’s check-in.", style = MaterialTheme.typography.bodySmall, color = muted)
            Button(onClick = { keyboard?.hide(); onClose() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save comment") }
        }
    }
}

@Composable private fun HabitGlyph(habit: Habit, modifier: Modifier = Modifier, cutoutColor: androidx.compose.ui.graphics.Color = hero, tint: androidx.compose.ui.graphics.Color = green) {
    Canvas(modifier) {
        val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val unit = size.minDimension / 48f
        when (habit.name) {
            "Morning light" -> {
                for (i in 0..7) {
                    val angle = i * (kotlin.math.PI / 4.0)
                    val inner = 15f * unit; val outer = 21f * unit
                    drawLine(tint, androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * inner, center.y + kotlin.math.sin(angle).toFloat() * inner), androidx.compose.ui.geometry.Offset(center.x + kotlin.math.cos(angle).toFloat() * outer, center.y + kotlin.math.sin(angle).toFloat() * outer), strokeWidth = 3.2f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
                drawCircle(tint, radius = 9f * unit, center = center)
            }
            "Sleep before 11:30 PM" -> {
                drawCircle(tint, radius = 19f * unit, center = center)
                drawCircle(cutoutColor, radius = 16f * unit, center = androidx.compose.ui.geometry.Offset(center.x + 9f * unit, center.y - 5f * unit))
            }
            "Plan the day" -> {
                drawCircle(tint, radius = 19f * unit, center = center, style = Stroke(width = 3f * unit))
                drawCircle(tint, radius = 10f * unit, center = center, style = Stroke(width = 3f * unit))
                drawCircle(tint, radius = 2.5f * unit, center = center)
            }
            else -> {
                drawLine(tint, androidx.compose.ui.geometry.Offset(8f * unit, 26f * unit), androidx.compose.ui.geometry.Offset(40f * unit, 22f * unit), strokeWidth = 3f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                drawLine(tint, androidx.compose.ui.geometry.Offset(8f * unit, 34f * unit), androidx.compose.ui.geometry.Offset(40f * unit, 30f * unit), strokeWidth = 3f * unit, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            }
        }
    }
}

@Composable private fun ListingScreen(title: String, rows: List<String>, note: String) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(title, style = MaterialTheme.typography.headlineSmall); Text(note, color = muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        items(rows) { row -> Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) { Text(row, Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.titleMedium) } }
    }
}
