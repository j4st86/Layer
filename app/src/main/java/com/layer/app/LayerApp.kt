package com.layer.app

import android.app.Application
import com.layer.app.box.BoxRuntime
import com.layer.app.di.AppContainer
import com.layer.app.locale.AppLanguagePreferences
import com.layer.app.vpn.VpnNotification

class LayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        AppLanguagePreferences.applySaved(this, updateLibbox = false)
        super.onCreate()
        instance = this
        container = AppContainer(this)
        BoxRuntime.setup(this)
        val tag = if (com.layer.core.i18n.UiLanguageState.isRussian) "ru" else "en"
        BoxRuntime.setLocale(tag)
        VpnNotification.ensureChannel(this)
    }

    companion object {
        lateinit var instance: LayerApp
            private set
    }
}
