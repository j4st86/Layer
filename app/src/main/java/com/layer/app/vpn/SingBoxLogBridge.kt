package com.layer.app.vpn

import com.layer.app.diagnostics.DiagnosticLog
import com.layer.core.diagnostics.BoxLogFilter
import com.layer.core.diagnostics.BoxLogRateLimiter
import com.layer.core.diagnostics.StatusLogPolicy
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator

class SingBoxLogBridge(private val diagnostics: DiagnosticLog) : CommandClientHandler {
    private var lastStatMs = 0L
    private val rateLimiter = BoxLogRateLimiter()

    override fun clearLogs() = Unit

    override fun connected() {
        diagnostics.append("[BOX] event=log-stream action=connected")
    }

    override fun disconnected(message: String?) {
        diagnostics.append(
            "[BOX] event=log-stream action=disconnected" +
                (message?.let { " error=$it" } ?: ""),
        )
    }

    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit

    override fun setDefaultLogLevel(level: Int) {
        diagnostics.append("[BOX] event=log-level level=$level name=${levelName(level)}")
    }

    override fun updateClashMode(mode: String?) = Unit

    override fun writeConnectionEvents(events: ConnectionEvents?) = Unit

    override fun writeGroups(groups: OutboundGroupIterator?) = Unit

    override fun writeLogs(message: LogIterator?) {
        if (message == null) return
        val now = System.currentTimeMillis()
        while (message.hasNext()) {
            val entry = message.next() ?: continue
            val text = entry.message?.trim().orEmpty()
            if (text.isBlank()) continue
            val level = levelName(entry.level)
            if (!BoxLogFilter.keep(level, text)) continue
            val fingerprint = BoxLogFilter.fingerprint(level, text)
            when (val decision = rateLimiter.allow(fingerprint, now)) {
                BoxLogRateLimiter.Decision.Drop -> continue
                BoxLogRateLimiter.Decision.Emit ->
                    diagnostics.append("[BOX:$level] $text")
                is BoxLogRateLimiter.Decision.EmitHidden ->
                    diagnostics.append("[BOX:$level] $text (+${decision.count} similar hidden)")
            }
        }
    }

    override fun writeOutbounds(outbounds: OutboundGroupItemIterator?) = Unit

    override fun writeStatus(message: StatusMessage?) {
        if (message == null) return
        if (message.uplink > 0L || message.downlink > 0L) {
            VpnStatusStore.noteTraffic()
        }
        val now = System.currentTimeMillis()
        if (!StatusLogPolicy.shouldLog(now, lastStatMs, message.uplink, message.downlink)) {
            return
        }
        lastStatMs = now
        diagnostics.append(
            "[STAT] conn in=${message.connectionsIn} out=${message.connectionsOut} " +
                "up=${message.uplink} down=${message.downlink} " +
                "upTotal=${message.uplinkTotal} downTotal=${message.downlinkTotal} " +
                "mem=${message.memory} goroutines=${message.goroutines}",
        )
    }

    companion object {
        fun levelName(level: Int): String = when (level) {
            0 -> "panic"
            1 -> "fatal"
            2 -> "error"
            3 -> "warn"
            4 -> "info"
            5 -> "debug"
            6 -> "trace"
            else -> "lv$level"
        }
    }
}
