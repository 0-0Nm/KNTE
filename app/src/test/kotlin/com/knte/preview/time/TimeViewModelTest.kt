package com.knte.preview.time

import com.knte.preview.time.host.OpaqueHostHandle
import org.junit.Assert.*
import org.junit.Test

/** Host contract tests that do not fake the controller or the durable journal. */
class TimeViewModelTest {
    @Test fun projectLimitIsExplicitAndDoesNotCreateBusinessState() {
        assertEquals(96, TIME_PROJECT_LIMIT)
        assertTrue(TimeUiState().canLeave)
        assertFalse(TimeUiState(draft = TimeDraft("x")).canLeave)
    }
    @Test fun hostHandlesRemainOpaqueStrings() {
        val a = OpaqueHostHandle("a")
        val b = OpaqueHostHandle("b")
        assertNotEquals(a, b)
        assertEquals("a", a.value)
    }
}
