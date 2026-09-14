package com.layer.core.diagnostics

data class DiagnosticEntry(
    val time: String,
    val message: String,
)

/**
 * Ring of diagnostic lines. [add] is O(1) plus the few oldest rows it drops.
 * Byte accounting is incremental so an error storm does not rescan the buffer.
 */
class DiagnosticLogBuffer(
    private val maxEntries: Int = MAX_ENTRIES,
    private val maxBytes: Int = MAX_BYTES,
) {
    private val entries = ArrayDeque<DiagnosticEntry>()
    private var bytes = 0

    val size: Int get() = entries.size
    val storedBytes: Int get() = bytes
    fun isEmpty(): Boolean = entries.isEmpty()

    fun add(entry: DiagnosticEntry) {
        entries.addLast(entry)
        bytes += exportedBytes(entry)
        trim()
    }

    fun snapshot(): List<DiagnosticEntry> = entries.toList()

    fun exportText(): String = entries.joinToString("\n") { line ->
        "${line.time}  ${line.message}"
    }

    fun clear() {
        entries.clear()
        bytes = 0
    }

    private fun trim() {
        while (entries.size > 1 && (entries.size > maxEntries || bytes > maxBytes)) {
            bytes -= exportedBytes(entries.removeFirst())
        }
        if (bytes < 0) bytes = 0
    }

    companion object {
        const val MAX_ENTRIES = 15_000
        const val MAX_BYTES = 1_500_000

        fun exportedBytes(entry: DiagnosticEntry): Int {
            val line = "${entry.time}  ${entry.message}\n"
            return line.toByteArray(Charsets.UTF_8).size
        }
    }
}
