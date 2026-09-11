package com.layer.core.diagnostics

/**
 * STAT from libbox is a periodic IPC. Logging every sample during music fills
 * the diagnostic buffer and keeps the process busy. Keep a quiet heartbeat so
 * a dead TUN still shows up in a report.
 */
object StatusLogPolicy {
    const val heartbeatMs = 120_000L

    fun shouldLog(nowMs: Long, lastLogMs: Long, uplink: Long, downlink: Long): Boolean {
        if (uplink > 0L || downlink > 0L) return false
        return lastLogMs <= 0L || nowMs - lastLogMs >= heartbeatMs
    }
}
