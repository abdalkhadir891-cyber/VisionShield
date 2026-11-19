package com.yusuf.shieldguard

import com.yusuf.shieldguard.data.ProtectionStateHolder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ProtectionStateHolderTest {

    @Before
    fun setup() {
        ProtectionStateHolder.resetForTest()
    }

    @Test
    fun updatesForegroundWindowAndCountsProtectedEvents() = runTest {
        ProtectionStateHolder.updateForegroundWindow("com.facebook.katana", isAccessible = true)
        assertEquals("com.facebook.katana", ProtectionStateHolder.state.value.activeForegroundPackage)
        assertTrue(ProtectionStateHolder.state.value.isCurrentWindowAccessible)
        assertEquals(0L, ProtectionStateHolder.state.value.protectedWindowEvents)

        ProtectionStateHolder.updateForegroundWindow("com.bank.secure", isAccessible = false)
        assertFalse(ProtectionStateHolder.state.value.isCurrentWindowAccessible)
        assertEquals(1L, ProtectionStateHolder.state.value.protectedWindowEvents)
    }

    @Test
    fun tracksLifecycleAndFrameCounter() = runTest {
        ProtectionStateHolder.setForegroundServiceRunning(true)
        ProtectionStateHolder.setAccessibilityConnected(true)
        ProtectionStateHolder.incrementFramesAnalyzed()
        ProtectionStateHolder.incrementFramesAnalyzed()

        val state = ProtectionStateHolder.state.value
        assertTrue(state.isForegroundServiceRunning)
        assertTrue(state.isAccessibilityServiceConnected)
        assertEquals(2L, state.framesAnalyzed)
    }
}
