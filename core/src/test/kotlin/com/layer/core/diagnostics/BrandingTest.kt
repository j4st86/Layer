package com.layer.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Test

class BrandingTest {
    @Test
    fun rewritesLegacyPixelnetSuffix() {
        assertEquals("1.14.0-layer", Branding.libboxVersion("1.14.0-pixelnet"))
        assertEquals("1.14.0-layer", Branding.libboxVersion("1.14.0-PixelNET"))
        assertEquals("1.14.0-layer", Branding.libboxVersion("1.14.0-layer"))
        assertEquals("unknown", Branding.libboxVersion("  "))
        assertEquals("unknown", Branding.libboxVersion(null))
    }
}
