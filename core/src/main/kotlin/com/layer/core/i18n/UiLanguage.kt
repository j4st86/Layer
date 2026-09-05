package com.layer.core.i18n

enum class UiLanguage {
    ENGLISH,
    RUSSIAN,
}

object UiLanguageState {
    @Volatile
    var current: UiLanguage = UiLanguage.ENGLISH

    val isRussian: Boolean get() = current == UiLanguage.RUSSIAN
}

fun copy(en: String, ru: String): String = if (UiLanguageState.isRussian) ru else en
