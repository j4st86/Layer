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
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale("ru"))
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
        _entries.update { current -> (current + entry).takeLast(MAX_ENTRIES) }
    }

    fun exportText(): String {
        return entries.value.joinToString("\n") { "${it.time}  ${it.message}" }
    }

    fun clear() {
        _entries.value = emptyList()
        lastTunDump = ""
        lastStartedConfig = ""
    }

    companion object {
        const val TAG = "Layer"
        private const val MAX_ENTRIES = 4000
    }
}
