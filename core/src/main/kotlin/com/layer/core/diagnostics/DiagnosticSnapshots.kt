package com.layer.core.diagnostics

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Thread-safe diagnostic ring plus a UI flow: first snapshot immediately,
 * then at most one emission per [intervalMs] while logs keep arriving.
 * A revision that lands during the wait is folded into that snapshot and
 * is not emitted again.
 */
class DiagnosticSnapshots(
    private val intervalMs: Long = SNAPSHOT_INTERVAL_MS,
) {
    private val lock = Any()
    private val buffer = DiagnosticLogBuffer()
    private val revision = MutableStateFlow(0)

    val entries: Flow<List<DiagnosticEntry>> = flow {
        var lastEmittedRev = -1
        var lastSnapshot: List<DiagnosticEntry>? = null
        val first = synchronized(lock) { buffer.snapshot() to revision.value }
        lastEmittedRev = first.second
        lastSnapshot = first.first
        emit(first.first)
        revision.collect { rev ->
            if (rev == lastEmittedRev) return@collect
            delay(intervalMs)
            val next = synchronized(lock) { buffer.snapshot() to revision.value }
            lastEmittedRev = next.second
            if (next.first != lastSnapshot) {
                lastSnapshot = next.first
                emit(next.first)
            }
        }
    }

    fun add(entry: DiagnosticEntry) {
        synchronized(lock) {
            buffer.add(entry)
            revision.value += 1
        }
    }

    fun snapshot(): List<DiagnosticEntry> = synchronized(lock) { buffer.snapshot() }

    fun exportText(): String = synchronized(lock) { buffer.exportText() }

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            revision.value += 1
        }
    }

    companion object {
        const val SNAPSHOT_INTERVAL_MS = 250L
    }
}
