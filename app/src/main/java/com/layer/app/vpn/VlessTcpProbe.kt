package com.layer.app.vpn

import android.net.Network
import android.os.SystemClock
import com.layer.app.diagnostics.DiagnosticLog
import com.layer.core.config.AutoServerPolicy
import com.layer.core.model.SavedServer
import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object VlessTcpProbe {
    suspend fun measureMedian(
        server: SavedServer,
        network: Network?,
        diagnostics: DiagnosticLog? = null,
        logPrefix: String = "[AUTO]",
    ): Long? = withContext(Dispatchers.IO) {
        val label = "${server.visibleName()} ${server.config.address}:${server.config.port}"
        val samples = mutableListOf<Long>()
        repeat(AutoServerPolicy.probeAttempts) { index ->
            val sample = measureOnce(
                server.config.address,
                server.config.port,
                network,
                diagnostics,
                "$label #${index + 1}",
                logPrefix,
            )
            if (sample != null) samples += sample
        }
        val median = AutoServerPolicy.median(samples)
        diagnostics?.append(
            "$logPrefix event=probe server=$label " +
                "bind=${if (network != null) "underlying" else "default"} " +
                "ok=${samples.size}/${AutoServerPolicy.probeAttempts} " +
                "samples=${if (samples.isEmpty()) "-" else samples.joinToString()} " +
                "median=${median?.let { "$it" } ?: "miss"}",
        )
        median
    }

    suspend fun measureAll(
        servers: List<SavedServer>,
        network: Network?,
        diagnostics: DiagnosticLog? = null,
        logPrefix: String = "[AUTO]",
    ): Map<String, Long?> = coroutineScope {
        servers.map { server ->
            async { server.id to measureMedian(server, network, diagnostics, logPrefix) }
        }.awaitAll().toMap()
    }

    private fun measureOnce(
        host: String,
        port: Int,
        network: Network?,
        diagnostics: DiagnosticLog?,
        label: String,
        logPrefix: String,
    ): Long? {
        val socket = Socket()
        return try {
            if (network != null) {
                try {
                    network.bindSocket(socket)
                } catch (error: Exception) {
                    diagnostics?.append(
                        "$logPrefix event=probe-bind server=$label " +
                            "error=${error.javaClass.simpleName}: ${error.message}",
                    )
                }
            }
            socket.tcpNoDelay = true
            val start = SystemClock.elapsedRealtime()
            socket.connect(InetSocketAddress(host, port), AutoServerPolicy.probeTimeoutMs)
            if (!socket.isConnected) {
                diagnostics?.append("$logPrefix event=probe-miss server=$label why=not-connected")
                null
            } else {
                SystemClock.elapsedRealtime() - start
            }
        } catch (error: Exception) {
            diagnostics?.append(
                "$logPrefix event=probe-miss server=$label " +
                    "error=${error.javaClass.simpleName}: ${error.message}",
            )
            null
        } finally {
            runCatching { socket.close() }
        }
    }
}
