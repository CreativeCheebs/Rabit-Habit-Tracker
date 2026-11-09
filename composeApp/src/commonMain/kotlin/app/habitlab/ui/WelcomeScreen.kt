package app.habitlab.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val WELCOME_PAGES = 3

/**
 * First-start flow in three pages: the weight-per-habit idea, how progress and
 * Quick Log work (animated), and where the habits should live. The sync choice
 * is last and can be changed later in Settings.
 */
@Composable
internal fun WelcomeScreen(
    onConnect: () -> Unit,
    onOffline: () -> Unit,
) {
    val pager = rememberPagerState { WELCOME_PAGES }
    val scope = rememberCoroutineScope()
    val last = WELCOME_PAGES - 1

    fun goTo(page: Int) {
        scope.launch { pager.animateScrollToPage(page) }
    }
    Dialog(
        // Back steps to the previous page; on the first page it does nothing (a choice is still needed).
        onDismissRequest = { if (pager.currentPage > 0) goTo(pager.currentPage - 1) },
        properties = DialogProperties(dismissOnBackPress = true, dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize(), color = palette.bg) {
            Column(Modifier.fillMaxSize()) {
                HorizontalPager(pager, Modifier.weight(1f)) { page ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 28.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        when (page) {
                            0 -> WeightPage(active = pager.currentPage == 0)
                            1 -> FlowPage(active = pager.currentPage == 1)
                            else -> SyncPage(onConnect = onConnect, onOffline = onOffline)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f)) {
                        if (pager.currentPage == last) {
                            TextButton(onClick = { goTo(last - 1) }) { Text("Back", color = muted) }
                        } else {
                            TextButton(onClick = { goTo(last) }) { Text("Skip", color = muted) }
                        }
                    }
                    PageDots(count = WELCOME_PAGES, current = pager.currentPage)
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End) {
                        if (pager.currentPage < last) {
                            Button(
                                onClick = { goTo(pager.currentPage + 1) },
                                colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = palette.onAccent),
                            ) { Text("Next") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PageDots(
    count: Int,
    current: Int,
) {
    Row(
        Modifier.semantics { contentDescription = "Page ${current + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val width by animateDpAsState(if (index == current) 22.dp else 8.dp, tween(250))
            val color by animateColorAsState(if (index == current) green else palette.divider, tween(250))
            Spacer(
                Modifier
                    .width(width)
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

// ---------- page 1: weight per habit ----------

@Composable
private fun WeightPage(active: Boolean) {
    Text("RABIT", color = green, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
    Text(
        "Not every habit weighs the same",
        color = ink,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        "Give each habit points for how much it moves your life. Progress is points earned out of points possible, not boxes ticked.",
        color = ink,
        style = MaterialTheme.typography.bodyLarge,
    )
    WeightExample(active)
    Text(
        "Skipped and partial days are kept apart from done, so the history stays honest.",
        color = muted,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun WeightExample(active: Boolean) {
    // Alternates which habit is skipped so the 4-vs-1 difference is visible.
    var skipBig by remember { mutableStateOf(false) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        skipBig = false
        while (true) {
            delay(2400)
            skipBig = !skipBig
        }
    }
    val earned = if (skipBig) 1 else 4
    val progress by animateFloatAsState(earned / 5f, tween(700, easing = FastOutSlowInEasing))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("EXAMPLE", color = muted, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        WeightRow("Focused work block", 4, skipped = skipBig)
        WeightRow("Stretch for a minute", 1, skipped = !skipBig)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$earned of 5 points",
                color = ink,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            Text("${earned * 20}%", color = green, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(50))
                .background(palette.track),
        ) {
            Spacer(
                Modifier
                    .fillMaxWidth(progress)
                    .height(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(green),
            )
        }
        Text(
            if (skipBig) {
                "Skip the 4-point habit and only 1 of 5 points is left."
            } else {
                "Skip the 1-point habit and you still have 4 of 5 points."
            },
            color = muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WeightRow(
    name: String,
    points: Int,
    skipped: Boolean,
) {
    val alpha by animateFloatAsState(if (skipped) 0.4f else 1f, tween(500))
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(14.dp))
            .background(palette.tile)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (skipped) "$name · skipped" else name,
            color = ink,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (points == 1) "1 pt" else "$points pts",
            color = palette.onAccent,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(green).padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

// ---------- page 2: burnup + Quick Log ----------

@Composable
private fun FlowPage(active: Boolean) {
    Text(
        "See it add up, log it in seconds",
        color = ink,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        "Every run has a burnup chart: the points you have earned so far, and a dashed forecast of where the run will end.",
        color = ink,
        style = MaterialTheme.typography.bodyLarge,
    )
    BurnupDemo(active)
    Text(
        "Quick Log shows what is due today, one habit at a time. Swipe right for done, left to skip.",
        color = ink,
        style = MaterialTheme.typography.bodyLarge,
    )
    QuickLogDemo(active)
}

// Cumulative points earned on days 1..9 of a 14-day run (made up for the demo).
private val DEMO_ACTUAL = listOf(0f, 3f, 5f, 9f, 10f, 14f, 17f, 19f, 23f)
private const val DEMO_DAYS = 14
private const val DEMO_MAX_POINTS = 42f

@Composable
private fun BurnupDemo(active: Boolean) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            progress.snapTo(0f)
            delay(300)
            progress.animateTo(1f, tween(3200, easing = androidx.compose.animation.core.LinearEasing))
            delay(2000)
        }
    }
    val lineColor = green
    val grid = palette.divider
    val textMuted = muted
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surface)
            .padding(16.dp)
            .clearAndSetSemantics {
                contentDescription =
                    "Example burnup chart: points earned rise day by day, then a dashed forecast continues to the end of the run."
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Points burnup", color = ink, style = MaterialTheme.typography.titleMedium)
        Canvas(Modifier.fillMaxWidth().height(150.dp)) {
            val left = 4.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 8.dp.toPx()
            val bottom = size.height - 6.dp.toPx()

            fun point(
                day: Int,
                points: Float,
            ) = Offset(
                left + (right - left) * day / (DEMO_DAYS - 1),
                bottom - (bottom - top) * points / DEMO_MAX_POINTS,
            )
            for (i in 0..3) {
                val y = bottom - (bottom - top) * i / 3f
                drawLine(grid, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            }
            val actual = DEMO_ACTUAL.mapIndexed { day, points -> point(day, points) }
            val segments = actual.size - 1
            // Actual line takes the first 70% of the animation, the forecast the rest.
            val actualP = (progress.value / 0.7f).coerceIn(0f, 1f) * segments
            var head = actual.first()
            for (i in 0 until segments) {
                val frac = (actualP - i).coerceIn(0f, 1f)
                if (frac <= 0f) break
                head = lerp(actual[i], actual[i + 1], frac)
                drawLine(lineColor, actual[i], head, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            val forecastP = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f)
            if (forecastP > 0f) {
                val slope = (DEMO_ACTUAL.last() - DEMO_ACTUAL[3]) / (DEMO_ACTUAL.size - 1 - 3)
                val end = point(DEMO_DAYS - 1, DEMO_ACTUAL.last() + slope * (DEMO_DAYS - DEMO_ACTUAL.size))
                head = lerp(actual.last(), end, forecastP)
                drawLine(
                    lineColor.copy(alpha = 0.7f),
                    actual.last(),
                    head,
                    strokeWidth = 3.dp.toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 10.dp.toPx())),
                )
            }
            drawCircle(lineColor, radius = 5.dp.toPx(), center = head)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("━ Actual", color = textMuted, style = MaterialTheme.typography.labelMedium)
            Text("┄ Forecast", color = textMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

private class DemoStep(
    val habit: String,
    val points: Int,
    val done: Boolean,
)

private val DEMO_STEPS =
    listOf(
        DemoStep("Plan the day", 3, done = true),
        DemoStep("Morning light", 2, done = true),
        DemoStep("Sleep before 11:30 PM", 2, done = false),
    )

@Composable
private fun QuickLogDemo(active: Boolean) {
    val drag = remember { Animatable(0f) } // -1 .. 1 of the card width, + is right
    var step by remember { mutableIntStateOf(0) }
    var earned by remember { mutableIntStateOf(0) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        while (true) {
            step = 0
            earned = 0
            drag.snapTo(0f)
            for (i in DEMO_STEPS.indices) {
                step = i
                drag.snapTo(0f)
                delay(900)
                val s = DEMO_STEPS[i]
                drag.animateTo(if (s.done) 0.45f else -0.45f, tween(700, easing = FastOutSlowInEasing))
                delay(150)
                drag.animateTo(if (s.done) 1.3f else -1.3f, tween(350))
                if (s.done) earned += s.points
            }
            delay(1500)
        }
    }
    val current = DEMO_STEPS[step]
    val stampAlpha = (kotlin.math.abs(drag.value) / 0.4f).coerceIn(0f, 1f)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(surface)
            .padding(16.dp)
            .clearAndSetSemantics {
                contentDescription =
                    "Example of Quick Log: swiping a habit card right marks it done and earns its points; swiping left skips it and earns none."
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Quick Log · today", color = ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text("$earned pts earned", color = green, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(min = 110.dp)) {
            val travel = maxWidth
            Spacer(
                Modifier
                    .fillMaxWidth()
                    .height(
                        96.dp,
                    ).padding(horizontal = 10.dp)
                    .offset(y = 12.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.tile),
            )
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .offset(x = travel * drag.value)
                    .graphicsLayer { rotationZ = drag.value * 10f }
                    .clip(RoundedCornerShape(18.dp))
                    .background(palette.surfaceAlt)
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(current.habit, color = ink, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    if (drag.value != 0f) {
                        val done = drag.value > 0f
                        Text(
                            if (done) "DONE" else "SKIP",
                            color = if (done) green else palette.skipped,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.graphicsLayer { alpha = stampAlpha },
                        )
                    }
                }
                Text(
                    "${current.points} impact points · due today",
                    color = muted,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            "Done earns the habit's points. Skip earns none.",
            color = muted,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

// ---------- page 3: sync choice ----------

@Composable
private fun SyncPage(
    onConnect: () -> Unit,
    onOffline: () -> Unit,
) {
    Text(
        "Where should your habits live?",
        color = ink,
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        "Choose now; you can change it any time in Settings.",
        color = muted,
        style = MaterialTheme.typography.bodyLarge,
    )
    Button(
        onClick = onConnect,
        modifier = Modifier.fillMaxWidth().height(52.dp),
        colors = ButtonDefaults.buttonColors(containerColor = green, contentColor = palette.onAccent),
    ) { Text("Connect to a server") }
    Text(
        "Keeps this phone and the browser app in step. You will sign in or create an account.",
        color = muted,
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedButton(onClick = onOffline, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Use offline", color = ink) }
    Text(
        "Everything stays on this phone. You can connect later from Settings.",
        color = muted,
        style = MaterialTheme.typography.bodySmall,
    )
}
