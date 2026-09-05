package com.layer.app

import android.app.Application
import com.layer.app.di.AppContainer
import com.layer.app.locale.AppLanguagePreferences
import com.layer.app.vpn.VpnNotification
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class LayerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        AppLanguagePreferences.applySaved(this, updateLibbox = false)
        super.onCreate()
        instance = this
        container = AppContainer(this)
        initLibbox()
        VpnNotification.ensureChannel(this)
    }

    private fun initLibbox() {
        val working = File(filesDir, "sing-box").apply { mkdirs() }
        val options = SetupOptions()
        options.basePath = filesDir.absolutePath
        options.workingPath = working.absolutePath
        options.tempPath = cacheDir.absolutePath
        options.fixAndroidStack = true
        options.logMaxLines = 2000L
        options.debug = com.layer.app.BuildConfig.DEBUG
        Libbox.setup(options)
        val tag = if (com.layer.core.i18n.UiLanguageState.isRussian) "ru" else "en"
        runCatching { Libbox.setLocale(tag) }
    }

    companion object {
        lateinit var instance: LayerApp
            private set
    }
}
