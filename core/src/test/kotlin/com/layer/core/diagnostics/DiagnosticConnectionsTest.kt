package com.layer.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticConnectionsTest {
    @Test
    fun keepsTelegramSessionAndFcmAndDropsOrdinaryTraffic() {
        assertTrue(DiagnosticConnections.keep("149.154.167.41:5222", "", emptyList()))
        assertTrue(DiagnosticConnections.keep("[2001:db8::1]:5228", "", emptyList()))
        assertTrue(DiagnosticConnections.keep("8.8.8.8:443", "mtalk.google.com", emptyList()))
        assertTrue(
            DiagnosticConnections.keep(
                "142.250.1.1:443",
                "",
                listOf("com.google.android.gms"),
            ),
        )
        assertTrue(
            DiagnosticConnections.keep(
                "1.2.3.4:443",
                "",
                listOf("app.revanced.android.gms"),
            ),
        )
        assertFalse(DiagnosticConnections.keep("142.250.1.1:443", "youtubei.googleapis.com", emptyList()))
        assertFalse(
            DiagnosticConnections.keep(
                "142.250.1.1:443",
                "",
                listOf("com.google.android.gms.supervision"),
            ),
        )
        assertFalse(
            DiagnosticConnections.keep(
                "142.250.1.1:443",
                "",
                listOf("app.morphe.android.youtube"),
            ),
        )
    }
}
