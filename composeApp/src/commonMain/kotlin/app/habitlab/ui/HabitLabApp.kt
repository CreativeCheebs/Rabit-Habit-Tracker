@file:Suppress("ktlint:standard:no-wildcard-imports")

package app.habitlab.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.habitlab.db.HabitDatabase
import app.habitlab.sync.SYNC_MODE_OFFLINE
import app.habitlab.sync.SYNC_MODE_SERVER
import app.habitlab.sync.SignInProblem
import app.habitlab.sync.SyncClient
import app.habitlab.sync.SyncOutcome
import app.habitlab.sync.createHttpClient
import app.habitlab.sync.isUnencryptedRemote
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

internal val ink get() = palette.ink
internal val muted get() = palette.muted
internal val surface get() = palette.surface
internal val green get() = palette.accent
internal val hero get() = palette.hero

internal data class Habit(
    val name: String,
    val group: String,
    val points: Int,
    val recurrence: String,
    val iteration: String = "I",
    val id: String = "",
    val icon: String = "",
    val color: String = "",
)

internal val sampleHabits =
    listOf(
        Habit("Morning light", "Wellbeing", 2, "Daily"),
        Habit("Sleep before 11:30 PM", "Wellbeing", 2, "Every 3 days", "II"),
        Habit("Plan the day", "Focus", 3, "3 days per week"),
        Habit("Focused work block", "Focus", 4, "3 days per week"),
    )

internal fun Habit.uid(): String =
    id.ifBlank {
        when (name) {
            "Morning light" -> "morning-light"
            "Sleep before 11:30 PM" -> "sleep-routine"
            "Plan the day" -> "plan-day"
            else -> "focus-block"
        }
    }

internal fun addIsoDays(
    date: String,
    amount: Int,
): String {
    val parts = date.split("-")
    var year = parts.getOrNull(0)?.toIntOrNull() ?: return date
    var month = parts.getOrNull(1)?.toIntOrNull() ?: return date
    var day = parts.getOrNull(2)?.toIntOrNull() ?: return date

    fun monthLength(
        y: Int,
        m: Int,
    ) = when (m) {
        2 ->
            if (y % 4 == 0 &&
                (y % 100 != 0 || y % 400 == 0)
            ) {
                29
            } else {
                28
            }
        ; 4, 6, 9, 11 -> 30
        else -> 31
    }
    if (amount >=
        0
    ) {
        repeat(amount) {
            day++
            if (day > monthLength(year, month)) {
                day = 1
                month++
                if (month > 12) {
                    month = 1
                    year++
                }
            }
        }
    } else {
        repeat(-amount) {
            day--
            if (day < 1) {
                month--
                if (month < 1) {
                    month = 12
                    year--
                }
                day = monthLength(year, month)
            }
        }
    }
    return "%04d-%02d-%02d".format(year, month, day)
}

private enum class Destination(
    val title: String,
    val glyph: String,
) {
    Runs("Today", "◷"),
    Analytics("Stats", "▥"),
    QuickLog("Quick Log", "⇆"),
    Goals("Goals", "◎"),
    Trackers("Trackers", "⌁"),
    Share("Share", "⇪"),
    Settings("Settings", "⚙"),
}

private val defaultDestinations =
    listOf(Destination.Runs, Destination.QuickLog, Destination.Goals, Destination.Share, Destination.Settings)
private val requiredDestinations = setOf(Destination.Runs, Destination.Settings)

private fun Destination.preferenceKey(): String =
    when (this) {
        Destination.Runs -> "runs"
        Destination.QuickLog -> "quick_log"
        Destination.Goals -> "goals"
        Destination.Settings -> "settings"
        Destination.Analytics -> "analytics"
        Destination.Trackers -> "trackers"
        Destination.Share -> "share"
    }

private fun readScreenOrder(saved: String?): List<Destination> {
    val restored =
        saved
            .orEmpty()
            .split(',')
            .mapNotNull { key -> defaultDestinations.firstOrNull { it.preferenceKey() == key } }
            .distinct()
    return restored + defaultDestinations.filterNot { it in restored }
}

private fun readHiddenScreens(saved: String?): Set<Destination> =
    saved
        .orEmpty()
        .split(',')
        .mapNotNull { key -> defaultDestinations.firstOrNull { it.preferenceKey() == key } }
        .filterNot { it in requiredDestinations }
        .toSet()

@Composable
fun HabitLabApp(
    database: HabitDatabase,
    today: String,
    onExportBackup: () -> Unit = {},
    onImportBackup: () -> Unit = {},
) {
    var page by remember { mutableStateOf(Destination.Runs) }
    var screenOrder by remember(database) { mutableStateOf(readScreenOrder(database.pref(PREF_SCREEN_ORDER))) }
    var hiddenScreens by remember(database) { mutableStateOf(readHiddenScreens(database.pref(PREF_HIDDEN_SCREENS))) }
    val visibleDestinations = screenOrder.filterNot { it in hiddenScreens }
    val pageHistory = remember { mutableStateListOf<Destination>() }
    var backProgress by remember { mutableFloatStateOf(0f) }
    var detailHabit by remember { mutableStateOf<Habit?>(null) }
    var dense by remember(database) { mutableStateOf(database.pref(PREF_DENSE_LAYOUT) == "1") }
    var runsRevision by remember { mutableIntStateOf(0) }
    var builderCopy by remember { mutableStateOf<Boolean?>(null) }
    var builderSource by remember { mutableStateOf<Int?>(null) }
    var editRunUid by remember { mutableStateOf<String?>(null) }
    var manageRuns by remember { mutableStateOf(false) }
    var groupHabits by remember(database) { mutableStateOf(database.pref("ui_group_habits") != "0") }
    var historyDays by remember(database) { mutableIntStateOf(database.pref("ui_history_days")?.toIntOrNull()?.coerceIn(7, 21) ?: 7) }
    val syncClient = remember(database) { SyncClient(database, createHttpClient()) }
    var syncMode by remember(database) { mutableStateOf(syncClient.mode()) }
    var syncing by remember(database) { mutableStateOf(false) }
    var syncDirty by remember(database) { mutableStateOf(false) }
    var syncStatus by remember(database) { mutableStateOf<String?>(null) }
    var syncProgress by remember(database) { mutableStateOf<Pair<Long, Long?>?>(null) }
    var showFirstStart by remember(database) { mutableStateOf(syncClient.mode() == null) }
    var showSignIn by remember { mutableStateOf(false) }
    var signInError by remember { mutableStateOf<String?>(null) }

    fun openSignIn() {
        signInError = null
        showFirstStart = false
        showSignIn = true
    }
    val syncScope = rememberCoroutineScope()
    var pendingSwitch by remember { mutableStateOf<Pair<SignInProblem.SwitchNeedsConfirmation, (Boolean) -> Unit>?>(null) }

    fun describeOutcome(outcome: SyncOutcome): String =
        when (outcome) {
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
                val repaired = database.ensureSingleActiveRun()
                if (repaired > 0) {
                    runsRevision++
                    syncDirty = true
                }
            }
        }
    }

    fun noteLocalChange() {
        syncDirty = true
        doSync()
    }

    LaunchedEffect(database) {
        val repaired = database.ensureSingleActiveRun()
        if (repaired > 0) {
            runsRevision++
            noteLocalChange()
        }
    }

    fun attemptSignIn(
        url: String,
        username: String,
        password: String,
        create: Boolean,
        replaceLocalData: Boolean,
    ) {
        syncScope.launch {
            try {
                syncClient.signIn(url, username, password, create, replaceLocalData)
                syncMode = SYNC_MODE_SERVER
                signInError = null
                showSignIn = false
                if (replaceLocalData) runsRevision++
                doSync()
            } catch (e: SignInProblem.SwitchNeedsConfirmation) {
                pendingSwitch = e to { replace -> attemptSignIn(url, username, password, create, replace) }
            } catch (e: SignInProblem) {
                signInError = e.message
            } catch (e: IllegalArgumentException) {
                signInError = e.message
            }
        }
    }
    LaunchedEffect(syncMode, database) {
        if (syncMode != SYNC_MODE_SERVER) return@LaunchedEffect
        doSync()
        while (true) {
            kotlinx.coroutines.delay(20_000)
            if (syncDirty) doSync()
        }
    }
    remember(database) {
        themeModePref = database.pref(PREF_THEME_MODE) ?: "system"
        accentPref = database.pref(PREF_ACCENT) ?: "green"
        stylePref = if (database.pref(PREF_STYLE) == STYLE_WA) STYLE_WA else STYLE_CLASSIC
        hidePointsPref =
            database.pref(PREF_HIDE_POINTS) == "1"
        0
    }
    val canNavigateBack = builderCopy != null || detailHabit != null || manageRuns || pageHistory.isNotEmpty()
    PlatformBackHandler(
        enabled = canNavigateBack && !showFirstStart && !showSignIn && pendingSwitch == null,
        onProgress = { backProgress = it },
        onBack = {
            when {
                builderCopy != null -> builderCopy = null
                detailHabit != null -> detailHabit = null
                manageRuns -> manageRuns = false
                pageHistory.isNotEmpty() -> page = pageHistory.removeAt(pageHistory.lastIndex)
            }
        },
    )
    val peekModifier =
        Modifier.graphicsLayer {
            scaleX = 1f - backProgress * 0.08f
            scaleY = 1f - backProgress * 0.08f
            alpha = 1f - backProgress * 0.2f
        }
    var lastDetail by remember { mutableStateOf<Habit?>(null) }
    if (detailHabit != null) lastDetail = detailHabit
    var lastBuilderCopy by remember { mutableStateOf(true) }
    if (builderCopy != null) lastBuilderCopy = builderCopy!!
    val streak = remember(database, runsRevision, today) { currentRunStreak(database, today) }
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    if (systemDarkState != systemDark) systemDarkState = systemDark
    MaterialTheme(colorScheme = palette.toColorScheme(), typography = appTypography()) {
        Scaffold(containerColor = MaterialTheme.colorScheme.background, bottomBar = {
            if (builderCopy == null && detailHabit == null && !manageRuns) {
                Column {
                    HorizontalDivider(color = palette.divider)
                    NavigationBar(containerColor = surface, tonalElevation = 0.dp) {
                        visibleDestinations.forEach { dest ->
                            NavigationBarItem(
                                selected = page == dest,
                                onClick = {
                                    if (page != dest) {
                                        pageHistory.add(page)
                                        page = dest
                                    }
                                },
                                icon = { DockGlyph(dest) },
                                label = { Text(dest.title, style = MaterialTheme.typography.labelSmall) },
                                alwaysShowLabel = true,
                                colors =
                                    NavigationBarItemDefaults.colors(
                                        selectedIconColor = ink,
                                        selectedTextColor = ink,
                                        indicatorColor = palette.accentSoft,
                                        unselectedIconColor = muted,
                                        unselectedTextColor = muted,
                                    ),
                            )
                        }
                    }
                }
            }
        }) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 58.dp).padding(start = 16.dp, end = 6.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (page == Destination.Runs) {
                        Text(
                            "Today",
                            style = MaterialTheme.typography.displaySmall,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                        if (streak > 0) StreakPill(streak)
                        if (syncMode == SYNC_MODE_SERVER) {
                            RoundIconButton(
                                LinePaths.SYNC,
                                if (syncing) "Syncing" else "Sync now",
                                onClick = { doSync() },
                                enabled = !syncing,
                                tint = palette.accent,
                            )
                        }
                        RoundIconButton(
                            LinePaths.PLUS,
                            "New run",
                            onClick = {
                                editRunUid = null
                                builderSource = null
                                builderCopy = true
                            },
                            background = palette.surfaceAlt,
                            tint = palette.accent,
                        )
                    } else {
                        Text(
                            page.title,
                            style = MaterialTheme.typography.displaySmall,
                            modifier = Modifier.weight(1f).semantics { heading() },
                        )
                    }
                    if (page == Destination.QuickLog) {
                        Text(
                            "~5 min",
                            style = MaterialTheme.typography.labelMedium,
                            color = muted,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                }
                if (page == Destination.Runs && syncMode == SYNC_MODE_SERVER) {
                    val progress = syncProgress
                    if (syncing && progress != null && progress.second != null && progress.second!! > 0) {
                        LinearProgressIndicator(
                            progress = { (progress.first.toFloat() / progress.second!!.toFloat()).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            color = green,
                            trackColor = palette.track,
                        )
                    }
                }

                @Composable
                fun DestinationContent(destination: Destination) {
                    when (destination) {
                        Destination.Runs ->
                            RunsScreen(
                                database = database,
                                revision = runsRevision,
                                today = today,
                                dense = dense,
                                groupHabits = groupHabits,
                                historyDays = historyDays,
                                onManageRuns = { manageRuns = true },
                                onOpenHabit = { detailHabit = it },
                                onLocalChange = {
                                    runsRevision++
                                    noteLocalChange()
                                },
                                onStartNextRun = {
                                    editRunUid = null
                                    builderSource = null
                                    builderCopy = true
                                },
                                onBuildRun = { source ->
                                    editRunUid = null
                                    builderSource = source
                                    builderCopy = true
                                },
                            )
                        Destination.QuickLog ->
                            QuickLogScreen(
                                database = database,
                                today = today,
                                runsRevision = runsRevision,
                                onLocalChange = { noteLocalChange() },
                                onStartNextRun = {
                                    editRunUid = null
                                    builderSource = null
                                    builderCopy = true
                                },
                                onBackToRuns = {
                                    page = Destination.Runs
                                },
                            )
                        Destination.Goals -> GoalsScreen(database, today, onLocalChange = { noteLocalChange() })
                        Destination.Share ->
                            ShareHabitsPage(
                                database,
                                today,
                                runsRevision,
                                onBack = { page = Destination.Runs },
                            )
                        Destination.Trackers ->
                            ListingScreen(
                                "Personal measurements",
                                listOf("Sleep quality · /10", "Daily energy · /10", "Body weight · kg"),
                                "Dated entries and simple history graphs.",
                            )
                        Destination.Settings ->
                            SettingsScreen(
                                database = database,
                                syncClient = syncClient,
                                syncMode = syncMode,
                                dense = dense,
                                syncing = syncing,
                                syncStatus = syncStatus,
                                screenOrder = screenOrder,
                                hiddenScreens = hiddenScreens,
                                onScreenOrderChange = { order ->
                                    screenOrder = order
                                    database.setPref(PREF_SCREEN_ORDER, order.joinToString(",") { it.preferenceKey() })
                                },
                                onScreenVisibilityChange = { destination, show ->
                                    hiddenScreens = if (show) hiddenScreens - destination else hiddenScreens + destination
                                    database.setPref(PREF_HIDDEN_SCREENS, hiddenScreens.joinToString(",") { it.preferenceKey() })
                                },
                                onResetScreens = {
                                    screenOrder = defaultDestinations
                                    hiddenScreens = emptySet()
                                    database.setPref(PREF_SCREEN_ORDER, "")
                                    database.setPref(PREF_HIDDEN_SCREENS, "")
                                },
                                onDense = {
                                    dense = it
                                    database.setPref(PREF_DENSE_LAYOUT, if (it) "1" else "0")
                                },
                                groupHabits = groupHabits,
                                onGroupHabits = {
                                    groupHabits = it
                                    database.setPref("ui_group_habits", if (it) "1" else "0")
                                },
                                historyDays = historyDays,
                                onHistoryDays = {
                                    historyDays = it
                                    database.setPref("ui_history_days", it.toString())
                                },
                                onConnect = { openSignIn() },
                                onSyncNow = { doSync() },
                                onOffline = {
                                    syncClient.setMode(SYNC_MODE_OFFLINE)
                                    syncMode = SYNC_MODE_OFFLINE
                                },
                                onExportBackup = onExportBackup,
                                onImportBackup = onImportBackup,
                            )
                        Destination.Analytics ->
                            ListingScreen(
                                "Analytics preview",
                                listOf("Cycle analytics arrive in Phase 2", "Example data is not personal data"),
                                "Analytics is a Phase 2 preview.",
                            )
                    }
                }
                Box(Modifier.fillMaxSize()) {
                    val peekDestination = pageHistory.lastOrNull()
                    val peekingPage =
                        backProgress > 0f &&
                            peekDestination != null &&
                            builderCopy == null &&
                            detailHabit == null &&
                            !manageRuns
                    if (peekingPage) DestinationContent(peekDestination!!)
                    Crossfade(
                        targetState = page,
                        modifier = if (peekingPage) peekModifier else Modifier,
                        label = "navigation",
                    ) { destination -> DestinationContent(destination) }
                }
            }
            AnimatedVisibility(visible = manageRuns, enter = fadeIn(), exit = fadeOut()) {
                Surface(
                    Modifier.fillMaxSize().padding(padding).then(
                        if (builderCopy ==
                            null
                        ) {
                            peekModifier
                        } else {
                            Modifier
                        },
                    ),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    RunManagementPage(database, runsRevision, onBack = { manageRuns = false }, onEdit = { uid ->
                        editRunUid = uid
                        builderCopy = true
                    }, onChanged = {
                        runsRevision++
                        noteLocalChange()
                    })
                }
            }
            AnimatedVisibility(visible = detailHabit != null, enter = fadeIn(), exit = fadeOut()) {
                lastDetail?.let { habit ->
                    Surface(Modifier.fillMaxSize().padding(padding).then(peekModifier), color = MaterialTheme.colorScheme.background) {
                        HabitDetailPage(database, habit, today, onBack = { detailHabit = null }, onChanged = { runsRevision++ })
                    }
                }
            }
            AnimatedVisibility(visible = builderCopy != null, enter = fadeIn(), exit = fadeOut()) {
                Surface(Modifier.fillMaxSize().padding(padding).then(peekModifier), color = MaterialTheme.colorScheme.background) {
                    RunBuilderPage(
                        database,
                        today,
                        lastBuilderCopy,
                        editRunUid,
                        initialSource = builderSource,
                        onCreated = {
                            runsRevision++
                            noteLocalChange()
                        },
                        onRunsChanged = {
                            runsRevision++
                            noteLocalChange()
                        },
                        onDismiss = {
                            builderCopy = null
                        },
                    )
                }
            }
            if (showFirstStart) {
                WelcomeScreen(
                    onConnect = { openSignIn() },
                    onOffline = {
                        syncClient.setMode(SYNC_MODE_OFFLINE)
                        syncMode = SYNC_MODE_OFFLINE
                        showFirstStart = false
                    },
                )
            }
            if (showSignIn) {
                SignInDialog(
                    initialUrl = syncClient.serverUrl() ?: "",
                    initialUsername = syncClient.account() ?: "",
                    error = signInError,
                    onSignIn = { url, username, password, create -> attemptSignIn(url, username, password, create, false) },
                    onOffline = {
                        syncClient.setMode(SYNC_MODE_OFFLINE)
                        syncMode = SYNC_MODE_OFFLINE
                        showSignIn = false
                        showFirstStart = false
                    },
                    onDismiss = {
                        showSignIn = false
                        // Nothing was chosen yet: go back to the welcome instead of leaving the choice unset.
                        if (syncClient.mode() == null) showFirstStart = true
                    },
                )
            }
            pendingSwitch?.let { (problem, attempt) ->
                AlertDialog(
                    onDismissRequest = { pendingSwitch = null },
                    title = { Text("Switch account?") },
                    text = {
                        Text(
                            "This phone is connected to ${problem.account} at ${problem.url}. " +
                                "Switching removes that account's habits from this phone " +
                                "(they stay on that server) and downloads the new account's.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            pendingSwitch = null
                            attempt(true)
                        }) { Text("Switch") }
                    },
                    dismissButton = { TextButton(onClick = { pendingSwitch = null }) { Text("Cancel") } },
                )
            }
        }
    }
}

@Composable private fun SignInDialog(
    initialUrl: String,
    initialUsername: String,
    error: String?,
    onSignIn: (String, String, String, Boolean) -> Unit,
    onOffline: () -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initialUrl) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var repeat by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    val mismatch = creating && repeat.isNotEmpty() && repeat != password
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "Create an account" else "Connect to sync server") },
        text = {
            val passwordVisual = if (showPassword) VisualTransformation.None else PasswordVisualTransformation()
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = url, onValueChange = {
                    url = it
                }, label = { Text("Server address") }, placeholder = { Text("http://192.168.0.8:8788") }, singleLine = true)
                OutlinedTextField(value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true)
                OutlinedTextField(value = password, onValueChange = {
                    password = it
                }, label = {
                    Text(if (creating) "Password (8+ characters)" else "Password")
                }, singleLine = true, visualTransformation = passwordVisual, trailingIcon = {
                    TextButton(onClick = { showPassword = !showPassword }) {
                        Text(if (showPassword) "Hide" else "Show")
                    }
                })
                if (creating) {
                    OutlinedTextField(value = repeat, onValueChange = {
                        repeat = it
                    }, label = {
                        Text("Repeat password")
                    }, singleLine = true, isError = mismatch, supportingText = {
                        if (mismatch) Text("Passwords do not match")
                    }, visualTransformation = passwordVisual, trailingIcon = {
                        TextButton(onClick = { showPassword = !showPassword }) {
                            Text(if (showPassword) "Hide" else "Show")
                        }
                    })
                }
                TextButton(onClick = {
                    creating = !creating
                    repeat = ""
                }) { Text(if (creating) "I already have an account" else "New here? Create an account") }
                if (isUnencryptedRemote(
                        url,
                    )
                ) {
                    Text(
                        "This address is not encrypted. Use https:// unless the server is on your own home network.",
                        color = palette.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (error != null) Text(error, color = palette.error, style = MaterialTheme.typography.bodySmall)
                Text(
                    "First connection downloads everything; later syncs are small and silent.",
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSignIn(url.trim(), username.trim(), password, creating) },
                enabled =
                    url.isNotBlank() &&
                        username.isNotBlank() &&
                        password.isNotEmpty() &&
                        (!creating || (repeat == password && password.length >= 8)),
            ) { Text(if (creating) "Create account" else "Connect") }
        },
        dismissButton = { TextButton(onClick = onOffline) { Text("Use offline") } },
    )
}

@Composable private fun DockGlyph(destination: Destination) {
    val tint = androidx.compose.material3.LocalContentColor.current
    val line =
        when (destination) {
            Destination.Runs -> LinePaths.TODAY
            Destination.Analytics -> LinePaths.CHART
            Destination.QuickLog -> LinePaths.BOLT
            Destination.Goals -> LinePaths.TARGET
            Destination.Trackers -> LinePaths.TRENDING
            Destination.Share -> LinePaths.SHARE
            Destination.Settings -> null
        }
    if (line != null) {
        LineIcon(line, tint, size = 22.dp)
        return
    }
    Canvas(Modifier.size(24.dp)) {
        val u = size.minDimension / 24f
        val stroke = Stroke(width = 2f * u)
        val c =
            androidx.compose.ui.geometry
                .Offset(size.width / 2f, size.height / 2f)
        when (destination) {
            Destination.Runs -> {
                drawCircle(tint, radius = 9f * u, center = c, style = stroke)
                drawLine(
                    tint,
                    c,
                    androidx.compose.ui.geometry
                        .Offset(c.x, c.y - 5f * u),
                    2f * u,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    tint,
                    c,
                    androidx.compose.ui.geometry
                        .Offset(c.x + 4f * u, c.y + 2f * u),
                    2f * u,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            Destination.Analytics -> {
                drawLine(
                    tint,
                    androidx.compose.ui.geometry
                        .Offset(3f * u, 20f * u),
                    androidx.compose.ui.geometry
                        .Offset(21f * u, 20f * u),
                    2f * u,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                listOf(7f to 9f, 12f to 5f, 17f to 2f).forEach { (x, h) ->
                    drawLine(
                        tint,
                        androidx.compose.ui.geometry
                            .Offset(x * u, 19f * u),
                        androidx.compose.ui.geometry.Offset(
                            x * u,
                            (19f - h) * u,
                        ),
                        3f * u,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
            }
            Destination.QuickLog -> {
                drawLine(
                    tint,
                    androidx.compose.ui.geometry
                        .Offset(4f * u, 8f * u),
                    androidx.compose.ui.geometry
                        .Offset(20f * u, 8f * u),
                    2f * u,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    tint,
                    androidx.compose.ui.geometry
                        .Offset(16f * u, 4f * u),
                    androidx.compose.ui.geometry
                        .Offset(20f * u, 8f * u),
                    2f * u,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    tint,
                    androidx.compose.ui.geometry
                        .Offset(16f * u, 12f * u),
                    androidx.compose.ui.geometry
                        .Offset(20f * u, 8f * u),
                    2f * u,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    tint,
                    androidx.compose.ui.geometry
                        .Offset(20f * u, 16f * u),
                    androidx.compose.ui.geometry
                        .Offset(4f * u, 16f * u),
                    2f * u,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    tint,
                    androidx.compose.ui.geometry
                        .Offset(8f * u, 12f * u),
                    androidx.compose.ui.geometry
                        .Offset(4f * u, 16f * u),
                    2f * u,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawLine(
                    tint,
                    androidx.compose.ui.geometry
                        .Offset(8f * u, 20f * u),
                    androidx.compose.ui.geometry
                        .Offset(4f * u, 16f * u),
                    2f * u,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
            Destination.Goals -> {
                drawCircle(tint, 9f * u, c, style = stroke)
                drawCircle(tint, 5f * u, c, style = stroke)
                drawCircle(tint, 1.7f * u, c)
            }
            Destination.Settings -> {
                drawCircle(tint, 3f * u, c, style = stroke)
                for (i in 0 until 8) {
                    val a = i * (kotlin.math.PI / 4.0)
                    drawLine(
                        tint,
                        androidx.compose.ui.geometry.Offset(
                            c.x + kotlin.math.cos(a).toFloat() * 7.5f * u,
                            c.y + kotlin.math.sin(a).toFloat() * 7.5f * u,
                        ),
                        androidx.compose.ui.geometry.Offset(
                            c.x + kotlin.math.cos(a).toFloat() * 10.5f * u,
                            c.y + kotlin.math.sin(a).toFloat() * 10.5f * u,
                        ),
                        2.4f * u,
                        cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    )
                }
                drawCircle(tint, 7.5f * u, c, style = stroke)
            }
            Destination.Share -> {
                // Share uses the line icon above; never reaches the canvas branch.
            }
            Destination.Trackers -> {
                val path =
                    Path().apply {
                        moveTo(3f * u, 15f * u)
                        lineTo(8f * u, 10f * u)
                        lineTo(12f * u, 13f * u)
                        lineTo(18f * u, 5f * u)
                        lineTo(
                            21f * u,
                            7f * u,
                        )
                    }
                drawPath(
                    path,
                    tint,
                    style =
                        Stroke(
                            width = 2f * u,
                            cap = androidx.compose.ui.graphics.StrokeCap.Round,
                            join = androidx.compose.ui.graphics.StrokeJoin.Round,
                        ),
                )
            }
        }
    }
}

/** ISO yyyy-MM-dd to UTC epoch millis, as the Material date picker expects; null when invalid. */
internal fun isoDateToUtcMillis(value: String): Long? {
    if (!isValidRunDate(value)) return null
    val y0 = value.substring(0, 4).toLong()
    val m = value.substring(5, 7).toLong()
    val d = value.substring(8, 10).toLong()
    val y = if (m <= 2) y0 - 1 else y0
    val era = (if (y >= 0) y else y - 399) / 400
    val yoe = y - era * 400
    val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return (era * 146097 + doe - 719468) * 86_400_000L
}

/** UTC epoch millis from the Material date picker to ISO yyyy-MM-dd. */
internal fun utcMillisToIsoDate(millis: Long): String {
    val z = millis.floorDiv(86_400_000L) + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1
    val m = if (mp < 10) mp + 3 else mp - 9
    val y = yoe + era * 400 + if (m <= 2) 1 else 0
    return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
}

internal fun isValidRunDate(value: String): Boolean {
    if (!Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) return false
    val year = value.substring(0, 4).toInt()
    val month = value.substring(5, 7).toInt()
    val day = value.substring(8, 10).toInt()
    if (year == 0 || month !in 1..12) return false
    val maxDay =
        when (month) {
            2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
            4, 6, 9, 11 -> 30
            else -> 31
        }
    return day in 1..maxDay
}

private fun loadQuickSwipeAction(
    database: HabitDatabase,
    key: String,
    fallback: QuickSwipeAction,
): QuickSwipeAction =
    try {
        QuickSwipeAction.parse(database.schemaQueries.selectMetaValue(key).executeAsOneOrNull(), fallback)
    } catch (_: Exception) {
        fallback
    }

private fun saveQuickSwipeAction(
    database: HabitDatabase,
    key: String,
    action: QuickSwipeAction,
) {
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
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = progress().coerceIn(0f, 1f) }
            .clip(shape)
            .background(swipeActionContainer(action)),
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 26.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (positive) Arrangement.Start else Arrangement.End,
        ) {
            Text(
                "${swipeActionGlyph(action)}  ${swipeActionLabel(action)}",
                color = swipeActionContent(action),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** Face of a Quick Log card; the card underneath renders the same face so promotion is seamless. */
@Composable private fun QuickLogCardFace(
    h: Habit,
    hint: String,
) {
    Column(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().weight(0.45f).background(Brush.verticalGradient(listOf(palette.accentSoft, palette.accentSoft2))),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val center =
                    androidx.compose.ui.geometry
                        .Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension * .47f
                listOf(.55f, .78f, 1f).forEach { ratio ->
                    drawCircle(
                        palette.muted.copy(alpha = .28f),
                        radius = maxRadius * ratio,
                        center = center,
                        style = Stroke(width = 1.dp.toPx()),
                    )
                }
            }
            Text(
                "${h.group.uppercase()}  ·  DUE",
                Modifier.align(Alignment.TopStart).padding(start = 17.dp, top = 15.dp),
                color = green,
                style = MaterialTheme.typography.labelMedium,
            )
            Box(Modifier.size(142.dp).clip(RoundedCornerShape(36.dp)).background(green), contentAlignment = Alignment.Center) {
                HabitGlyph(h, Modifier.size(56.dp), green, palette.onAccent)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .weight(0.55f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 23.dp, vertical = 19.dp),
        ) {
            Text("${h.recurrence}  ·  due today", color = muted, style = MaterialTheme.typography.labelMedium)
            Text(h.name, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                "${h.iteration}  ·  ${h.recurrence}",
                color = muted,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 5.dp),
            )
            if (!hidePointsPref) {
                Text(
                    "${h.points} impact points",
                    color = green,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Text(hint, color = muted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 14.dp))
        }
    }
}

/** Bordered corner stamp, matching the web prototype's DONE/SKIP stamp treatment. */
@Composable private fun BoxScope.SwipeStamp(
    action: QuickSwipeAction,
    alpha: () -> Float,
    positive: Boolean,
) {
    val content = swipeActionContent(action)
    Text(
        swipeStampLabel(action),
        Modifier
            .align(if (positive) Alignment.TopStart else Alignment.TopEnd)
            .padding(top = 18.dp, start = 18.dp, end = 18.dp)
            .graphicsLayer {
                rotationZ = if (positive) -12f else 12f
                this.alpha = alpha().coerceIn(0f, 1f)
            }.border(3.dp, content, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        color = content,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
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
                Text(
                    "Default: right Done · left Skipped. Comment opens the note sheet without logging.",
                    color = muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(right, left) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable private fun QuickLogScreen(
    database: HabitDatabase,
    today: String,
    runsRevision: Int,
    onLocalChange: () -> Unit = {},
    onStartNextRun: () -> Unit = {},
    onBackToRuns: () -> Unit = {},
) {
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
    val dragAction by remember {
        derivedStateOf {
            when (dragSide) {
                1 -> swipeRight
                -1 -> swipeLeft
                else -> null
            }
        }
    }
    val pastCommit by remember { derivedStateOf { kotlin.math.abs(offsetX.value) >= commitThresholdPx } }
    LaunchedEffect(pastCommit) { if (pastCommit && !animating) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    val hint = "Swipe right for ${swipeRight.key} · left for ${swipeLeft.key}"
    val activeRun =
        remember(database, runsRevision, today) {
            database.activeRuns(today).firstOrNull()
        }
    val runHabits =
        remember(database, runsRevision) {
            loadRunHabits(database, activeRun?.uid)
        }
    val due =
        remember(database, today, runHabits) {
            val completed =
                database.schemaQueries
                    .selectCheckInsForDay(today)
                    .executeAsList()
                    .filter {
                        it.state == "done"
                    }.map { it.habit_uid }
                    .toSet()
            runHabits.filter { habit ->
                if (habit.uid() in completed) {
                    false
                } else if (habit.recurrence.startsWith("Every ")) {
                    val interval = Regex("\\d+").find(habit.recurrence)?.value?.toIntOrNull() ?: 1
                    val recentCompletion =
                        database.schemaQueries.selectCheckInsForHabit(habit.uid()).executeAsList().firstOrNull {
                            it.state ==
                                "done"
                        }
                    val daysSince =
                        recentCompletion?.let { entry ->
                            (0 until interval).firstOrNull { addIsoDays(today, -it) == entry.day }
                        }
                    daysSince == null
                } else {
                    true
                }
            }
        }

    fun record(
        state: String,
        habit: Habit,
    ) {
        val uid = habit.uid()
        val note = comment.takeIf { it.isNotBlank() }
        if (database.schemaQueries.selectCheckInByUid("$uid:$today").executeAsOneOrNull() == null) {
            database.schemaQueries.insertCheckIn("$uid:$today", uid, today, state, note)
        } else {
            database.schemaQueries.updateCheckIn(state, note, uid, today)
        }
        when (state) {
            "done" -> done++
            "partial" -> partial++
            "skipped" -> skipped++
        }
        index++
        comment = ""
        commentOpen = false
        onLocalChange()
    }

    fun springBack(velocity: Float) {
        scope.launch { offsetX.animateTo(0f, spring(dampingRatio = .62f, stiffness = 380f), initialVelocity = velocity) }
        scope.launch { offsetY.animateTo(0f, spring(dampingRatio = .75f, stiffness = 380f)) }
    }

    /** Throws the top card off-screen at the finger's speed, then promotes the card underneath in the same frame. */
    fun flyOut(
        action: QuickSwipeAction,
        habit: Habit,
        direction: Int,
        velocity: Float = 0f,
    ) {
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
    if (activeRun == null) {
        val lastRun = remember(database, runsRevision) { database.currentRun() }
        val isLastRunEnded = lastRun != null && database.isRunEnded(lastRun, today)
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (isLastRunEnded && lastRun != null) {
                    Text("🏁", style = MaterialTheme.typography.displayMedium)
                    Text("Run completed!", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Your ${lastRun.name} has finished. There are no habits due today until you start your next run.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Button(onClick = onStartNextRun, shape = RoundedCornerShape(14.dp)) {
                        Text("Start next run")
                    }
                    TextButton(onClick = onBackToRuns) {
                        Text("View last run results")
                    }
                } else {
                    Text("No active run", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Start a run to use Quick Log.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = muted,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Button(onClick = onStartNextRun, shape = RoundedCornerShape(14.dp)) {
                        Text("Build a run")
                    }
                }
            }
        }
        return
    }
    if (index >=
        due.size
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Quick Log complete\n$done done · $partial partial · $skipped skipped", style = MaterialTheme.typography.titleLarge)
        }
        return
    }
    val h = due[index]
    Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Today’s due habits", style = MaterialTheme.typography.titleMedium)
                Text("Quick check-in", color = muted, style = MaterialTheme.typography.bodySmall)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                AssistChip(onClick = {}, label = { Text("${index + 1} of ${due.size}") })
                IconButton(
                    onClick = { swipeSettingsOpen = true },
                    enabled = !animating,
                    modifier =
                        Modifier.size(40.dp).semantics {
                            contentDescription =
                                "Swipe actions"
                        },
                ) { Text("⚙", color = muted) }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f).onSizeChanged { cardWidthPx = it.width.toFloat() }) {
            if (index + 1 >= due.size) {
                SwipeActionBackground(
                    action = dragAction,
                    progress = { swipeProgress(offsetX.value, commitThresholdPx) },
                    positive = dragSide >= 0,
                )
            }
            if (index + 1 < due.size) {
                // The next card waits underneath and grows to full size as the top card leaves.
                Card(
                    Modifier
                        .fillMaxSize()
                        .zIndex(0f)
                        .graphicsLayer {
                            val lift = (kotlin.math.abs(offsetX.value) / (size.width * 0.5f)).coerceIn(0f, 1f)
                            val scale = 0.94f + 0.06f * lift
                            scaleX = scale
                            scaleY = scale
                            translationY = (1f - lift) * 14.dp.toPx()
                        },
                    colors = CardDefaults.cardColors(containerColor = surface),
                    shape = RoundedCornerShape(26.dp),
                ) {
                    QuickLogCardFace(due[index + 1], hint)
                }
            }
            Card(
                Modifier
                    .fillMaxSize()
                    .zIndex(1f)
                    .graphicsLayer {
                        translationX = offsetX.value
                        translationY = offsetY.value
                        rotationZ = offsetX.value / size.width * 14f
                        transformOrigin = TransformOrigin(0.5f, 1.1f)
                    }.semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction("Mark done") {
                                    flyOut(QuickSwipeAction.Done, h, 1)
                                    true
                                },
                                CustomAccessibilityAction("Mark partial") {
                                    flyOut(QuickSwipeAction.Partial, h, -1)
                                    true
                                },
                                CustomAccessibilityAction("Mark skipped") {
                                    flyOut(QuickSwipeAction.Skipped, h, -1)
                                    true
                                },
                                CustomAccessibilityAction("Add comment") {
                                    commentOpen = true
                                    true
                                },
                            )
                    }.horizontalSwipe(
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
                            if (direction == 0) {
                                springBack(velocity)
                            } else {
                                flyOut(if (direction > 0) swipeRight else swipeLeft, h, direction, velocity)
                            }
                        },
                    ),
                colors = CardDefaults.cardColors(containerColor = surface),
                shape = RoundedCornerShape(26.dp),
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
            Button(
                onClick = { flyOut(QuickSwipeAction.Partial, h, -1) },
                enabled = !animating,
                modifier =
                    Modifier.weight(1f).height(54.dp).semantics {
                        contentDescription =
                            "Partial"
                    },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = palette.partialSoft, contentColor = palette.partial),
            ) { Text("◐  Partial") }
            FilledTonalButton(
                onClick = { commentOpen = true },
                enabled = !animating,
                modifier =
                    Modifier.weight(1f).height(54.dp).semantics {
                        contentDescription =
                            "Comment"
                    },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.filledTonalButtonColors(containerColor = palette.accentSoft2, contentColor = green),
            ) { Text("✎  Comment") }
        }
        Text(
            "Swipe right for ${swipeRight.key} · left for ${swipeLeft.key}. Skipped interval habits stay due until completed.",
            Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            color = muted,
            style = MaterialTheme.typography.bodySmall,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
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
            onDismiss = { swipeSettingsOpen = false },
        )
    }
    if (commentOpen) {
        CommentSheet(
            habit = h,
            comment = comment,
            onCommentChange = { comment = it },
            onClose = { commentOpen = false },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CommentSheet(
    habit: Habit,
    comment: String,
    onCommentChange: (String) -> Unit,
    onClose: () -> Unit,
) {
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
        onDismissRequest = {
            keyboard?.hide()
            onClose()
        },
        sheetState = sheetState,
        containerColor = surface,
        contentColor = ink,
        dragHandle = { BottomSheetDefaults.DragHandle(color = muted) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Add a comment", style = MaterialTheme.typography.headlineSmall)
            Text("${habit.name} · today", style = MaterialTheme.typography.bodyMedium, color = muted)
            OutlinedTextField(
                value = comment,
                onValueChange = onCommentChange,
                label = { Text("Comment for today") },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                minLines = 3,
            )
            Text("This note will be attached to today’s check-in.", style = MaterialTheme.typography.bodySmall, color = muted)
            Button(onClick = {
                keyboard?.hide()
                onClose()
            }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Save comment") }
        }
    }
}

@Composable private fun ListingScreen(
    title: String,
    rows: List<String>,
    note: String,
) {
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            Text(note, color = muted, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
        }
        items(rows) { row ->
            Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(18.dp)) {
                Text(row, Modifier.fillMaxWidth().padding(18.dp), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable private fun SettingsScreen(
    database: HabitDatabase,
    syncClient: SyncClient,
    syncMode: String?,
    dense: Boolean,
    syncing: Boolean,
    syncStatus: String?,
    screenOrder: List<Destination>,
    hiddenScreens: Set<Destination>,
    onScreenOrderChange: (List<Destination>) -> Unit,
    onScreenVisibilityChange: (Destination, Boolean) -> Unit,
    onResetScreens: () -> Unit,
    onDense: (Boolean) -> Unit,
    groupHabits: Boolean,
    onGroupHabits: (Boolean) -> Unit,
    historyDays: Int,
    onHistoryDays: (Int) -> Unit,
    onConnect: () -> Unit,
    onSyncNow: () -> Unit,
    onOffline: () -> Unit,
    onExportBackup: () -> Unit,
    onImportBackup: () -> Unit,
) {
    val connected = syncMode == SYNC_MODE_SERVER
    val url = syncClient.serverUrl()
    var confirmImport by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LOCAL BACKUP", style = MaterialTheme.typography.labelMedium, color = green)
                Text("Export or restore this phone's complete SQLite database.", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "The backup includes the sync session token. Store the file somewhere private. Restoring replaces this phone's data; it does not change server data or reset the account password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onExportBackup) { Text("Export backup") }
                    Button(onClick = { confirmImport = true }) { Text("Import backup") }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("SYNC SERVER", style = MaterialTheme.typography.labelMedium, color = green)
                Text(if (connected) "Connected" else "Offline — data stays on this phone", style = MaterialTheme.typography.titleSmall)
                if (connected && url != null) {
                    Text(url, style = MaterialTheme.typography.bodySmall, color = muted)
                    syncClient.account()?.let { Text("Signed in as $it", style = MaterialTheme.typography.bodySmall, color = muted) }
                    Text(
                        "The web app lives at the same address: open it in any browser and sign in with this account.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
                    syncStatus?.let { Text(if (syncing) "Syncing…" else it, style = MaterialTheme.typography.labelSmall, color = muted) }
                } else {
                    Text(
                        "Connect to your sync server to share runs with your other devices and the web app.",
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                    )
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
                        FilterChip(selected = themeModePref == key, onClick = {
                            themeModePref = key
                            database.setPref(PREF_THEME_MODE, key)
                        }, label = { Text(label, maxLines = 1) }, modifier = Modifier.weight(1f))
                    }
                }
                Text("Style", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                SegmentedChoice(
                    options = listOf(STYLE_CLASSIC to "Classic", STYLE_WA to "Japanese"),
                    selected = stylePref,
                    onSelect = { key ->
                        if (key != stylePref) {
                            stylePref = key
                            database.setPref(PREF_STYLE, key)
                            if (key == STYLE_WA) {
                                accentPref = "blue"
                                database.setPref(PREF_ACCENT, "blue")
                            }
                        }
                    },
                )
                val styleNote =
                    if (stylePref == STYLE_WA) {
                        "Washi paper, sumi ink and traditional Japanese colours."
                    } else {
                        "Warm paper and bright, friendly colours."
                    }
                Text(
                    styleNote,
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                Text("Accent color", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    ACCENTS.forEach { option ->
                        val on = accentPref == option.key
                        val swatch = option.value(palette.wa, palette.dark)
                        val label = if (palette.wa) "${option.label} (${option.waName})" else option.label
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .clickable {
                                    accentPref = option.key
                                    database.setPref(PREF_ACCENT, option.key)
                                }.semantics {
                                    contentDescription = "Accent $label"
                                    stateDescription = if (on) "Selected" else "Not selected"
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .then(if (on) Modifier.border(2.5.dp, swatch, CircleShape).padding(4.dp) else Modifier.padding(2.dp))
                                    .clip(CircleShape)
                                    .background(swatch),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (on) Text("✓", color = palette.onAccent, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("APP SCREENS", style = MaterialTheme.typography.labelMedium, color = green)
                        Text("Choose tabs and their order", style = MaterialTheme.typography.titleSmall)
                    }
                    TextButton(onClick = onResetScreens) { Text("Reset") }
                }
                Text(
                    "Today and Settings always stay available. Changes are saved on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = muted,
                )
                screenOrder.forEachIndexed { index, destination ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("${index + 1}. ${destination.title}", style = MaterialTheme.typography.titleSmall)
                            if (destination in requiredDestinations) {
                                Text("Always shown", style = MaterialTheme.typography.labelSmall, color = muted)
                            } else {
                                Text(
                                    if (destination in hiddenScreens) "Hidden from tabs" else "Shown in tabs",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = muted,
                                )
                            }
                        }
                        IconButton(
                            enabled = index > 0,
                            onClick = {
                                val reordered = screenOrder.toMutableList()
                                val moved = reordered.removeAt(index)
                                reordered.add(index - 1, moved)
                                onScreenOrderChange(reordered)
                            },
                            modifier = Modifier.semantics { contentDescription = "Move ${destination.title} up" },
                        ) { Text("↑", color = if (index > 0) green else muted) }
                        IconButton(
                            enabled = index < screenOrder.lastIndex,
                            onClick = {
                                val reordered = screenOrder.toMutableList()
                                val moved = reordered.removeAt(index)
                                reordered.add(index + 1, moved)
                                onScreenOrderChange(reordered)
                            },
                            modifier = Modifier.semantics { contentDescription = "Move ${destination.title} down" },
                        ) { Text("↓", color = if (index < screenOrder.lastIndex) green else muted) }
                        if (destination !in requiredDestinations) {
                            Switch(
                                checked = destination !in hiddenScreens,
                                onCheckedChange = { onScreenVisibilityChange(destination, it) },
                                modifier = Modifier.semantics { contentDescription = "Show ${destination.title} tab" },
                            )
                        }
                    }
                }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = surface), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("HABIT LIST", style = MaterialTheme.typography.labelMedium, color = green)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show habit groups", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Switch(
                        checked = groupHabits,
                        onCheckedChange = onGroupHabits,
                        modifier =
                            Modifier.semantics {
                                contentDescription =
                                    "Show habit groups"
                            },
                    )
                }
                Text("Days shown in normal layout", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(7, 14, 21).forEach { days ->
                        FilterChip(selected = historyDays == days, onClick = { onHistoryDays(days) }, label = { Text("$days days") })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Dense layout", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "For long lists: one line per habit with small day dots. Pick the day from the strip above the list; swiping still logs.",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                    Switch(
                        checked = dense,
                        onCheckedChange = onDense,
                        modifier =
                            Modifier.padding(start = 12.dp).semantics {
                                contentDescription =
                                    "Dense layout"
                            },
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Hide points", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Hide impact points on habit rows, the run summary, Quick Log and the points charts.",
                            style = MaterialTheme.typography.bodySmall,
                            color = muted,
                        )
                    }
                    Switch(
                        checked = hidePointsPref,
                        onCheckedChange = {
                            hidePointsPref = it
                            database.setPref(PREF_HIDE_POINTS, if (it) "1" else "0")
                        },
                        modifier =
                            Modifier.padding(start = 12.dp).semantics {
                                contentDescription =
                                    "Hide points"
                            },
                    )
                }
            }
        }
    }
    if (confirmImport) {
        AlertDialog(
            onDismissRequest = { confirmImport = false },
            title = { Text("Replace local data?") },
            text = {
                Text(
                    "Choose a Habit Lab SQLite backup. The app will validate it before replacing this phone's database. Data on the sync server will not be changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmImport = false
                    onImportBackup()
                }) { Text("Choose backup") }
            },
            dismissButton = { TextButton(onClick = { confirmImport = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun StartDatePickerDialog(
    initial: String,
    fallback: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = isoDateToUtcMillis(initial) ?: isoDateToUtcMillis(fallback))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = { state.selectedDateMillis?.let { onPick(utcMillisToIsoDate(it)) } },
            ) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) {
        DatePicker(state = state, title = { Text("Run start date", modifier = Modifier.padding(start = 24.dp, top = 16.dp)) })
    }
}
