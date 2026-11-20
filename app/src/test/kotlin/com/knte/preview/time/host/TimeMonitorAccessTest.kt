package com.knte.preview.time.host

import org.junit.Assert.*
import org.junit.Test

class TimeMonitorAccessTest {
    @Test fun bothGrantsAreRequired() {
        assertFalse(MonitorAccess(overlay = false, notifications = false).allowed)
        assertFalse(MonitorAccess(overlay = true, notifications = false).allowed)
        assertFalse(MonitorAccess(overlay = false, notifications = true).allowed)
        assertTrue(MonitorAccess(overlay = true, notifications = true).allowed)
    }

    @Test fun eachRevocationBlocksFurtherMonitorActions() {
        val granted = MonitorAccess(overlay = true, notifications = true)
        assertFalse(granted.copy(overlay = false).allowed)
        assertFalse(granted.copy(notifications = false).allowed)
    }

    @Test fun hiddenWindowKeepsMonitorRunningButFailureDoesNot() {
        assertTrue(MonitorStatus.Visible.active)
        assertTrue(MonitorStatus.Visible.visible)
        assertTrue(MonitorStatus.Hidden.active)
        assertFalse(MonitorStatus.Hidden.visible)
        assertFalse(MonitorStatus.Off.active)
        assertFalse(MonitorStatus.Failed.active)
    }
}
