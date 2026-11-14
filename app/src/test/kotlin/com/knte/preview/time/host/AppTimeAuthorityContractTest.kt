package com.knte.preview.time.host

import org.junit.Assert.*
import org.junit.Test

class AppTimeAuthorityContractTest {
    @Test fun commandNamesAreBounded() {
        assertTrue(TimeMonitorServiceNames.actions.all { it.startsWith("com.knte.preview.timer.") })
        assertFalse(TimeMonitorServiceNames.actions.any { it.contains("STOP_RECORD") })
    }
}

private object TimeMonitorServiceNames {
    val actions = listOf("com.knte.preview.timer.MONITOR", "com.knte.preview.timer.SHOW", "com.knte.preview.timer.HIDE", "com.knte.preview.timer.STOP_MONITOR")
}
