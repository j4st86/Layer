package com.layer.app.locale

import android.content.Context
import android.content.res.Resources
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.layer.core.i18n.UiLanguage
import com.layer.core.i18n.UiLanguageState
import io.nekohasekai.libbox.Libbox

enum class AppLanguage {
    AUTO,
    RUSSIAN,
    ENGLISH,
    ;

    val storageKey: String
        get() = when (this) {
            AUTO -> "auto"
            RUSSIAN -> "ru"
            ENGLISH -> "en"
        }

    companion object {
        fun fromStorage(value: String?): AppLanguage = when (value) {
            "ru" -> RUSSIAN
            "en" -> ENGLISH
            else -> AUTO
        }
    }
}

object AppLanguagePreferences {
    private const val PREFS = "layer_language"
    private const val KEY = "mode"

    fun get(context: Context): AppLanguage {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "auto")
        return AppLanguage.fromStorage(raw)
    }

    fun set(context: Context, language: AppLanguage) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, language.storageKey)
            .apply()
        apply(language)
    }

    fun applySaved(context: Context, updateLibbox: Boolean = true) {
        apply(get(context), updateLibbox)
    }

    fun apply(language: AppLanguage, updateLibbox: Boolean = true) {
        val locales = when (language) {
            AppLanguage.AUTO -> LocaleListCompat.getEmptyLocaleList()
            AppLanguage.RUSSIAN -> LocaleListCompat.forLanguageTags("ru")
            AppLanguage.ENGLISH -> LocaleListCompat.forLanguageTags("en")
        }
        AppCompatDelegate.setApplicationLocales(locales)
        UiLanguageState.current = resolved(language)
        if (updateLibbox) {
            val tag = if (UiLanguageState.isRussian) "ru" else "en"
            runCatching { Libbox.setLocale(tag) }
        }
    }

    fun resolved(language: AppLanguage): UiLanguage = when (language) {
        AppLanguage.RUSSIAN -> UiLanguage.RUSSIAN
        AppLanguage.ENGLISH -> UiLanguage.ENGLISH
        AppLanguage.AUTO -> {
            val lang = Resources.getSystem().configuration.locales[0]?.language.orEmpty()
            if (lang.equals("ru", ignoreCase = true)) UiLanguage.RUSSIAN else UiLanguage.ENGLISH
        }
    }
}
