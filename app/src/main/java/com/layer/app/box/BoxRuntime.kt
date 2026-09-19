package com.layer.app.box

import android.content.Context
import com.layer.app.BuildConfig
import com.layer.core.diagnostics.Branding
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

/**
 * The only place static libbox helpers are called. Higher layers use this
 * instead of `io.nekohasekai.libbox.Libbox` so a future sing-ffi binding
 * can replace the implementation without touching UI or VpnService.
 */
object BoxRuntime {
    @Volatile
    private var ready = false

    fun setup(context: Context) {
        if (ready) return
        val working = File(context.filesDir, "sing-box").apply { mkdirs() }
        val options = SetupOptions().apply {
            basePath = context.filesDir.absolutePath
            workingPath = working.absolutePath
            tempPath = context.cacheDir.absolutePath
            fixAndroidStack = true
            logMaxLines = 2000L
            debug = BuildConfig.DEBUG
        }
        Libbox.setup(options)
        ready = true
    }

    fun setLocale(tag: String) {
        runCatching { Libbox.setLocale(tag) }
    }

    fun version(): String {
        return Branding.libboxVersion(runCatching { Libbox.version() }.getOrNull())
    }

    fun checkConfig(json: String) {
        Libbox.checkConfig(json)
    }

    val tag: String
        get() = BuildConfig.SINGBOX_TAG
}
