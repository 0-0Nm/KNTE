package com.knte.preview.time.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimerOverlayWindowGeometryTest {
    private val area = TimerOverlayWindowArea(left = 20, top = 40, right = 1000, bottom = 800)

    @Test fun physicalDeltasClampToRightAndBottomSafeArea() {
        assertEquals(TimerOverlayWindowPosition(700, 600),
            TimerOverlayWindowGeometry.drag(500, 400, 900.4f, 900.4f, 300, 200, area))
    }

    @Test fun negativeDeltasClampToLeftAndTopIncludingInsets() {
        assertEquals(TimerOverlayWindowPosition(20, 40),
            TimerOverlayWindowGeometry.drag(100, 100, -1000f, -1000f, 300, 200, area))
    }

    @Test fun fractionalPhysicalPixelsRoundWithoutDpConversion() {
        assertEquals(TimerOverlayWindowPosition(102, 98),
            TimerOverlayWindowGeometry.drag(100, 100, 1.6f, -1.6f, 10, 10, area))
    }

    @Test fun nonFiniteDeltasCannotReachWindowManager() {
        assertNull(TimerOverlayWindowGeometry.drag(100, 100, Float.NaN, 0f, 10, 10, area))
        assertNull(TimerOverlayWindowGeometry.drag(100, 100, 0f, Float.POSITIVE_INFINITY, 10, 10, area))
    }

    @Test fun oversizedViewIsAnchoredAtSafeOriginWithoutUnderflow() {
        val extreme = TimerOverlayWindowArea(Int.MIN_VALUE, -100, Int.MAX_VALUE, 100)
        assertEquals(TimerOverlayWindowPosition(0, -100),
            TimerOverlayWindowGeometry.drag(0, 0, 0f, 0f, Int.MAX_VALUE, 500, extreme))
    }
}
