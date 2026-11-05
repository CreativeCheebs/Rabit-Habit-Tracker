package app.habitlab.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull


class SwipeActionsTest {
    @Test
    fun rightSwipeCommitsRightActionPastThreshold() {
        assertEquals(
            QuickSwipeAction.Done,
            resolveSwipeAction(120f, 96f, QuickSwipeAction.Done, QuickSwipeAction.Skipped),
        )
    }

    @Test
    fun leftSwipeCommitsLeftActionPastThreshold() {
        assertEquals(
            QuickSwipeAction.Skipped,
            resolveSwipeAction(-120f, 96f, QuickSwipeAction.Done, QuickSwipeAction.Skipped),
        )
    }

    @Test
    fun remappedDirectionsAreHonoured() {
        assertEquals(
            QuickSwipeAction.Partial,
            resolveSwipeAction(150f, 96f, QuickSwipeAction.Partial, QuickSwipeAction.Comment),
        )
        assertEquals(
            QuickSwipeAction.Comment,
            resolveSwipeAction(-150f, 96f, QuickSwipeAction.Partial, QuickSwipeAction.Comment),
        )
    }

    @Test
    fun atOrBelowThresholdNothingCommits() {
        assertNull(resolveSwipeAction(96f, 96f, QuickSwipeAction.Done, QuickSwipeAction.Skipped))
        assertNull(resolveSwipeAction(-96f, 96f, QuickSwipeAction.Done, QuickSwipeAction.Skipped))
        assertNull(resolveSwipeAction(0f, 96f, QuickSwipeAction.Done, QuickSwipeAction.Skipped))
    }

    @Test
    fun nonPositiveThresholdNeverCommits() {
        assertNull(resolveSwipeAction(500f, 0f, QuickSwipeAction.Done, QuickSwipeAction.Skipped))
    }

    @Test
    fun previewFollowsDirectionBelowThreshold() {
        assertEquals(
            QuickSwipeAction.Done,
            previewSwipeAction(10f, QuickSwipeAction.Done, QuickSwipeAction.Skipped),
        )
        assertEquals(
            QuickSwipeAction.Skipped,
            previewSwipeAction(-10f, QuickSwipeAction.Done, QuickSwipeAction.Skipped),
        )
        assertNull(previewSwipeAction(0f, QuickSwipeAction.Done, QuickSwipeAction.Skipped))
    }

    @Test
    fun progressIsClampedToUnitRange() {
        assertEquals(0f, swipeProgress(0f, 96f))
        assertEquals(0.5f, swipeProgress(48f, 96f))
        assertEquals(1f, swipeProgress(400f, 96f))
        assertEquals(1f, swipeProgress(-400f, 96f))
        assertEquals(0f, swipeProgress(50f, 0f))
    }

    @Test
    fun parseFallsBackOnUnknownOrBlank() {
        assertEquals(QuickSwipeAction.Done, QuickSwipeAction.parse("done", QuickSwipeAction.Skipped))
        assertEquals(QuickSwipeAction.Partial, QuickSwipeAction.parse("  Partial ", QuickSwipeAction.Done))
        assertEquals(QuickSwipeAction.Skipped, QuickSwipeAction.parse("bogus", QuickSwipeAction.Skipped))
        assertEquals(QuickSwipeAction.Done, QuickSwipeAction.parse(null, QuickSwipeAction.Done))
    }

    @Test
    fun commentWritesNoCheckInState() {
        assertEquals("done", swipeCheckInState(QuickSwipeAction.Done))
        assertEquals("partial", swipeCheckInState(QuickSwipeAction.Partial))
        assertEquals("skipped", swipeCheckInState(QuickSwipeAction.Skipped))
        assertNull(swipeCheckInState(QuickSwipeAction.Comment))
    }

    @Test
    fun actionListsRoundTripAndCapAtThree() {
        assertEquals(
            listOf(QuickSwipeAction.Done, QuickSwipeAction.Comment),
            parseSwipeActionList("done,comment", listOf(QuickSwipeAction.Skipped)),
        )
        assertEquals(
            listOf(QuickSwipeAction.Done, QuickSwipeAction.Partial, QuickSwipeAction.Skipped),
            parseSwipeActionList("done,partial,skipped,comment", listOf(QuickSwipeAction.Done)),
        )
        assertEquals(
            listOf(QuickSwipeAction.Skipped),
            parseSwipeActionList(null, listOf(QuickSwipeAction.Skipped)),
        )
        assertEquals(
            listOf(QuickSwipeAction.Skipped),
            parseSwipeActionList("bogus", listOf(QuickSwipeAction.Skipped)),
        )
        assertEquals(
            "done,comment",
            serializeSwipeActionList(listOf(QuickSwipeAction.Done, QuickSwipeAction.Comment)),
        )
        assertEquals(
            listOf(QuickSwipeAction.Done),
            parseSwipeActionList("done", listOf(QuickSwipeAction.Skipped)),
        )
    }

    @Test
    fun releasePastThresholdCommitsInDragDirection() {
        assertEquals(1, swipeReleaseDirection(120f, 0f, 96f, 1000f))
        assertEquals(-1, swipeReleaseDirection(-120f, 0f, 96f, 1000f))
        assertEquals(1, swipeReleaseDirection(120f, 2000f, 96f, 1000f))
    }

    @Test
    fun quickFlickCommitsOnShortDrag() {
        assertEquals(1, swipeReleaseDirection(30f, 1500f, 96f, 1000f))
        assertEquals(-1, swipeReleaseDirection(-30f, -1500f, 96f, 1000f))
    }

    @Test
    fun slowShortDragOrFlickBackSpringsBack() {
        assertEquals(0, swipeReleaseDirection(60f, 200f, 96f, 1000f))
        assertEquals(0, swipeReleaseDirection(150f, -1500f, 96f, 1000f))
        assertEquals(0, swipeReleaseDirection(0f, 0f, 96f, 1000f))
    }

    @Test
    fun revealOpensPastHalfButtonOrOnFlick() {
        assertEquals(RevealSettle.OpenRight, revealSettle(40f, 0f, 76f, 360f, 1000f))
        assertEquals(RevealSettle.OpenLeft, revealSettle(-40f, 0f, 76f, 360f, 1000f))
        assertEquals(RevealSettle.OpenRight, revealSettle(12f, 1500f, 76f, 360f, 1000f))
        assertEquals(RevealSettle.Closed, revealSettle(30f, 0f, 76f, 360f, 1000f))
    }

    @Test
    fun revealFlickBackCloses() {
        assertEquals(RevealSettle.Closed, revealSettle(76f, -1500f, 76f, 360f, 1000f))
        assertEquals(RevealSettle.Closed, revealSettle(-200f, 1500f, 76f, 360f, 1000f))
    }

    @Test
    fun revealHalfWidthDragCommits() {
        assertEquals(RevealSettle.CommitRight, revealSettle(180f, 0f, 76f, 360f, 1000f))
        assertEquals(RevealSettle.CommitLeft, revealSettle(-180f, -400f, 76f, 360f, 1000f))
        assertEquals(RevealSettle.OpenRight, revealSettle(179f, 0f, 76f, 360f, 1000f))
        assertEquals(RevealSettle.Closed, revealSettle(0f, 0f, 76f, 360f, 1000f))
    }

    @Test
    fun stampLabelsAreDistinct() {
        assertEquals("DONE", swipeStampLabel(QuickSwipeAction.Done))
        assertEquals("PARTIAL", swipeStampLabel(QuickSwipeAction.Partial))
        assertEquals("SKIPPED", swipeStampLabel(QuickSwipeAction.Skipped))
        assertEquals("NOTE", swipeStampLabel(QuickSwipeAction.Comment))
    }
}
