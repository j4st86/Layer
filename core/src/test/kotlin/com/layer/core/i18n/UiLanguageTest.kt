package com.layer.core.i18n

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class UiLanguageTest {
    @After
    fun resetLanguage() {
        UiLanguageState.current = UiLanguage.ENGLISH
    }

    @Test
    fun copyFollowsCurrentLanguage() {
        UiLanguageState.current = UiLanguage.ENGLISH
        assertEquals("Hello", copy("Hello", "Привет"))
        UiLanguageState.current = UiLanguage.RUSSIAN
        assertEquals("Привет", copy("Hello", "Привет"))
    }
}
