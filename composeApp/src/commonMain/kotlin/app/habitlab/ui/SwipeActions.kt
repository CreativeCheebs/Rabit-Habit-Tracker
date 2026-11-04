package app.habitlab.ui

import kotlin.math.abs

/**
 * Actions a horizontal swipe can trigger on a habit card.
 *
 * Spec defaults stay Done on right swipe and Skipped on left swipe; the user
 * may remap each direction in the swipe settings. Comment never writes a
 * check-in: it opens the note sheet for the current habit instead.
 */
enum class QuickSwipeAction(val key: String) {
    Done("done"),
    Partial("partial"),
    Skipped("skipped"),
    Comment("comment");

    companion object {
        fun parse(raw: String?, fallback: QuickSwipeAction): QuickSwipeAction =
            entries.firstOrNull { it.key == raw?.trim()?.lowercase() } ?: fallback
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
fun swipeProgress(offsetX: Float, thresholdPx: Float): Float =
    if (thresholdPx <= 0f) 0f else (abs(offsetX) / thresholdPx).coerceIn(0f, 1f)

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
