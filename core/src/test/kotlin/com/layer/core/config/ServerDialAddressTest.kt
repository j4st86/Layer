package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerDialAddressTest {
    @Test
    fun freshIpWinsOverRemembered() {
        assertEquals(
            "203.0.113.10",
            ServerDialAddress.select("203.0.113.10", "198.51.100.7"),
        )
    }

    @Test
    fun rememberedIpv4IsUsedWhenTheLookupMisses() {
        assertEquals(
            "213.21.237.173",
            ServerDialAddress.select(null, "213.21.237.173"),
        )
        assertEquals(
            "213.21.237.173",
            ServerDialAddress.select("  ", " 213.21.237.173 "),
        )
    }

    @Test
    fun hostnameAndBlankMemoryFallBackToNull() {
        assertNull(ServerDialAddress.select(null, null))
        assertNull(ServerDialAddress.select(null, ""))
        assertNull(ServerDialAddress.select(null, "prt.vladosina-portfolio.ru"))
        assertNull(ServerDialAddress.select(null, "213.21.237"))
    }

    @Test
    fun retryWindowCoversThePostBootDnsGap() {
        assertEquals(8_000L, ServerDialAddress.retryCount * ServerDialAddress.retryDelayMs)
    }
}
