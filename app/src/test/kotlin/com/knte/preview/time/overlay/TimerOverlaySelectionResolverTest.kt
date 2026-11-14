package com.knte.preview.time.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerOverlaySelectionResolverTest {
    private val a = TimerOverlayTarget("a", "A".repeat(100), "2026-11-14 15:00", "local")
    private val b = TimerOverlayTarget("b", "B", "2026-11-14 14:00", "local")

    @Test fun currentSnapshotMemberResolvesExactly() {
        assertEquals(a, TimerOverlaySelectionResolver.resolve(TimerOverlaySnapshot(listOf(a, b)), "a"))
    }

    @Test fun nullBlankAndUnknownSelectionFailClosed() {
        val snapshot = TimerOverlaySnapshot(listOf(a, b))
        assertNull(TimerOverlaySelectionResolver.resolve(snapshot, null))
        assertNull(TimerOverlaySelectionResolver.resolve(snapshot, "   "))
        assertNull(TimerOverlaySelectionResolver.resolve(snapshot, "removed-id"))
    }

    @Test fun removedSelectionAfterSnapshotReplacementDoesNotGuessAnotherTarget() {
        val old = TimerOverlaySnapshot(listOf(a, b))
        val current = TimerOverlaySnapshot(listOf(b))
        assertNull(TimerOverlaySelectionResolver.resolve(current, "a"))
    }

    @Test fun aSingleInvalidSelectionDoesNotChooseTheOnlyTarget() {
        val snapshot = TimerOverlaySnapshot(listOf(a))
        assertNull(TimerOverlaySelectionResolver.resolve(snapshot, "foreign-id"))
    }

    @Test fun duplicateIdsAreNotAnUnambiguousTarget() {
        val duplicate = TimerOverlaySnapshot(listOf(a, a.copy(projectText = "other")))
        assertNull(TimerOverlaySelectionResolver.resolve(duplicate, "a"))
    }

    @Test fun nonRunningPhasesNeverResolveStopTargets() {
        val snapshot = TimerOverlaySnapshot(listOf(a))
        assertNull(TimerOverlaySelectionResolver.resolve(TimerOverlayPhase.Idle(snapshot)))
        assertNull(TimerOverlaySelectionResolver.resolve(TimerOverlayPhase.Busy("busy")))
        assertNull(TimerOverlaySelectionResolver.resolve(TimerOverlayPhase.PendingReview("review", snapshot)))
        assertNull(TimerOverlaySelectionResolver.resolve(TimerOverlayPhase.PreparingStop(a, OpaqueHandle("h"))))
    }
}
