package app.habitlab.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 24-unit stroke icon paths used by the redesign (Lucide-style, ISC licence). */
internal object LinePaths {
    const val FLAME =
        "M8.5 14.5A2.5 2.5 0 0 0 11 12c0-1.38-.5-2-1-3-1.07-2.14-.22-4.05 2-6 .5 2.5 2 4.9 4 6.5 2 1.6 3 3.5 3 5.5a7 7 0 1 1-14 0c0-1.15.43-2.29 1-3a2.5 2.5 0 0 0 2.5 2.5z"
    const val PENCIL = "M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"
    const val BACK = "M19 12H5M12 19l-7-7 7-7"
    const val CHEVRON_RIGHT = "m9 18 6-6-6-6"
    const val CHEVRON_LEFT = "m15 18-6-6 6-6"
    const val CHEVRON_DOWN = "m6 9 6 6 6-6"
    const val PLUS = "M12 5v14M5 12h14"
    const val MINUS = "M5 12h14"
    const val CHECK = "M20 6 9 17l-5-5"
    const val SHARE = "M4 12v8a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-8M16 6l-4-4-4 4M12 2v13"
    const val SYNC = "M21 12a9 9 0 1 1-3-6.7L21 8M21 3v5h-5"
    const val DOWNLOAD = "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3"
    const val CALENDAR = "M5 4h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM16 2v4M8 2v4M3 10h18"
    const val COPY =
        "M10 8h10a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H10a2 2 0 0 1-2-2V10a2 2 0 0 1 2-2zM4 16a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2"
    const val BLANK = "M5 3h14a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zM8 12h8M12 8v8"
    const val CODE = "m16 18 6-6-6-6M8 6l-6 6 6 6"
    const val CHART = "M3 3v18h18M18 17V9M13 17V5M8 17v-3"
    const val LEAF =
        "M11 20A7 7 0 0 1 9.8 6.1C15.5 5 17 4.48 19 2c1 2 2 4.18 2 8 0 5.5-4.78 10-10 10ZM2 21c0-3 1.85-5.36 5.08-6C9.5 14.52 12 13 13 12"
    const val INFO = "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 16v-4M12 8h.01"
    const val ALERT = "M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0zM12 9v4M12 17h.01"
    const val TODAY = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0M9 12l2 2 4-4"
    const val BOLT = "M13 2 3 14h9l-1 8 10-12h-9l1-8z"
    const val TARGET = "M2 12a10 10 0 1 0 20 0a10 10 0 1 0-20 0M6 12a6 6 0 1 0 12 0a6 6 0 1 0-12 0M10 12a2 2 0 1 0 4 0a2 2 0 1 0-4 0"
    const val TRENDING = "m22 7-8.5 8.5-5-5L2 17M16 7h6v6"
    const val SPARKLE = "M12 3l1.9 5.8L20 10.7l-6.1 1.9L12 18.5l-1.9-5.9L4 10.7l6.1-1.9z"
}

private val LINE_PATH_CACHE = HashMap<String, Path>()

/** A 24-unit stroke icon drawn in [tint]; decorative unless the parent supplies semantics. */
@Composable internal fun LineIcon(
    path: String,
    tint: Color,
    size: Dp = 20.dp,
    strokeWidth: Float = 2.2f,
    modifier: Modifier = Modifier,
) {
    val parsed = LINE_PATH_CACHE.getOrPut(path) { PathParser().parsePathString(path).toPath() }
    Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / 24f
        withTransform({ scale(scale, scale, pivot = Offset.Zero) }) {
            drawPath(parsed, tint, style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

/** Visual state of a check-in circle. */
internal enum class CheckVisual { Todo, Done, Partial, Skipped, Missed }

internal fun checkVisualOf(
    state: String?,
    pastDay: Boolean,
): CheckVisual =
    when (state) {
        "done" -> CheckVisual.Done
        "partial" -> CheckVisual.Partial
        "skipped" -> CheckVisual.Skipped
        else -> if (pastDay) CheckVisual.Missed else CheckVisual.Todo
    }

internal fun checkStateLabel(visual: CheckVisual): String =
    when (visual) {
        CheckVisual.Todo -> "not done yet"
        CheckVisual.Done -> "done"
        CheckVisual.Partial -> "partly done"
        CheckVisual.Skipped -> "skipped"
        CheckVisual.Missed -> "missed"
    }

/** 40dp check-in circle inside a 48dp touch target. Tapping cycles the state. */
@Composable internal fun CheckCircle(
    habitName: String,
    visual: CheckVisual,
    tone: HabitTone,
    enabled: Boolean,
    onTap: () -> Unit,
    size: Dp = 40.dp,
) {
    val label = checkStateLabel(visual)
    Box(
        Modifier
            .size(maxOf(size, 48.dp))
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = "Change check-in", role = Role.Button, onClick = onTap)
            .semantics {
                contentDescription = if (enabled) "$habitName, $label. Tap to change." else "$habitName, $label."
                stateDescription = label
            },
        contentAlignment = Alignment.Center,
    ) {
        val skipBg = palette.skipBg
        val skipInk = palette.skipInk
        val error = palette.error
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2f
            val ring = 2.5.dp.toPx()
            when (visual) {
                CheckVisual.Done -> {
                    drawCircle(tone.solid, r)
                }
                CheckVisual.Partial -> {
                    clipRect(top = r) { drawCircle(tone.solid, r) }
                    drawCircle(tone.ink, r - ring / 2, style = Stroke(ring))
                }
                CheckVisual.Skipped -> {
                    drawCircle(skipBg, r)
                    drawLine(skipInk, Offset(r - 6.dp.toPx(), r), Offset(r + 6.dp.toPx(), r), 3.dp.toPx(), cap = StrokeCap.Round)
                }
                CheckVisual.Missed -> {
                    drawCircle(
                        error,
                        r - 1.dp.toPx(),
                        style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))),
                    )
                }
                CheckVisual.Todo -> {
                    drawCircle(tone.ink, r - ring / 2, style = Stroke(ring))
                }
            }
        }
        if (visual == CheckVisual.Done) LineIcon(LinePaths.CHECK, tone.onSolid, size = size * 0.55f, strokeWidth = 3.2f)
    }
}

/** Rounded icon tile: habit tint background with the habit icon in ink. */
@Composable internal fun HabitIconTile(
    habit: Habit,
    tone: HabitTone,
    size: Dp = 42.dp,
    radius: Dp = 13.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier.size(size).clip(RoundedCornerShape(radius)).background(tone.tint),
        contentAlignment = Alignment.Center,
    ) { HabitGlyph(habit, Modifier.size(size * 0.52f), tone.tint, tone.ink) }
}

/** Ring progress (0..1) with a centred label. */
@Composable internal fun ProgressRing(
    progress: Float,
    track: Color,
    color: Color,
    size: Dp = 76.dp,
    stroke: Dp = 9.dp,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val inset = w / 2
            val arcSize = Size(this.size.width - w, this.size.height - w)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
            val sweep = 360f * progress.coerceIn(0f, 1f)
            if (sweep > 0f) drawArc(color, -90f, sweep, false, Offset(inset, inset), arcSize, style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

/** Small rounded label used for points and run facts. */
@Composable internal fun Pill(
    text: String,
    background: Color,
    content: Color,
    modifier: Modifier = Modifier,
) {
    Text(
        text,
        modifier = modifier.clip(CircleShape).background(background).padding(horizontal = 9.dp, vertical = 4.dp),
        color = content,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.ExtraBold, letterSpacing = 0.sp),
        maxLines = 1,
    )
}

/** Flame + "N days" pill; callers hide it when the streak is zero. */
@Composable internal fun StreakPill(days: Int) {
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(palette.streakSoft)
            .padding(horizontal = 12.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Streak: $days ${if (days == 1) "day" else "days"} in a row" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        LineIcon(LinePaths.FLAME, palette.streakInk, size = 16.dp, strokeWidth = 2.4f)
        Text(
            "$days ${if (days == 1) "day" else "days"}",
            color = palette.streakInk,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.ExtraBold),
        )
    }
}

/** Surface card with the redesign's radius. */
@Composable internal fun SectionCard(
    modifier: Modifier = Modifier,
    color: Color = palette.surface,
    radius: Dp = 20.dp,
    padding: Dp = 16.dp,
    content: @Composable () -> Unit,
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(radius))
            .background(color)
            .padding(padding),
    ) { content() }
}

/** Two-or-more option segmented control (rounded container, selected option lifted on surface). */
@Composable internal fun SegmentedChoice(
    options: List<Pair<String, String>>,
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(palette.surfaceAlt)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) palette.surface else Color.Transparent)
                    .clickable(role = Role.RadioButton) { onSelect(key) }
                    .semantics { stateDescription = if (on) "Selected" else "Not selected" },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    color = if (on) ink else muted,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Pill chip (length chips, group chips): selected = ink fill. */
@Composable internal fun ChoicePill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dashed: Boolean = false,
    contentColor: Color? = null,
) {
    val shape = CircleShape
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (selected) ink else palette.surface)
            .then(
                if (selected) {
                    Modifier
                } else if (dashed) {
                    Modifier.dashedBorder(palette.field, 999.dp)
                } else {
                    Modifier.border(1.5.dp, palette.field, shape)
                },
            ).clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) palette.bg else contentColor ?: ink,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
    }
}

/** Dashed outline (Compose has no dashed border modifier). */
internal fun Modifier.dashedBorder(
    color: Color,
    radius: Dp,
    width: Dp = 1.5.dp,
): Modifier =
    drawBehind {
        val w = width.toPx()
        val r = minOf(radius.toPx(), size.minDimension / 2)
        drawRoundRect(
            color,
            topLeft = Offset(w / 2, w / 2),
            size = Size(size.width - w, size.height - w),
            cornerRadius = CornerRadius(r, r),
            style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
        )
    }

/** Labelled −/value/+ stepper with 44dp buttons. */
@Composable internal fun Stepper(
    value: Int,
    onChange: (Int) -> Unit,
    min: Int,
    max: Int,
    decreaseLabel: String,
    increaseLabel: String,
    valueSize: Int = 26,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StepperButton(LinePaths.MINUS, decreaseLabel, value > min) { onChange((value - 1).coerceIn(min, max)) }
        Text(
            value.toString(),
            style = MaterialTheme.typography.headlineMedium.copy(fontSize = valueSize.sp),
            modifier = Modifier.semantics { contentDescription = value.toString() },
            textAlign = TextAlign.Center,
        )
        StepperButton(LinePaths.PLUS, increaseLabel, value < max) { onChange((value + 1).coerceIn(min, max)) }
        trailing()
    }
}

@Composable private fun StepperButton(
    path: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, palette.field, RoundedCornerShape(14.dp))
            .background(palette.surface)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) { LineIcon(path, if (enabled) ink else muted.copy(alpha = .4f), size = 18.dp, strokeWidth = 2.6f) }
}

/** Round 48dp icon button drawing a line icon. */
@Composable internal fun RoundIconButton(
    path: String,
    description: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    background: Color = Color.Transparent,
    tint: Color = ink,
) {
    Box(
        Modifier
            .size(48.dp)
            .padding(2.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) { LineIcon(path, if (enabled) tint else muted.copy(alpha = .4f), size = 20.dp) }
}

/** Banner for warnings and errors: tinted background, icon and content column. */
@Composable internal fun Banner(
    background: Color,
    iconTint: Color,
    icon: String = LinePaths.ALERT,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(background)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LineIcon(icon, iconTint, size = 18.dp, modifier = Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

/** Weekly / interval schedule in the redesign's wording: "Every day", "3× a week", "Every 2 days". */
internal fun scheduleLabel(recurrence: String): String {
    val n = Regex("\\d+").find(recurrence)?.value?.toIntOrNull() ?: return recurrence
    return if (recurrence.startsWith("Every ")) {
        if (n <= 1) "Every day" else "Every $n days"
    } else {
        if (n >= 7) "Every day" else "$n× a week"
    }
}

/** Full-width primary action: 52dp, radius 16, accent fill. */
@Composable internal fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .heightIn(min = 52.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (enabled) palette.accent else palette.track)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (enabled) palette.onAccent else muted,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold),
            maxLines = 1,
        )
    }
}

/** Selectable option card (Start from / empty state): icon square, title and a short line. */
@Composable internal fun SourceCard(
    title: String,
    sub: String,
    icon: String,
    iconBg: Color,
    iconInk: Color,
    selected: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .heightIn(min = 104.dp)
            .clip(shape)
            .background(if (selected) palette.accentSoft else palette.surface)
            .border(if (selected) 2.dp else 1.5.dp, if (selected) palette.accent else palette.field, shape)
            .then(
                if (onClick != null) {
                    Modifier
                        .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
                        .semantics { stateDescription = if (selected) "Selected" else "Not selected" }
                } else {
                    Modifier.semantics(mergeDescendants = true) {}
                },
            ).alpha(if (enabled) 1f else .45f)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(10.dp)).background(iconBg), contentAlignment = Alignment.Center) {
            LineIcon(icon, iconInk, size = 18.dp)
        }
        Text(
            title,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.5.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 16.sp),
        )
        Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = muted)
    }
}
