package com.knte.preview.time.overlay

import org.junit.Assert.*
import org.junit.Test

class TimerOverlayPreparedDetailsTest {
    private val h = OpaqueHandle("opaque")
    private val target = TimerOverlayTarget("t", "项目", "2026-11-20 23:55", "本地页")

    @Test fun projectEditableOnlyWhileIdle() {
        val snapshot = TimerOverlaySnapshot(listOf(target))
        assertTrue(TimerOverlayText.projectEditable(TimerOverlayPhase.Idle(snapshot)))
        listOf(
            TimerOverlayPhase.Unloaded, TimerOverlayPhase.PreparingStart("项目", h),
            TimerOverlayPhase.PreparingStop(target, h), TimerOverlayPhase.Running(snapshot, "t"),
            TimerOverlayPhase.Busy("busy"), TimerOverlayPhase.Failed("failed"),
            TimerOverlayPhase.PendingReview("review"), TimerOverlayPhase.Confirmed("done"),
        ).forEach { assertFalse(TimerOverlayText.projectEditable(it)) }
    }

    @Test fun legacyPreparingConstructorsHaveNoFakeTimeDefaults() {
        val start = TimerOverlayPhase.PreparingStart("项目", h)
        val stop = TimerOverlayPhase.PreparingStop(target, h)
        assertNull(start.eventTimes)
        assertNull(stop.eventTimes)
        assertTrue(TimerOverlayText.preparingDetails(start).contains("计划开始：主机未提供"))
        assertTrue(TimerOverlayText.preparingDetails(stop).contains("计划停止：主机未提供"))
        assertFalse(TimerOverlayText.preparingDetails(start).contains("00:00"))
    }

    @Test fun startDetailsUseOnlyTheSuppliedBoundStartTime() {
        val phase = TimerOverlayPhase.PreparingStart("  项目  ", h, TimerOverlayEventTimes("2026-11-20 23:55"))
        val text = TimerOverlayText.preparingDetails(phase)
        assertTrue(text.contains("项目：  项目  \n"))
        assertTrue(text.contains("计划开始：2026-11-20 23:55"))
        assertFalse(text.contains("计划停止"))
        assertEquals(h, phase.handle)
    }

    @Test fun stopDetailsShowCrossDayBoundEndAndKeepTargetIdentity() {
        val phase = TimerOverlayPhase.PreparingStop(target, h,
            TimerOverlayEventTimes("2026-11-20 23:55", "2026-11-21 00:05"))
        val text = TimerOverlayText.preparingDetails(phase)
        assertTrue(text.contains("目标：t"))
        assertTrue(text.contains("来源：本地页"))
        assertTrue(text.contains("计划开始：2026-11-20 23:55"))
        assertTrue(text.contains("计划停止：2026-11-21 00:05"))
        assertEquals(h, phase.handle)
    }

    @Test fun partialTimeDataExplicitlyMarksTheMissingEnd() {
        val phase = TimerOverlayPhase.PreparingStop(target, h, TimerOverlayEventTimes(startText = target.startedAtText))
        assertTrue(TimerOverlayText.preparingDetails(phase).contains("计划停止：主机未提供"))
        assertNull(TimerOverlayEventTimes().startText)
        assertNull(TimerOverlayEventTimes().endText)
        assertEquals("", TimerOverlayText.preparingDetails(TimerOverlayPhase.Unloaded))
    }

    @Test(expected = IllegalArgumentException::class) fun blankStartIsNotAnActualTime() {
        TimerOverlayEventTimes(startText = " ")
    }
    @Test(expected = IllegalArgumentException::class) fun blankEndIsNotAnActualTime() {
        TimerOverlayEventTimes(endText = "")
    }
}
