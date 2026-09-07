package com.layer.core.config

/**
 * HyperOS / MIUI "No restrictions" (Нет ограничений) is not the AOSP
 * Unrestricted whitelist. [android.os.PowerManager.isIgnoringBatteryOptimizations]
 * stays false after the user sets Xiaomi's toggle, so a Pixel-style check
 * keeps nagging on Poco / Redmi / Xiaomi.
 *
 * Xiaomi documents [android.app.ActivityManager.isBackgroundRestricted] as
 * the signal that PowerKeeper is actually limiting the app. On that family,
 * a clear background-restricted flag is enough; the AOSP ignore-battery
 * whitelist is not required.
 */
object OemKeepAlivePolicy {
    fun isXiaomiFamily(
        manufacturer: String,
        brand: String,
        model: String = "",
        miuiVersion: String? = null,
        hyperOsVersion: String? = null,
    ): Boolean {
        val blob = "$manufacturer $brand $model".lowercase()
        if (XIAOMI_TOKENS.any { it in blob }) return true
        return !miuiVersion.isNullOrBlank() || !hyperOsVersion.isNullOrBlank()
    }

    fun treatsOemBackgroundAsUnrestricted(xiaomiFamily: Boolean): Boolean = xiaomiFamily

    private val XIAOMI_TOKENS = listOf("xiaomi", "redmi", "poco", "blackshark")
}
