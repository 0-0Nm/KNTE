package com.knte.preview.time.overlay

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class TimerOverlayElapsedClockTest {
    private val start = LocalDateTime.of(2026, 11, 21, 10, 0)
    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test fun firstObservationIsMinuteOnlyThenSecondsUseMonotonicAdvance() {
        val clock = TimerOverlayElapsedClock()
        val firstWall = LocalDateTime.of(2026, 11, 21, 10, 2, 20)
        assertEquals("约2分钟（分钟级）", clock.estimate("A", start, firstWall, zone, 5_000))
        // One second of harmless capture skew must not become an extra elapsed second.
        val nextWall = firstWall.plusSeconds(60).plusSeconds(1)
        assertEquals("约00:03:20（秒为本次显示估算）",
            clock.estimate("A", start, nextWall, zone, 65_000))
    }

    @Test fun wallJumpDegradesToMinutePrecisionAndDoesNotReanchorSilently() {
        val clock = TimerOverlayElapsedClock()
        val firstWall = LocalDateTime.of(2026, 11, 21, 10, 2, 20)
        clock.estimate("A", start, firstWall, zone, 5_000)
        assertEquals("约3分钟（墙钟或时区变化，秒数已停用）",
            clock.estimate("A", start, firstWall.plusSeconds(70), zone, 65_000))
        assertEquals("约4分钟（时钟变化，秒数已停用）",
            clock.estimate("A", start, firstWall.plusSeconds(130), zone, 125_000))
    }

    @Test fun timezoneChangeNeverKeepsClaimingContinuousSeconds() {
        val clock = TimerOverlayElapsedClock()
        val now = LocalDateTime.of(2026, 11, 21, 10, 2, 20)
        clock.estimate("A", start, now, zone, 5_000)
        assertEquals("约2分钟（墙钟或时区变化，秒数已停用）",
            clock.estimate("A", start, now.plusSeconds(1), ZoneId.of("UTC"), 6_000))
    }

    @Test fun monotonicRollbackInvalidatesBootAnchor() {
        val clock = TimerOverlayElapsedClock()
        val now = LocalDateTime.of(2026, 11, 21, 10, 2, 20)
        clock.estimate("A", start, now, zone, 50_000)
        assertEquals("不可用（启动锚点已失效）",
            clock.estimate("A", start, now.plusSeconds(1), zone, 1_000))
    }

    @Test fun targetChangeCannotReusePreviousTargetsAnchor() {
        val clock = TimerOverlayElapsedClock()
        val now = LocalDateTime.of(2026, 11, 21, 10, 2, 20)
        clock.estimate("A", start, now, zone, 5_000)
        assertEquals("约2分钟（分钟级）", clock.estimate("B", start, now.plusSeconds(1), zone, 6_000))
    }

    @Test fun futureMinuteEventAndInvalidMonotonicInputAreUnavailable() {
        val clock = TimerOverlayElapsedClock()
        assertEquals("不可用（当前时间早于开始或启动锚点无效）",
            clock.estimate("A", start, start.minusMinutes(1), zone, 5_000))
        assertEquals("不可用（当前时间早于开始或启动锚点无效）",
            clock.estimate("A", start, start, zone, -1))
    }

    @Test fun durationFormattingCrossesHourAndDayBoundaries() {
        val clock = TimerOverlayElapsedClock()
        val now = start.plusHours(25).plusMinutes(1)
        clock.estimate("A", start, now, zone, 5_000)
        assertEquals("约25:01:01（秒为本次显示估算）",
            clock.estimate("A", start, now.plusSeconds(1), zone, 6_000))
    }
}
