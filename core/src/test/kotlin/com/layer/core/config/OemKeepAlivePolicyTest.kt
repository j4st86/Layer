package com.layer.core.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemKeepAlivePolicyTest {
    @Test
    fun pocoX6ProIsXiaomiFamily() {
        assertTrue(
            OemKeepAlivePolicy.isXiaomiFamily(
                manufacturer = "Xiaomi",
                brand = "POCO",
                model = "2311DRK48G",
            ),
        )
    }

    @Test
    fun redmiBrandIsXiaomiFamily() {
        assertTrue(
            OemKeepAlivePolicy.isXiaomiFamily(
                manufacturer = "Xiaomi",
                brand = "Redmi",
                model = "23078PND5G",
            ),
        )
    }

    @Test
    fun hyperOsPropertyCountsWithoutXiaomiName() {
        assertTrue(
            OemKeepAlivePolicy.isXiaomiFamily(
                manufacturer = "",
                brand = "",
                hyperOsVersion = "2.0",
            ),
        )
    }

    @Test
    fun miuiPropertyCountsWithoutXiaomiName() {
        assertTrue(
            OemKeepAlivePolicy.isXiaomiFamily(
                manufacturer = "",
                brand = "",
                miuiVersion = "V140",
            ),
        )
    }

    @Test
    fun pixelIsNotXiaomiFamily() {
        assertFalse(
            OemKeepAlivePolicy.isXiaomiFamily(
                manufacturer = "Google",
                brand = "google",
                model = "Pixel 8",
            ),
        )
    }

    @Test
    fun samsungIsNotXiaomiFamily() {
        assertFalse(
            OemKeepAlivePolicy.isXiaomiFamily(
                manufacturer = "samsung",
                brand = "samsung",
                model = "SM-S911B",
            ),
        )
    }

    @Test
    fun xiaomiDoesNotNeedAospIgnoreBattery() {
        assertTrue(OemKeepAlivePolicy.treatsOemBackgroundAsUnrestricted(true))
        assertFalse(OemKeepAlivePolicy.treatsOemBackgroundAsUnrestricted(false))
    }
}
