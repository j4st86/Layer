package com.layer.core.config

import com.layer.core.model.AppRoutingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendedAppsTest {
    @Test
    fun addsOnlyInstalledRecommendedApps() {
        val installed = mapOf(
            "org.telegram.messenger" to "Telegram",
            "com.google.android.youtube" to "YouTube",
            "com.android.chrome" to "Chrome",
            "com.spotify.music" to "Spotify",
        )
        val rules = RecommendedApps.rulesToAdd(installed, existingPackageNames = emptySet())
        assertEquals(
            listOf("org.telegram.messenger", "com.google.android.youtube", "com.spotify.music"),
            rules.map { it.packageName },
        )
        assertTrue(rules.all { it.mode == AppRoutingMode.VPN })
    }

    @Test
    fun skipsAppsAlreadyInActiveList() {
        val installed = mapOf(
            "org.telegram.messenger" to "Telegram",
            "com.spotify.music" to "Spotify",
        )
        val rules = RecommendedApps.rulesToAdd(
            installed,
            existingPackageNames = setOf("org.telegram.messenger"),
        )
        assertEquals(listOf("com.spotify.music"), rules.map { it.packageName })
    }

    @Test
    fun usesInstalledLabelAndKeepsEmptyWhenNothingMatches() {
        val installed = mapOf("org.telegram.messenger.web" to "Telegram Web")
        val rules = RecommendedApps.rulesToAdd(installed, existingPackageNames = emptySet())
        assertEquals("Telegram Web", rules.single().appName)
        assertTrue(RecommendedApps.rulesToAdd(mapOf("com.android.chrome" to "Chrome"), emptySet()).isEmpty())
    }

    @Test
    fun assignsDirectModeToLocalServices() {
        val installed = mapOf(
            "ru.ozon.app.android" to "OZON",
            "com.vkontakte.android" to "VK",
            "org.telegram.messenger" to "Telegram",
        )
        val rules = RecommendedApps.rulesToAdd(installed, existingPackageNames = emptySet())
        assertEquals(AppRoutingMode.DIRECT, rules.first { it.packageName == "ru.ozon.app.android" }.mode)
        assertEquals(AppRoutingMode.DIRECT, rules.first { it.packageName == "com.vkontakte.android" }.mode)
        assertEquals(AppRoutingMode.VPN, rules.first { it.packageName == "org.telegram.messenger" }.mode)
    }
}
