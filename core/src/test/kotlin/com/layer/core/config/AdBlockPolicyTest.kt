package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdBlockPolicyTest {
    @Test
    fun sharesRefreshCadenceWithItdogLists() {
        assertEquals(RuleSetCatalog.FRESHNESS_MS, AdBlockPolicy.FRESHNESS_MS)
        assertEquals(RuleSetCatalog.UPDATE_INTERVAL, AdBlockPolicy.UPDATE_INTERVAL)
        assertEquals(3L * 24 * 60 * 60 * 1000, AdBlockPolicy.FRESHNESS_MS)
        assertEquals("rs-adguard", AdBlockPolicy.TAG)
    }
}
