package com.layer.app.diagnostics

import android.util.Log
import com.layer.core.diagnostics.LogSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticEntry(
    val time: String,
    val message: String,
)

class DiagnosticLog {
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val _entries = MutableStateFlow<List<DiagnosticEntry>>(emptyList())
    val entries: StateFlow<List<DiagnosticEntry>> = _entries.asStateFlow()

    @Volatile
    var lastTunDump: String = ""

    @Volatile
    var lastStartedConfig: String = ""

    fun append(message: String) {
        val clean = LogSanitizer.sanitize(message)
        if (clean.isBlank()) return
        Log.i(TAG, clean)
        val entry = DiagnosticEntry(formatter.format(Date()), clean)
        _entries.update { current -> trimOldest(current + entry) }
    }

    fun exportText(): String {
        return entries.value.joinToString("\n") { "${it.time}  ${it.message}" }
    }

    fun clear() {
        _entries.value = emptyList()
        lastTunDump = ""
        lastStartedConfig = ""
    }

    private fun trimOldest(entries: List<DiagnosticEntry>): List<DiagnosticEntry> {
        if (entries.size <= MAX_ENTRIES && exportedBytes(entries) <= MAX_BYTES) {
            return entries
        }
        var drop = 0
        var bytes = exportedBytes(entries)
        val last = entries.lastIndex
        while (drop < last && (entries.size - drop > MAX_ENTRIES || bytes > MAX_BYTES)) {
            bytes -= exportedBytes(entries[drop])
            drop++
        }
        return if (drop == 0) entries else entries.subList(drop, entries.size).toList()
    }

    companion object {
        const val TAG = "Layer"
        private const val MAX_ENTRIES = 15_000
        private const val MAX_BYTES = 1_500_000

        private fun exportedBytes(entry: DiagnosticEntry): Int {
            // "HH:mm:ss.SSS  message\n" in the report. ASCII logs are 1 byte/char.
            return entry.time.length + 2 + entry.message.length + 1
        }

        private fun exportedBytes(entries: List<DiagnosticEntry>): Int {
            var total = 0
            for (entry in entries) total += exportedBytes(entry)
            return total
        }
    }
}
