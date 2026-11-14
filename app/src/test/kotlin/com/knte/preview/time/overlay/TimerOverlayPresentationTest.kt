package com.knte.preview.time.overlay

import org.junit.Assert.*
import org.junit.Test

class TimerOverlayPresentationTest {
    private val a = TimerOverlayTarget("r-a", "写作", "2026-11-14 15:00", "本地页", "15:00")
    private val b = TimerOverlayTarget("r-b", "阅读", "2026-11-14 14:00", "本地页")

    @Test fun multipleRunningNeverChoosesImplicitTarget() {
        val p = TimerOverlayPresentationMapper.running(TimerOverlaySnapshot(listOf(a, b)))
        assertNull((p.phase as TimerOverlayPhase.Running).selectedTargetId)
        assertTrue(TimerOverlayText.phaseLabel(p.phase).contains("请选择目标"))
    }

    @Test fun startStopIntentsCarryOnlyBoundedTextIdOrOpaqueHandle() {
        assertEquals("项目", TimerOverlayText.projectInput("  项目  "))
        assertEquals(96, TimerOverlayText.projectInput("x".repeat(200)).length)
        val handle = OpaqueHandle("h-1")
        assertEquals(handle, (TimerOverlayIntent.Confirm(handle) as TimerOverlayIntent.Confirm).handle)
        assertEquals("r-a", (TimerOverlayIntent.Stop("r-a") as TimerOverlayIntent.Stop).targetId)
    }

    @Test fun detailsExposeProjectTargetTimeAndSource() {
        val text = TimerOverlayText.targetDetails(a)
        assertTrue(text.contains("写作")); assertTrue(text.contains("r-a"))
        assertTrue(text.contains("15:00")); assertTrue(text.contains("本地页"))
    }

    @Test fun failedAndPendingKeepLastGoodButDoNotBecomeRunning() {
        val snapshot = TimerOverlaySnapshot(listOf(a))
        assertTrue(TimerOverlayPresentationMapper.failed("读失败", snapshot).phase is TimerOverlayPhase.Failed)
        assertTrue(TimerOverlayPresentationMapper.pending("待核", snapshot).phase is TimerOverlayPhase.PendingReview)
    }

    @Test fun hideAndMonitorAreSeparateConcepts() {
        val p = TimerOverlayPresentation(monitor = TimerOverlayMonitor.Running)
        assertEquals(TimerOverlayMonitor.Running, p.monitor)
        assertTrue(TimerOverlayIntent.Hide is TimerOverlayIntent.Hide)
    }
}
