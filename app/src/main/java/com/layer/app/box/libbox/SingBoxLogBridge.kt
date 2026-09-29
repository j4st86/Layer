package com.layer.app.box.libbox

import android.os.SystemClock
import com.layer.app.diagnostics.DiagnosticLog
import com.layer.app.vpn.VpnStatusStore
import com.layer.core.diagnostics.BoxLogFilter
import com.layer.core.diagnostics.BoxLogRateLimiter
import com.layer.core.diagnostics.DiagnosticConnections
import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.Connection
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupItemIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator

internal class SingBoxLogBridge(
    private val diagnostics: DiagnosticLog,
    private val onDisconnected: (String?) -> Unit,
    private val onLogStreamReady: () -> Unit,
) : CommandClientHandler {
    private var lastStatElapsed = 0L
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
        onDisconnected(message)
    }

    override fun initializeClashMode(modeList: StringIterator?, currentMode: String?) = Unit

    override fun setDefaultLogLevel(level: Int) {
        diagnostics.append("[BOX] event=log-level level=$level name=${levelName(level)}")
        onLogStreamReady()
    }

    override fun updateClashMode(mode: String?) = Unit

    override fun writeConnectionEvents(events: ConnectionEvents?) {
        if (events == null) return
        runCatching {
            if (events.reset) {
                diagnostics.append("[CONN] event=reset")
            }
            val iterator = events.iterator() ?: return
            while (iterator.hasNext()) {
                val event = iterator.next() ?: continue
                val kind = when (event.type.toLong()) {
                    Libbox.ConnectionEventNew -> "open"
                    Libbox.ConnectionEventClosed -> "close"
                    else -> continue
                }
                val conn = event.connection ?: continue
                val packages = packageNames(conn)
                val destination = conn.destination.orEmpty()
                val domain = conn.domain.orEmpty()
                if (!DiagnosticConnections.keep(destination, domain, packages)) continue
                val alive = if (kind == "close" && conn.closedAt > 0L && conn.createdAt > 0L) {
                    " aliveMs=${conn.closedAt - conn.createdAt}"
                } else {
                    ""
                }
                diagnostics.append(
                    "[CONN] event=$kind network=${conn.network.orEmpty()} " +
                        "dest=$destination domain=$domain " +
                        "pkg=${packages.joinToString(",").ifEmpty { "-" }} " +
                        "outbound=${conn.outbound.orEmpty()}$alive " +
                        "up=${conn.uplink} down=${conn.downlink}",
                )
            }
        }.onFailure {
            diagnostics.append("[CONN] event=read-failed error=${it.message}")
        }
    }

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
        VpnStatusStore.noteStatus(message.connectionsIn, message.connectionsOut)
        val now = SystemClock.elapsedRealtime()
        val gap = if (lastStatElapsed > 0L) now - lastStatElapsed else 0L
        if (gap >= STATUS_GAP_MS) {
            diagnostics.append("[STAT] event=resume gapMs=$gap ${VpnStatusStore.quietFields(now)}")
        }
        if (lastStatElapsed > 0L && gap < STATUS_INTERVAL_MS) return
        lastStatElapsed = now
        diagnostics.append(
            "[STAT] conn in=${message.connectionsIn} out=${message.connectionsOut} " +
                "up=${message.uplink} down=${message.downlink} " +
                "upTotal=${message.uplinkTotal} downTotal=${message.downlinkTotal} " +
                "mem=${message.memory} goroutines=${message.goroutines}",
        )
    }

    companion object {
        private const val STATUS_INTERVAL_MS = 8_000L
        private const val STATUS_GAP_MS = 30_000L

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

    private fun packageNames(conn: Connection): List<String> {
        val info = conn.processInfo ?: return emptyList()
        val names = info.packageNames() ?: return emptyList()
        return buildList {
            while (names.hasNext()) {
                names.next()?.takeIf { it.isNotBlank() }?.let { add(it) }
            }
        }
    }
}
