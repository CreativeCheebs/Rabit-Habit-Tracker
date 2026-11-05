package app.habitlab.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange

/**
 * Horizontal swipe that locks once the finger passes touch slop sideways.
 * Moves are reported synchronously so the card tracks the finger without a
 * frame of lag, and release hands back the fling velocity in px/s. Vertical
 * drags never pass the horizontal slop, so they still reach the scroller.
 * Downs already consumed by clickable children still start a swipe.
 */
fun Modifier.horizontalSwipe(
    key: Any?,
    canStart: () -> Boolean = { true },
    onStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit,
    onRelease: (velocityX: Float) -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!canStart()) return@awaitEachGesture
        val tracker = VelocityTracker()
        tracker.addPointerInputChange(down)
        var overSlop = 0f
        val start = awaitHorizontalTouchSlopOrCancellation(down.id) { change, over ->
            change.consume()
            overSlop = over
        } ?: return@awaitEachGesture
        tracker.addPointerInputChange(start)
        onStart()
        onDrag(overSlop, 0f)
        horizontalDrag(start.id) { change ->
            tracker.addPointerInputChange(change)
            val delta = change.positionChange()
            change.consume()
            onDrag(delta.x, delta.y)
        }
        onRelease(tracker.calculateVelocity().x)
    }
}
