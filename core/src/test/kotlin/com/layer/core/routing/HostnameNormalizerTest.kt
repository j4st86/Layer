package com.layer.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostnameNormalizerTest {

    @Test
    fun stripsUrlToHostname() {
        val result = HostnameNormalizer.normalize("https://youtube.com/watch?v=123")
        assertTrue(result.isValid)
        assertEquals("youtube.com", result.domain)
    }

    @Test
    fun acceptsWildcardAsSuffix() {
        val result = HostnameNormalizer.normalize("*.example.com")
        assertTrue(result.isValid)
        assertEquals("example.com", result.domain)
    }

    @Test
    fun trimsSpacesAndScheme() {
        val result = HostnameNormalizer.normalize("  HTTP://Google.COM/search  ")
        assertTrue(result.isValid)
        assertEquals("google.com", result.domain)
    }

    @Test
    fun rejectsEmpty() {
        assertFalse(HostnameNormalizer.normalize("   ").isValid)
    }

    @Test
    fun rejectsSingleLabel() {
        val result = HostnameNormalizer.normalize("youtube")
        assertFalse(result.isValid)
    }

    @Test
    fun rejectsGarbage() {
        assertFalse(HostnameNormalizer.normalize("not a domain!!!").isValid)
        assertFalse(HostnameNormalizer.normalize("https://").isValid)
    }

    @Test
    fun suffixMatch() {
        assertTrue(HostnameNormalizer.matches("google.com", "gemini.google.com"))
        assertTrue(HostnameNormalizer.matches("google.com", "google.com"))
        assertFalse(HostnameNormalizer.matches("google.com", "notgoogle.com"))
    }

    @Test
    fun acceptsCyrillicRuZoneAsPunycode() {
        val result = HostnameNormalizer.normalize("https://Сайт.РУ/path")
        assertTrue(result.isValid)
        assertEquals("xn--80aswg.xn--p1ag", result.domain)
        assertEquals("сайт.ру", HostnameNormalizer.display(result.domain))
    }

    @Test
    fun acceptsCyrillicRfZoneAsPunycode() {
        val result = HostnameNormalizer.normalize("пример.рф")
        assertTrue(result.isValid)
        assertEquals("xn--e1afmkfd.xn--p1ai", result.domain)
        assertEquals("пример.рф", HostnameNormalizer.display(result.domain))
    }

    @Test
    fun matchesUnicodeIdnAgainstPunycodeHost() {
        assertTrue(HostnameNormalizer.matches("сайт.ру", "xn--80aswg.xn--p1ag"))
        assertTrue(HostnameNormalizer.matches("сайт.ру", "www.сайт.ру"))
        assertFalse(HostnameNormalizer.matches("сайт.ру", "несайт.ру"))
    }
}
