package com.layer.app.diagnostics

import android.util.Log
import com.layer.core.diagnostics.DiagnosticEntry
import com.layer.core.diagnostics.DiagnosticSnapshots
import com.layer.core.diagnostics.LogSanitizer
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class DiagnosticLog {
    private val snapshots = DiagnosticSnapshots()
    val entries: Flow<List<DiagnosticEntry>> = snapshots.entries

    @Volatile
    var lastTunDump: String = ""

    @Volatile
    var lastStartedConfig: String = ""

    fun append(message: String) {
        val clean = LogSanitizer.sanitize(message)
        if (clean.isBlank()) return
        Log.i(TAG, clean)
        snapshots.add(DiagnosticEntry(CLOCK.format(Instant.now()), clean))
    }

    fun exportText(): String = snapshots.exportText()

    fun clear() {
        snapshots.clear()
        lastTunDump = ""
        lastStartedConfig = ""
    }

    companion object {
        const val TAG = "Layer"
        private val CLOCK: DateTimeFormatter =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    }
}
