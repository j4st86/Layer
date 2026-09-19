package com.layer.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnPhaseTest {
    @Test
    fun sessionIsLiveOnlyWhileStartingStartedOrReloading() {
        assertTrue(VpnPhase.Starting.isSessionLive())
        assertTrue(VpnPhase.Started.isSessionLive())
        assertTrue(VpnPhase.Reloading.isSessionLive())
        assertFalse(VpnPhase.Stopped.isSessionLive())
        assertFalse(VpnPhase.Stopping.isSessionLive())
        assertFalse(VpnPhase.Failed.isSessionLive())
    }

    @Test
    fun mapsToExistingUiStates() {
        assertEquals(VpnConnectionState.DISCONNECTED, VpnPhase.Stopped.toUi())
        assertEquals(VpnConnectionState.DISCONNECTED, VpnPhase.Stopping.toUi())
        assertEquals(VpnConnectionState.CONNECTING, VpnPhase.Starting.toUi())
        assertEquals(VpnConnectionState.CONNECTED, VpnPhase.Started.toUi())
        assertEquals(VpnConnectionState.RECONNECTING, VpnPhase.Reloading.toUi())
        assertEquals(VpnConnectionState.ERROR, VpnPhase.Failed.toUi())
    }
}
