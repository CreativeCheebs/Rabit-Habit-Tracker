package app.habitlab.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlin.random.Random
import app.habitlab.db.HabitDatabase
import app.habitlab.sync.SignInProblem
import app.habitlab.sync.SYNC_MODE_OFFLINE
import app.habitlab.sync.SYNC_MODE_SERVER
import app.habitlab.sync.SyncClient
import app.habitlab.sync.SyncOutcome
import app.habitlab.sync.createHttpClient

internal val ink get() = palette.ink
internal val muted get() = palette.muted
internal val surface get() = palette.surface
internal val green get() = palette.accent
internal val hero get() = palette.hero
internal data class Habit(val name: String, val group: String, val points: Int, val recurrence: String, val iteration: String = "I", val id: String = "", val icon: String = "", val color: String = "")
internal val sampleHabits = listOf(
    Habit("Morning light", "Wellbeing", 2, "Daily"), Habit("Sleep before 11:30 PM", "Wellbeing", 2, "Every 3 days", "II"),
    Habit("Plan the day", "Focus", 3, "3 days per week"), Habit("Focused work block", "Focus", 4, "3 days per week")
)
internal fun Habit.uid(): String = id.ifBlank { when (name) { "Morning light" -> "morning-light"; "Sleep before 11:30 PM" -> "sleep-routine"; "Plan the day" -> "plan-day"; else -> "focus-block" } }
internal fun addIsoDays(date: String, amount: Int): String {
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
    Runs("Runs", "◷"), Analytics("Analytics", "▥"), QuickLog("Quick Log", "⇆"), Goals("Goals", "◎"), Trackers("Trackers", "⌁"), Settings("Settings", "⚙")
}

/** Analytics and Trackers stay in the code but are hidden from the dock for now. */
private val visibleDestinations = listOf(Destination.Runs, Destination.QuickLog, Destination.Goals, Destination.Settings)

@Composable
fun HabitLabApp(database: HabitDatabase, today: String) {
    var page by remember { mutableStateOf(Destination.Runs) }
    var detailHabit by remember { mutableStateOf<Habit?>(null) }
    var dense by remember(database) { mutableStateOf(database.pref(PREF_DENSE_LAYOUT) == "1") }
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
    remember(database) { themeModePref = database.pref(PREF_THEME_MODE) ?: "system"; accentPref = database.pref(PREF_ACCENT) ?: "green"; hidePointsPref = database.pref(PREF_HIDE_POINTS) == "1"; 0 }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    if (systemDarkState != systemDark) systemDarkState = systemDark
    MaterialTheme(colorScheme = palette.toColorScheme()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            if (builderCopy == null && detailHabit == null)
            NavigationBar(containerColor = surface) {
                visibleDestinations.forEach { dest -> NavigationBarItem(selected = page == dest, onClick = { page = dest }, icon = { DockGlyph(dest) }, label = { Text(dest.title) }, alwaysShowLabel = true)
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
                    if (page == Destination.Runs) Spacer(Modifier.width(12.dp))
                    if (page == Destination.Runs) IconButton(onClick = { builderCopy = true }, modifier = Modifier.size(42.dp).clip(RoundedCornerShape(50)).background(surface)) { Text("＋", color = green, style = MaterialTheme.typography.headlineMedium) }
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
                            color = green, trackColor = palette.track
                        )
                    }
                }
                when (page) {
                    Destination.Runs -> RunsScreen(database, runsRevision, today, dense, onOpenHabit = { detailHabit = it }, onLocalChange = { runsRevision++; noteLocalChange() })
                    Destination.QuickLog -> QuickLogScreen(database, today, runsRevision, onLocalChange = { noteLocalChange() })
                    Destination.Goals -> ListingScreen("Your goals", listOf("Start focused work by 9:30", "Build a steady sleep routine", "Run 5 km comfortably"), "Progress history starts from each goal’s start date.")
                    Destination.Trackers -> ListingScreen("Personal measurements", listOf("Sleep quality · /10", "Daily energy · /10", "Body weight · kg"), "Dated entries and simple history graphs.")
                    Destination.Settings -> SettingsScreen(
                        database = database,
                        syncClient = syncClient, syncMode = syncMode, dense = dense, syncing = syncing, syncStatus = syncStatus,
                        onDense = { dense = it; database.setPref(PREF_DENSE_LAYOUT, if (it) "1" else "0") },
                        onConnect = { openSignIn() }, onSyncNow = { doSync() },
                        onOffline = { syncClient.setMode(SYNC_MODE_OFFLINE); syncMode = SYNC_MODE_OFFLINE }
                    )
                    Destination.Analytics -> ListingScreen("Analytics preview", listOf("Cycle analytics arrive in Phase 2", "Example data is not personal data"), "Analytics is a Phase 2 preview.")
                }
            }
            detailHabit?.let { habit ->
                Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                    HabitDetailPage(database, habit, today, onBack = { detailHabit = null }, onChanged = { runsRevision++ })
                }
            }
            builderCopy?.let { copy ->
                Surface(Modifier.fillMaxSize().padding(padding), color = MaterialTheme.colorScheme.background) {
                    RunBuilderPage(
                        database, today, copy,
                        onCreated = { runsRevision++; noteLocalChange() },
                        onDismiss = { builderCopy = null }
                    )
                }
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
                if (error != null) Text(error, color = palette.error, style = MaterialTheme.typography.bodySmall)
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
            Destination.Settings -> {
                drawCircle(tint, 3f*u, c, style = stroke)
                for (i in 0 until 8) {
                    val a = i * (kotlin.math.PI / 4.0)
                    drawLine(tint, androidx.compose.ui.geometry.Offset(c.x + kotlin.math.cos(a).toFloat() * 7.5f*u, c.y + kotlin.math.sin(a).toFloat() * 7.5f*u), androidx.compose.ui.geometry.Offset(c.x + kotlin.math.cos(a).toFloat() * 10.5f*u, c.y + kotlin.math.sin(a).toFloat() * 10.5f*u), 2.4f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
                drawCircle(tint, 7.5f*u, c, style = stroke)
            }
            Destination.Trackers -> {
                val path = Path().apply { moveTo(3f*u, 15f*u); lineTo(8f*u, 10f*u); lineTo(12f*u, 13f*u); lineTo(18f*u, 5f*u); lineTo(21f*u, 7f*u) }
                drawPath(path, tint, style = Stroke(width = 2f*u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            }
        }
    }
}

/** Full-page run builder. The first choice on the page is where the habits come from: previous run, scratch, or YAML. */
@Composable private fun RunBuilderPage(database: HabitDatabase, today: String, startWithCopy: Boolean, onCreated: () -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var duration by remember { mutableIntStateOf(21) }
    var custom by remember { mutableStateOf(false) }
    var customDays by remember { mutableStateOf("28") }
    var startDate by remember { mutableStateOf(today) }
    val existing = remember(database) { database.schemaQueries.selectHabits().executeAsList() }
    val latestRun = remember(database) { database.schemaQueries.selectRuns().executeAsList().firstOrNull() }
    val previousHabitIds = remember(database, latestRun) { latestRun?.let { database.schemaQueries.selectHabitsForRun(it.uid).executeAsList().map { row -> row.uid } }.orEmpty() }
    val hasPrevious = previousHabitIds.isNotEmpty()
    // 0 = copy previous run, 1 = start from scratch, 2 = from YAML
    var source by remember { mutableIntStateOf(if (startWithCopy && hasPrevious) 0 else 1) }
    val selected = remember { mutableStateListOf<String>().apply { if (startWithCopy && hasPrevious) addAll(previousHabitIds) } }
    val addedHabits = remember { mutableStateListOf<Habit>() }
    var newHabitName by remember { mutableStateOf("") }
    var newHabitPoints by remember { mutableStateOf("1") }
    var newHabitGroup by remember { mutableStateOf("General") }
    var newHabitIcon by remember { mutableStateOf("") }
    var newHabitColor by remember { mutableStateOf("") }
    var showIconPicker by remember { mutableStateOf(false) }
    var intervalMode by remember { mutableStateOf(false) }
    var recurrenceValue by remember { mutableStateOf("3") }
    var editingSourceId by remember { mutableStateOf<String?>(null) }
    var yamlText by remember { mutableStateOf("") }
    var yamlErrors by remember { mutableStateOf<List<String>>(emptyList()) }
    var promptCopied by remember { mutableStateOf(false) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val line = palette.divider

    fun chooseSource(next: Int) {
        source = next
        selected.clear()
        if (next == 0) selected.addAll(previousHabitIds)
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDismiss, modifier = Modifier.semantics { contentDescription = "Back" }) { Text("←", style = MaterialTheme.typography.titleLarge) }
            Text("New run", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Where do the habits come from?", style = MaterialTheme.typography.labelLarge)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = source == 0, enabled = hasPrevious, onClick = { chooseSource(0) }, label = { Text("Previous", maxLines = 1) }, modifier = Modifier.weight(1f))
                FilterChip(selected = source == 1, onClick = { chooseSource(1) }, label = { Text("Scratch", maxLines = 1) }, modifier = Modifier.weight(1f))
                FilterChip(selected = source == 2, onClick = { chooseSource(2) }, label = { Text("YAML", maxLines = 1) }, modifier = Modifier.weight(1f))
            }
            Text(
                when (source) {
                    0 -> "Reuses ${latestRun?.name ?: "the latest run"}’s habits. Untick any you want to leave out."
                    1 -> "Start with an empty run and add habits below."
                    else -> "Ask any AI chat for a run file, paste it here, and load it into the editor."
                },
                color = muted, style = MaterialTheme.typography.bodySmall
            )
            if (source == 2) {
                Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(16.dp)) {
                    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("1 · Copy this prompt into an AI chat", style = MaterialTheme.typography.labelLarge)
                        Text(YAML_GUIDE_PROMPT.lines().take(3).joinToString("\n") + "\n…", color = muted, style = MaterialTheme.typography.bodySmall, maxLines = 4)
                        OutlinedButton(onClick = { clipboard.setText(androidx.compose.ui.text.AnnotatedString(YAML_GUIDE_PROMPT)); promptCopied = true }) {
                            Text(if (promptCopied) "Copied ✓" else "Copy AI prompt")
                        }
                        Text("2 · Paste the YAML it gives you", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
                        OutlinedTextField(
                            value = yamlText, onValueChange = { yamlText = it; yamlErrors = emptyList() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                            placeholder = { Text("name: Reset week\ndays: 14\nhabits:\n  - name: Morning light\n    every: 1 day") }
                        )
                        yamlErrors.forEach { Text(it, color = palette.error, style = MaterialTheme.typography.bodySmall) }
                        Button(
                            enabled = yamlText.isNotBlank(),
                            onClick = {
                                val parsed = parseRunYaml(yamlText)
                                if (!parsed.ok) { yamlErrors = parsed.errors.take(5); return@Button }
                                name = parsed.name
                                if (parsed.start.isNotEmpty()) startDate = parsed.start
                                if (parsed.days in listOf(7, 14, 21)) { duration = parsed.days; custom = false } else { custom = true; customDays = parsed.days.toString() }
                                selected.clear()
                                addedHabits.clear()
                                parsed.habits.forEach { y ->
                                    val recurrence = if (y.weekly > 0) "${y.weekly} days per week" else "Every ${y.interval} days"
                                    addedHabits.add(Habit(y.name, y.group, y.points, recurrence, "I", "habit-${Random.nextLong().toULong().toString(16)}", y.icon))
                                }
                                yamlErrors = emptyList()
                                source = 1
                            }
                        ) { Text("Load into editor") }
                    }
                }
            } else {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Run name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                            newHabitIcon = database.habitIcon(row.uid)
                            intervalMode = row.recurrence_type == "interval"
                            recurrenceValue = row.recurrence_value.toString()
                        }) { Text("Edit") }
                    }
                }
                addedHabits.forEach { added ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HabitGlyph(added, Modifier.size(20.dp), surface)
                        Text("${added.name} · ${added.points} pts · ${added.recurrence}", style = MaterialTheme.typography.bodySmall, color = green, modifier = Modifier.weight(1f))
                        TextButton(onClick = { addedHabits.remove(added) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("Remove", style = MaterialTheme.typography.labelSmall) }
                    }
                }
                HorizontalDivider(color = line)
                Text("Add a habit", style = MaterialTheme.typography.labelLarge)
                OutlinedTextField(value = newHabitName, onValueChange = { newHabitName = it }, label = { Text("Habit name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = newHabitGroup, onValueChange = { newHabitGroup = it }, label = { Text("Group") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(value = newHabitPoints, onValueChange = { newHabitPoints = it.filter(Char::isDigit).take(3) }, label = { Text("Impact pts") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                Text("Icon and color", style = MaterialTheme.typography.labelLarge)
                val pickTint = habitColorOf(newHabitColor) ?: green
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)).background(pickTint.copy(alpha = .2f))
                            .clickable { showIconPicker = true }.semantics { contentDescription = "Choose icon" },
                        contentAlignment = Alignment.Center
                    ) { IconGlyph(newHabitIcon.ifBlank { "checkmark_circle" }, Modifier.size(30.dp), pickTint) }
                    Column(Modifier.weight(1f)) {
                        Text(if (newHabitIcon.isBlank()) "Tap to choose an icon" else newHabitIcon.replace('_', ' '), style = MaterialTheme.typography.bodyMedium)
                        Text("Search 170+ icons", style = MaterialTheme.typography.labelSmall, color = muted)
                    }
                }
                ColorSwatchRow(newHabitColor) { newHabitColor = it }
                if (showIconPicker) IconPickerSheet(newHabitIcon, pickTint, onPick = { newHabitIcon = it; showIconPicker = false }, onDismiss = { showIconPicker = false })
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = !intervalMode, onClick = { intervalMode = false }, label = { Text("Days per week") })
                    FilterChip(selected = intervalMode, onClick = { intervalMode = true }, label = { Text("Every N days") })
                }
                OutlinedTextField(value = recurrenceValue, onValueChange = { recurrenceValue = it.filter(Char::isDigit).take(3) }, label = { Text(if (intervalMode) "Interval days" else "Target days per week") }, singleLine = true)
                TextButton(onClick = {
                    val habitName = newHabitName.trim()
                    if (habitName.isNotEmpty()) {
                        editingSourceId?.let { selected.remove(it) }
                        val id = "habit-${Random.nextLong().toULong().toString(16)}"
                        val count = recurrenceValue.toIntOrNull()?.coerceIn(1, if (intervalMode) 365 else 7) ?: 3
                        addedHabits.add(Habit(habitName, newHabitGroup.ifBlank { "General" }, newHabitPoints.toIntOrNull()?.coerceAtLeast(0) ?: 1, if (intervalMode) "Every $count days" else "$count days per week", "I", id, newHabitIcon, newHabitColor))
                        newHabitName = ""
                        newHabitIcon = ""
                        newHabitColor = ""
                        editingSourceId = null
                    }
                }, enabled = newHabitName.isNotBlank()) { Text("＋ Add habit to this run") }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (source != 2) {
            HorizontalDivider(color = line)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("Cancel") }
                Button(
                    modifier = Modifier.weight(2f),
                    enabled = selected.isNotEmpty() || addedHabits.isNotEmpty(),
                    onClick = {
                        val days = if (custom) customDays.toLongOrNull()?.coerceIn(1, 365) ?: 21L else duration.toLong()
                        val runName = name.ifBlank { "${days}-day run" }
                        val runId = "run-$startDate-${Random.nextLong().toULong().toString(16)}"
                        database.transaction {
                            database.schemaQueries.insertRun(runId, runName, startDate, days)
                            addedHabits.forEach { habit ->
                                val count = Regex("\\d+").find(habit.recurrence)?.value?.toLongOrNull() ?: 3L
                                database.schemaQueries.insertHabit(habit.uid(), habit.name, habit.group, 1L, habit.points.toLong(), if (habit.recurrence.startsWith("Every ")) "interval" else "weekly", count)
                                database.setHabitIcon(habit.uid(), habit.icon)
                                database.setHabitColor(habit.uid(), habit.color)
                            }
                            selected.forEach { database.schemaQueries.attachHabitToRun("$runId:$it", runId, it) }
                            addedHabits.forEach { database.schemaQueries.attachHabitToRun("$runId:${it.uid()}", runId, it.uid()) }
                        }
                        onCreated()
                        onDismiss()
                    }
                ) { Text("Create run") }
            }
        }
    }
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

internal fun swipeActionGlyph(action: QuickSwipeAction): String =
    when (action) {
        QuickSwipeAction.Done -> "✓"
        QuickSwipeAction.Partial -> "◐"
        QuickSwipeAction.Skipped -> "✕"
        QuickSwipeAction.Comment -> "✎"
    }

internal fun swipeActionLabel(action: QuickSwipeAction): String =
    when (action) {
        QuickSwipeAction.Done -> "Done"
        QuickSwipeAction.Partial -> "Partial"
        QuickSwipeAction.Skipped -> "Skipped"
        QuickSwipeAction.Comment -> "Comment"
    }

internal fun swipeActionContainer(action: QuickSwipeAction): androidx.compose.ui.graphics.Color =
    when (action) {
        QuickSwipeAction.Done -> palette.accentSoft
        QuickSwipeAction.Partial -> palette.partialSoft
        QuickSwipeAction.Skipped -> palette.skippedSoft
        QuickSwipeAction.Comment -> palette.accentSoft2
    }

internal fun swipeActionContent(action: QuickSwipeAction): androidx.compose.ui.graphics.Color =
    when (action) {
        QuickSwipeAction.Done -> palette.accent
        QuickSwipeAction.Partial -> palette.partial
        QuickSwipeAction.Skipped -> palette.error
        QuickSwipeAction.Comment -> palette.accent
    }

/** Gmail-style action background revealed behind the last swiped card. Fades in from the draw layer. */
@Composable private fun SwipeActionBackground(
    action: QuickSwipeAction?,
    progress: () -> Float,
    positive: Boolean,
    shape: RoundedCornerShape = RoundedCornerShape(26.dp),
) {
    if (action == null) {
        Spacer(Modifier.fillMaxSize())
        return
    }
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = progress().coerceIn(0f, 1f) }.clip(shape).background(swipeActionContainer(action))) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (positive) Arrangement.Start else Arrangement.End
        ) {
            Text(
                "${swipeActionGlyph(action)}  ${swipeActionLabel(action)}",
                color = swipeActionContent(action),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/** Face of a Quick Log card; the card underneath renders the same face so promotion is seamless. */
@Composable private fun QuickLogCardFace(h: Habit, hint: String) {
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().weight(0.45f).background(Brush.verticalGradient(listOf(palette.accentSoft, palette.accentSoft2))), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val center = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension * .47f
                listOf(.55f, .78f, 1f).forEach { ratio -> drawCircle(palette.muted.copy(alpha = .28f), radius = maxRadius * ratio, center = center, style = Stroke(width = 1.dp.toPx())) }
            }
            Text("${h.group.uppercase()}  ·  DUE", Modifier.align(Alignment.TopStart).padding(start = 17.dp, top = 15.dp), color = green, style = MaterialTheme.typography.labelMedium)
            Box(Modifier.size(142.dp).clip(RoundedCornerShape(36.dp)).background(green), contentAlignment = Alignment.Center) { HabitGlyph(h, Modifier.size(56.dp), green, palette.onAccent) }
        }
        Column(Modifier.fillMaxWidth().weight(0.55f).verticalScroll(rememberScrollState()).padding(horizontal = 23.dp, vertical = 19.dp)) {
            Text("${h.recurrence}  ·  due today", color = muted, style = MaterialTheme.typography.labelMedium)
            Text(h.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Text("${h.iteration}  ·  ${h.recurrence}", color = muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 5.dp))
            Text("${h.points} impact points", color = green, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
            Text(hint, color = muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** Bordered corner stamp, matching the web prototype's DONE/SKIP stamp treatment. */
@Composable private fun BoxScope.SwipeStamp(action: QuickSwipeAction, alpha: () -> Float, positive: Boolean) {
    val content = swipeActionContent(action)
    Text(
        swipeStampLabel(action),
        Modifier.align(if (positive) Alignment.TopStart else Alignment.TopEnd)
            .padding(top = 18.dp, start = 18.dp, end = 18.dp)
            .graphicsLayer { rotationZ = if (positive) -12f else 12f; this.alpha = alpha().coerceIn(0f, 1f) }
            .border(3.dp, content, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = content,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
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
    val offsetY = remember { Animatable(0f) }
    var cardWidthPx by remember { mutableFloatStateOf(1000f) }
    val scope = rememberCoroutineScope()
    val commitThresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val flingPx = with(LocalDensity.current) { 700.dp.toPx() }
    val flyOutSpeedPx = with(LocalDensity.current) { 2200.dp.toPx() }
    val haptics = LocalHapticFeedback.current
    val dragSide by remember { derivedStateOf { kotlin.math.sign(offsetX.value).toInt() } }
    val dragAction by remember { derivedStateOf { when (dragSide) { 1 -> swipeRight; -1 -> swipeLeft; else -> null } } }
    val pastCommit by remember { derivedStateOf { kotlin.math.abs(offsetX.value) >= commitThresholdPx } }
    LaunchedEffect(pastCommit) { if (pastCommit && !animating) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val hint = "Swipe right for ${swipeRight.key} · left for ${swipeLeft.key}"
    val runHabits = remember(database, runsRevision) {
        val latestRun = database.schemaQueries.selectRuns().executeAsList().firstOrNull()
        latestRun?.let { database.schemaQueries.selectHabitsForRun(it.uid).executeAsList().map { row ->
            val recurrence = if (row.recurrence_type == "interval") "Every ${row.recurrence_value} days" else "${row.recurrence_value} days per week"
            Habit(row.title, row.group_name, row.impact_points.toInt(), recurrence, "I", row.uid, database.habitIcon(row.uid))
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
    fun springBack(velocity: Float) {
        scope.launch { offsetX.animateTo(0f, spring(dampingRatio = .62f, stiffness = 380f), initialVelocity = velocity) }
        scope.launch { offsetY.animateTo(0f, spring(dampingRatio = .75f, stiffness = 380f)) }
    }
    /** Throws the top card off-screen at the finger's speed, then promotes the card underneath in the same frame. */
    fun flyOut(action: QuickSwipeAction, habit: Habit, direction: Int, velocity: Float = 0f) {
        if (animating) return
        val state = swipeCheckInState(action)
        if (state == null) {
            commentOpen = true
            springBack(velocity)
            return
        }
        animating = true
        scope.launch {
            val target = direction * cardWidthPx * 1.5f
            val speed = maxOf(kotlin.math.abs(velocity), flyOutSpeedPx)
            val millis = (kotlin.math.abs(target - offsetX.value) / speed * 1000f).toInt().coerceIn(120, 320)
            val easing = if (kotlin.math.abs(velocity) >= flingPx) LinearEasing else FastOutLinearInEasing
            offsetX.animateTo(target, tween(millis, easing = easing))
            record(state, habit)
            offsetX.snapTo(0f)
            offsetY.snapTo(0f)
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
        Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { cardWidthPx = it.width.toFloat() }) {
            if (index + 1 >= due.size) SwipeActionBackground(
                action = dragAction,
                progress = { swipeProgress(offsetX.value, commitThresholdPx) },
                positive = dragSide >= 0
            )
            if (index + 1 < due.size) {
                // The next card waits underneath and grows to full size as the top card leaves.
                Card(
                    Modifier.fillMaxSize().zIndex(0f)
                        .graphicsLayer {
                            val lift = (kotlin.math.abs(offsetX.value) / (size.width * 0.5f)).coerceIn(0f, 1f)
                            val scale = 0.94f + 0.06f * lift
                            scaleX = scale; scaleY = scale
                            translationY = (1f - lift) * 14.dp.toPx()
                        },
                    colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(26.dp)
                ) {
                    QuickLogCardFace(due[index + 1], hint)
                }
            }
            Card(
                Modifier.fillMaxSize().zIndex(1f)
                    .graphicsLayer {
                        translationX = offsetX.value
                        translationY = offsetY.value
                        rotationZ = offsetX.value / size.width * 14f
                        transformOrigin = TransformOrigin(0.5f, 1.1f)
                    }
                    .semantics { customActions = listOf(
                        CustomAccessibilityAction("Mark done") { flyOut(QuickSwipeAction.Done, h, 1); true },
                        CustomAccessibilityAction("Mark partial") { flyOut(QuickSwipeAction.Partial, h, -1); true },
                        CustomAccessibilityAction("Mark skipped") { flyOut(QuickSwipeAction.Skipped, h, -1); true },
                        CustomAccessibilityAction("Add comment") { commentOpen = true; true }
                    ) }
                    .horizontalSwipe(
                        key = h.uid(),
                        canStart = { !animating },
                        onDrag = { dx, dy ->
                            val nextX = offsetX.value + dx
                            val nextY = offsetY.value + dy * 0.4f
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetX.snapTo(nextX) }
                            scope.launch(start = CoroutineStart.UNDISPATCHED) { offsetY.snapTo(nextY) }
                        },
                        onRelease = { velocity ->
                            val direction = swipeReleaseDirection(offsetX.value, velocity, commitThresholdPx, flingPx)
                            if (direction == 0) springBack(velocity)
                            else flyOut(if (direction > 0) swipeRight else swipeLeft, h, direction, velocity)
                        }
                    ),
                colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(26.dp)
            ) {
                Box(Modifier.fillMaxSize()) {
                    QuickLogCardFace(h, hint)
                    dragAction?.let { action ->
                        SwipeStamp(action = action, alpha = { (kotlin.math.abs(offsetX.value) - 8f) / 90f }, positive = dragSide >= 0)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { flyOut(QuickSwipeAction.Partial, h, -1) }, enabled = !animating, modifier = Modifier.weight(1f).height(54.dp).semantics { contentDescription = "Partial" }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.buttonColors(containerColor = palette.partialSoft, contentColor = palette.partial)) { Text("◐  Partial") }
            FilledTonalButton(onClick = { commentOpen = true }, enabled = !animating, modifier = Modifier.weight(1f).height(54.dp).semantics { contentDescription = "Comment" }, shape = RoundedCornerShape(16.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.accentSoft2, contentColor = green)) { Text("✎  Comment") }
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

@Composable private fun ListingScreen(title: String, rows: List<String>, note: String) {
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text(title, style = MaterialTheme.typography.headlineSmall); Text(note, color = muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp)) }
        items(rows) { row -> Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) { Text(row, Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.titleMedium) } }
    }
}

@Composable private fun SettingsScreen(
    database: HabitDatabase,
    syncClient: SyncClient,
    syncMode: String?,
    dense: Boolean,
    syncing: Boolean,
    syncStatus: String?,
    onDense: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onSyncNow: () -> Unit,
    onOffline: () -> Unit,
) {
    val connected = syncMode == SYNC_MODE_SERVER
    val url = syncClient.serverUrl()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SYNC SERVER", style = MaterialTheme.typography.labelMedium, color = green)
                Text(if (connected) "Connected" else "Offline — data stays on this phone", style = MaterialTheme.typography.titleSmall)
                if (connected && url != null) {
                    Text(url, style = MaterialTheme.typography.bodySmall, color = muted)
                    syncClient.account()?.let { Text("Signed in as $it", style = MaterialTheme.typography.bodySmall, color = muted) }
                    Text("The web app lives at the same address: open it in any browser and sign in with this account.", style = MaterialTheme.typography.bodySmall, color = muted)
                    syncStatus?.let { Text(if (syncing) "Syncing…" else it, style = MaterialTheme.typography.labelSmall, color = muted) }
                } else {
                    Text("Connect to your sync server to share runs with your other devices and the web app.", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onConnect) { Text(if (connected) "Change server" else "Connect") }
                    if (connected) {
                        OutlinedButton(onClick = onSyncNow, enabled = !syncing) { Text("Sync now") }
                    }
                }
                if (connected) TextButton(onClick = onOffline) { Text("Switch to offline mode") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("APPEARANCE", style = MaterialTheme.typography.labelMedium, color = green)
                Text("Theme", style = MaterialTheme.typography.titleSmall)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                        FilterChip(selected = themeModePref == key, onClick = { themeModePref = key; database.setPref(PREF_THEME_MODE, key) }, label = { Text(label, maxLines = 1) }, modifier = Modifier.weight(1f))
                    }
                }
                Text("Accent color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ACCENTS.forEach { option ->
                        val on = accentPref == option.key
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).background(option.seed)
                                .border(if (on) 3.dp else 0.dp, ink, CircleShape)
                                .clickable { accentPref = option.key; database.setPref(PREF_ACCENT, option.key) }
                                .semantics { contentDescription = "Accent ${option.label}" },
                            contentAlignment = Alignment.Center
                        ) { if (on) Text("✓", color = androidx.compose.ui.graphics.Color(0xFF111111), style = MaterialTheme.typography.labelLarge) }
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("HABIT LIST", style = MaterialTheme.typography.labelMedium, color = green)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dense layout", style = MaterialTheme.typography.titleSmall)
                        Text("For long lists: one line per habit with small day dots. Pick the day from the strip above the list; swiping still logs.", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Switch(checked = dense, onCheckedChange = onDense, modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = "Dense layout" })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide points", style = MaterialTheme.typography.titleSmall)
                        Text("Hide impact points on habit rows and the run summary.", style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                    Switch(checked = hidePointsPref, onCheckedChange = { hidePointsPref = it; database.setPref(PREF_HIDE_POINTS, if (it) "1" else "0") }, modifier = Modifier.padding(start = 12.dp).semantics { contentDescription = "Hide points" })
                }
            }
        }
    }
}
