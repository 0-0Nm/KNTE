package com.knte.preview.time.overlay

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.abs

/** Display-only elapsed estimate. The first observation has no seconds anchor; later seconds
 * advance only by same-process elapsedRealtime. A wall/zone discontinuity permanently degrades
 * that target until it changes or this short-lived clock is recreated by the Service.
 */
internal class TimerOverlayElapsedClock {
    private var targetId: String? = null
    private var anchorElapsedMillis: Long? = null
    private var anchorWall: LocalDateTime? = null
    private var anchorZone: ZoneId? = null
    private var anchorDurationMillis = 0L
    private var degraded = false

    fun reset() {
        targetId = null
        anchorElapsedMillis = null
        anchorWall = null
        anchorZone = null
        anchorDurationMillis = 0L
        degraded = false
    }

    fun estimate(
        targetId: String,
        minuteStart: LocalDateTime,
        now: LocalDateTime,
        zone: ZoneId,
        elapsedRealtimeMillis: Long,
    ): String {
        if (this.targetId != targetId) {
            reset()
            this.targetId = targetId
        }
        val wallDuration = try { Duration.between(minuteStart, now) }
        catch (_: ArithmeticException) { return "不可用（时间超出范围）" }
        if (wallDuration.isNegative || elapsedRealtimeMillis < 0L) return "不可用（当前时间早于开始或启动锚点无效）"

        val anchoredElapsed = anchorElapsedMillis
        val anchoredWall = anchorWall
        if (degraded) return minuteLabel(wallDuration, "时钟变化，秒数已停用")
        if (anchoredElapsed == null || anchoredWall == null) {
            anchorElapsedMillis = elapsedRealtimeMillis
            anchorWall = now
            anchorZone = zone
            anchorDurationMillis = try { wallDuration.toMillis() }
            catch (_: ArithmeticException) { return minuteLabel(wallDuration, "无有效秒数锚点") }
            return minuteLabel(wallDuration, "分钟级")
        }
        if (elapsedRealtimeMillis < anchoredElapsed) {
            degraded = true
            anchorElapsedMillis = null
            return "不可用（启动锚点已失效）"
        }
        val monotonicAdvance = elapsedRealtimeMillis - anchoredElapsed
        val expectedWall = try { anchoredWall.plusNanos(Math.multiplyExact(monotonicAdvance, 1_000_000L)) }
        catch (_: ArithmeticException) {
            degraded = true
            return minuteLabel(wallDuration, "时钟变化，秒数已停用")
        } catch (_: java.time.DateTimeException) {
            degraded = true
            return minuteLabel(wallDuration, "时钟变化，秒数已停用")
        }
        val wallSkewMillis = try { abs(Duration.between(expectedWall, now).toMillis()) }
        catch (_: ArithmeticException) { Long.MAX_VALUE }
        if (zone != anchorZone || wallSkewMillis > WALL_SKEW_TOLERANCE_MILLIS) {
            degraded = true
            anchorElapsedMillis = null
            return minuteLabel(wallDuration, "墙钟或时区变化，秒数已停用")
        }
        val estimatedMillis = try { Math.addExact(anchorDurationMillis, monotonicAdvance) }
        catch (_: ArithmeticException) { return "不可用（计时超出范围）" }
        return "约${durationText(estimatedMillis)}（秒为本次显示估算）"
    }

    private fun minuteLabel(duration: Duration, suffix: String): String {
        val minutes = try { duration.toMinutes() } catch (_: ArithmeticException) { return "不可用（计时超出范围）" }
        return "约${minutes}分钟（$suffix）"
    }

    private companion object {
        const val WALL_SKEW_TOLERANCE_MILLIS = 2_000L

        fun durationText(milliseconds: Long): String {
            val totalSeconds = milliseconds / 1_000L
            val hours = totalSeconds / 3_600L
            val minutes = totalSeconds / 60L % 60L
            val seconds = totalSeconds % 60L
            return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
        }
    }
}
