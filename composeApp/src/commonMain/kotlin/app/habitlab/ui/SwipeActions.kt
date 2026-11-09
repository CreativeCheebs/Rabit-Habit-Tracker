package app.habitlab.ui

import kotlin.math.abs
import kotlin.math.sign

/**
 * Actions a horizontal swipe can trigger on a habit card.
 *
 * Spec defaults stay Done on right swipe and Skipped on left swipe; the user
 * may remap each direction in the swipe settings. Comment never writes a
 * check-in: it opens the note sheet for the current habit instead.
 */
enum class QuickSwipeAction(
    val key: String,
) {
    Done("done"),
    Partial("partial"),
    Skipped("skipped"),
    Comment("comment"),
    ;

    companion object {
        fun parse(
            raw: String?,
            fallback: QuickSwipeAction,
        ): QuickSwipeAction = entries.firstOrNull { it.key == raw?.trim()?.lowercase() } ?: fallback
    }
}

const val SWIPE_RIGHT_KEY = "quicklog_swipe_right"
const val SWIPE_LEFT_KEY = "quicklog_swipe_left"

/** Committed action once the drag passes [thresholdPx], or null below threshold. */
fun resolveSwipeAction(
    offsetX: Float,
    thresholdPx: Float,
    right: QuickSwipeAction,
    left: QuickSwipeAction,
): QuickSwipeAction? =
    when {
        thresholdPx <= 0f -> null
        offsetX > thresholdPx -> right
        offsetX < -thresholdPx -> left
        else -> null
    }

/** Direction preview while dragging, even below the commit threshold. */
fun previewSwipeAction(
    offsetX: Float,
    right: QuickSwipeAction,
    left: QuickSwipeAction,
): QuickSwipeAction? =
    when {
        offsetX > 0f -> right
        offsetX < 0f -> left
        else -> null
    }

/** 0..1 progress used to fade in the Gmail-style background and stamp. */
fun swipeProgress(
    offsetX: Float,
    thresholdPx: Float,
): Float = if (thresholdPx <= 0f) 0f else (abs(offsetX) / thresholdPx).coerceIn(0f, 1f)

fun swipeStampLabel(action: QuickSwipeAction): String =
    when (action) {
        QuickSwipeAction.Done -> "DONE"
        QuickSwipeAction.Partial -> "PARTIAL"
        QuickSwipeAction.Skipped -> "SKIPPED"
        QuickSwipeAction.Comment -> "NOTE"
    }

/** Check-in state written for terminal actions; null means "open comment, log nothing". */
fun swipeCheckInState(action: QuickSwipeAction): String? =
    when (action) {
        QuickSwipeAction.Done -> "done"
        QuickSwipeAction.Partial -> "partial"
        QuickSwipeAction.Skipped -> "skipped"
        QuickSwipeAction.Comment -> null
    }

/** Parses a comma-joined stored action list; legacy single values still parse. */
fun parseSwipeActionList(
    raw: String?,
    fallback: List<QuickSwipeAction>,
): List<QuickSwipeAction> {
    if (raw.isNullOrBlank()) return fallback
    val parsed =
        raw
            .split(",")
            .mapNotNull { token -> QuickSwipeAction.entries.firstOrNull { it.key == token.trim().lowercase() } }
            .distinct()
            .take(3)
    return parsed.ifEmpty { fallback }
}

fun serializeSwipeActionList(actions: List<QuickSwipeAction>): String = actions.joinToString(",") { it.key }

/**
 * Where a released Quick Log card goes: 1 flies right, -1 flies left, 0
 * springs back. Dragging past the threshold commits unless the finger is
 * flicking back; a fast flick commits even on a short drag, like a card stack.
 */
fun swipeReleaseDirection(
    offsetX: Float,
    velocityX: Float,
    thresholdPx: Float,
    flingVelocityPx: Float,
): Int {
    val flinging = abs(velocityX) >= flingVelocityPx
    return when {
        abs(offsetX) >= thresholdPx ->
            if (flinging && sign(velocityX) != sign(offsetX)) 0 else sign(offsetX).toInt()
        flinging -> sign(velocityX).toInt()
        else -> 0
    }
}

/** Resting state of a swipe-to-reveal row after the finger lifts. */
enum class RevealSettle { Closed, OpenRight, OpenLeft, CommitRight, CommitLeft }

/**
 * Swipe-to-reveal decision. A drag past half the row width fires the action
 * directly; a flick opens or closes the tray in its direction; otherwise the
 * tray stays open once more than half a button is uncovered.
 */
fun revealSettle(
    offsetX: Float,
    velocityX: Float,
    panelPx: Float,
    fullWidthPx: Float,
    flingVelocityPx: Float,
): RevealSettle {
    if (offsetX == 0f || panelPx <= 0f) return RevealSettle.Closed
    val right = offsetX > 0f
    val flinging = abs(velocityX) >= flingVelocityPx
    val flingingBack = flinging && sign(velocityX) != sign(offsetX)
    return when {
        fullWidthPx > 0f && abs(offsetX) >= fullWidthPx * REVEAL_COMMIT_FRACTION && !flingingBack ->
            if (right) RevealSettle.CommitRight else RevealSettle.CommitLeft
        flingingBack -> RevealSettle.Closed
        flinging || abs(offsetX) >= panelPx * 0.5f ->
            if (right) RevealSettle.OpenRight else RevealSettle.OpenLeft
        else -> RevealSettle.Closed
    }
}

/** Fraction of the row width a drag must cover before release fires the action. */
const val REVEAL_COMMIT_FRACTION = 0.5f
