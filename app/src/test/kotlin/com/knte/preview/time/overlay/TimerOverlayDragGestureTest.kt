package com.knte.preview.time.overlay

import org.junit.Assert.*
import org.junit.Test

class TimerOverlayDragGestureTest {
    @Test fun movementRequiresAnActiveGestureAndCrossingSlop() {
        val gesture = TimerOverlayDragGesture(5f)
        assertNull(gesture.move(20f, 30f))
        gesture.begin(10f, 10f)
        assertNull(gesture.move(13f, 14f))
        assertFalse(gesture.end())
    }

    @Test fun deltasAreIncrementalPhysicalPixelsAndIncludeInitialSlop() {
        val gesture = TimerOverlayDragGesture(5f)
        gesture.begin(100f, 200f)
        assertNull(gesture.move(102f, 202f))
        assertEquals(TimerOverlayDragGesture.Delta(6f, -2f), gesture.move(106f, 198f))
        assertEquals(TimerOverlayDragGesture.Delta(1f, 3f), gesture.move(107f, 201f))
        assertTrue(gesture.end())
        assertNull(gesture.move(120f, 210f))
    }

    @Test fun cancelAndNewGestureDoNotReuseOldOrigin() {
        val gesture = TimerOverlayDragGesture(0f)
        gesture.begin(1f, 2f)
        gesture.move(10f, 20f)
        gesture.end()
        gesture.begin(80f, 90f)
        assertEquals(TimerOverlayDragGesture.Delta(-2f, 1f), gesture.move(78f, 91f))
        gesture.begin(30f, 40f)
        assertEquals(TimerOverlayDragGesture.Delta(1f, 2f), gesture.move(31f, 42f))
    }

    @Test fun nonFiniteCoordinatesAbortRatherThanReachingWindowHost() {
        val gesture = TimerOverlayDragGesture(2f)
        gesture.begin(Float.NaN, 0f)
        assertNull(gesture.move(5f, 5f))
        gesture.begin(0f, 0f)
        assertNull(gesture.move(Float.POSITIVE_INFINITY, 0f))
        assertNull(gesture.move(5f, 5f))
    }

    @Test(expected = IllegalArgumentException::class) fun invalidSlopIsRejected() {
        TimerOverlayDragGesture(-1f)
    }
}
