package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun stripsVPrefix() {
        assertEquals("1.0.0", AppVersion.normalize("v1.0.0"))
        assertEquals("1.0.0", AppVersion.normalize("  V1.0.0 "))
    }

    @Test
    fun equalWithAndWithoutPrefix() {
        assertEquals(0, AppVersion.compare("1.0.0", "v1.0.0"))
        assertFalse(AppVersion.isNewer("1.0.0", "v1.0.0"))
    }

    @Test
    fun detectsNewerPatch() {
        assertTrue(AppVersion.isNewer("1.0.0", "1.0.1"))
        assertFalse(AppVersion.isNewer("1.0.1", "1.0.0"))
    }

    @Test
    fun comparesNumericSegments() {
        assertTrue(AppVersion.isNewer("1.9.0", "1.10.0"))
        assertTrue(AppVersion.isNewer("1.0.0", "2.0.0"))
    }

    @Test
    fun ignoresPreReleaseSuffixForCoreCompare() {
        assertEquals(0, AppVersion.compare("1.0.1-debug", "1.0.1"))
    }
}
