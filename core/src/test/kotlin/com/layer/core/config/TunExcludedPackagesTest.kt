package com.layer.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunExcludedPackagesTest {
    @Test
    fun excludesFcmSocketsAndLeavesMorpheAppsInTheTunnel() {
        val excluded = TunExcludedPackages.resolve(
            ownPackageName = "com.layer.app",
            installedPackages = listOf(
                "com.layer.app",
                "com.google.android.gms",
                "com.google.android.gsf",
                "app.morphe.android.gms",
                "app.revanced.android.gms",
                "app.morphe.android.youtube",
                "app.morphe.android.apps.youtube.music",
                "org.telegram.messenger",
            ),
        )
        assertEquals(
            listOf(
                "com.layer.app",
                "com.google.android.gms",
                "com.google.android.gsf",
                "app.morphe.android.gms",
                "app.revanced.android.gms",
            ),
            excluded,
        )
        assertFalse(TunExcludedPackages.isMicroGPushPackage("com.google.android.gms"))
        assertFalse(TunExcludedPackages.isMicroGPushPackage("app.morphe.android.youtube"))
        assertTrue(TunExcludedPackages.isMicroGPushPackage("app.morphe.android.gms"))
    }

    @Test
    fun skipsFrameworkWhenItIsNotInstalled() {
        val excluded = TunExcludedPackages.resolve(
            ownPackageName = "com.layer.app",
            installedPackages = listOf("com.layer.app", "com.google.android.gms"),
        )
        assertFalse(excluded.contains(TunExcludedPackages.SERVICES_FRAMEWORK))
    }
}
