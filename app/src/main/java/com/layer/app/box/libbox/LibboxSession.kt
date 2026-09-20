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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var logClient: CommandClient? = null
    private val logBridge = SingBoxLogBridge(
        diagnostics = diagnostics,
        onDisconnected = ::onStreamLost,
        onLogStreamReady = { logAttempts = 0 },
    )

    @Volatile
    private var closed = false
    private var logAttempts = 0
    private var reattachJob: Job? = null

    init {
        host.dbg("[BOX] event=command-server-start libbox=${BoxRuntime.version()}")
        commandServer.start()
    }

    fun startOrReload(json: String) {
        commandServer.startOrReloadService(json, OverrideOptions())
        // 1.14.1 serves the streams over gRPC, and GetDefaultLogLevel answers
        // "invalid argument" while the box instance is nil. Attaching from init
        // lost that race every time and killed core logs for the whole session.
        if (logClient == null) attachLogs()
    }

    fun wake() {
        commandServer.wake()
    }

    fun close() {
        closed = true
        reattachJob?.cancel()
        scope.cancel()
        runCatching { logClient?.disconnect() }
        runCatching { commandServer.closeService() }
        runCatching { commandServer.close() }
        tun.close()
        logClient = null
    }

    /**
     * A reload nils the instance, so both streams end on every reload and the
     * gomobile goroutines never come back on their own.
     */
    @Synchronized
    private fun onStreamLost(message: String?) {
        if (closed || reattachJob?.isActive == true) return
        if (logAttempts >= MAX_LOG_ATTEMPTS) {
            host.dbg("[BOX] event=log-client action=give-up attempts=$logAttempts")
            return
        }
        val attempt = ++logAttempts
        reattachJob = scope.launch {
            delay(REATTACH_BASE_MS * attempt)
            if (closed) return@launch
            host.dbg(
                "[BOX] event=log-client action=reattach attempt=$attempt " +
                    "after=${message?.takeIf { it.isNotBlank() } ?: "-"}",
            )
            attachLogs()
        }
    }

    @Synchronized
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

    private companion object {
        const val MAX_LOG_ATTEMPTS = 5
        const val REATTACH_BASE_MS = 1_000L
    }
}
