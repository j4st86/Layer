package com.layer.app.box.libbox

import android.net.VpnService
import com.layer.app.box.BoxCommandCallbacks
import com.layer.app.box.BoxHost
import com.layer.app.box.BoxRuntime
import com.layer.app.diagnostics.DiagnosticLog
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.SystemProxyStatus

/**
 * Owns the gomobile CommandServer, log client, platform interface and TUN fd.
 * LayerBoxService talks to this type and never imports libbox classes.
 */
class LibboxSession(
    vpn: VpnService,
    private val host: BoxHost,
    diagnostics: DiagnosticLog,
    private val commands: BoxCommandCallbacks,
) : CommandServerHandler {
    private val tun = LibboxTun(vpn, host)
    private val platform = SingBoxPlatform(vpn, host, tun)
    private val commandServer = CommandServer(this, platform)
    private var logClient: CommandClient? = null
    private val logBridge = SingBoxLogBridge(diagnostics)

    init {
        host.dbg("[BOX] event=command-server-start libbox=${BoxRuntime.version()}")
        commandServer.start()
        attachLogs()
    }

    fun startOrReload(json: String) {
        commandServer.startOrReloadService(json, OverrideOptions())
    }

    fun wake() {
        commandServer.wake()
    }

    fun close() {
        runCatching { logClient?.disconnect() }
        runCatching { commandServer.closeService() }
        runCatching { commandServer.close() }
        tun.close()
        logClient = null
    }

    private fun attachLogs() {
        runCatching { logClient?.disconnect() }
        logClient = null
        val options = CommandClientOptions().apply {
            addCommand(Libbox.CommandLog)
            addCommand(Libbox.CommandStatus)
            statusInterval = 1_000_000_000L
        }
        val client = CommandClient(logBridge, options)
        val connected = runCatching { client.connect() }
        if (connected.isFailure) {
            host.dbg("[BOX] event=log-client action=fail error=${connected.exceptionOrNull()?.message}")
            runCatching { client.connect() }
                .onFailure { host.dbg("[BOX] event=log-client action=retry-fail error=${it.message}") }
                .onSuccess { host.dbg("[BOX] event=log-client action=retry-ok") }
        } else {
            host.dbg("[BOX] event=log-client action=ok")
        }
        logClient = client
    }

    override fun serviceReload() = commands.onBoxReload()
    override fun serviceStop() = commands.onBoxStop()
    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus()
    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit
    override fun triggerNativeCrash() = Unit
    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) host.dbg("[BOX] $message")
    }
    override fun connectSSHAgent(): Int = -1
}
