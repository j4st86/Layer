package com.layer.core.diagnostics

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticSnapshotsTest {
    @Test
    fun firstSnapshotIsImmediate() = runTest {
        val log = DiagnosticSnapshots(intervalMs = 250)
        log.add(line("a"))
        val collected = mutableListOf<List<String>>()
        backgroundScope.launch {
            log.entries.collect { collected += it.map { entry -> entry.message } }
        }
        runCurrent()
        assertEquals(listOf(listOf("a")), collected)
    }

    @Test
    fun burstIsCoalescedWithoutDuplicate() = runTest {
        val log = DiagnosticSnapshots(intervalMs = 250)
        val collected = mutableListOf<List<String>>()
        backgroundScope.launch {
            log.entries.collect { collected += it.map { entry -> entry.message } }
        }
        runCurrent()
        assertEquals(listOf(emptyList<String>()), collected)

        log.add(line("a"))
        log.add(line("b"))
        log.add(line("c"))
        runCurrent()
        assertEquals(1, collected.size)

        advanceTimeBy(249)
        runCurrent()
        assertEquals(1, collected.size)

        advanceTimeBy(1)
        runCurrent()
        assertEquals(listOf(emptyList(), listOf("a", "b", "c")), collected)

        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(2, collected.size)

        log.add(line("d"))
        advanceTimeBy(250)
        runCurrent()
        assertEquals(listOf("a", "b", "c", "d"), collected.last())
        assertEquals(3, collected.size)
    }

    @Test
    fun clearReachesTheCollector() = runTest {
        val log = DiagnosticSnapshots(intervalMs = 250)
        log.add(line("a"))
        val collected = mutableListOf<List<String>>()
        backgroundScope.launch {
            log.entries.collect { collected += it.map { entry -> entry.message } }
        }
        runCurrent()
        assertEquals(listOf(listOf("a")), collected)

        log.clear()
        advanceTimeBy(250)
        runCurrent()
        assertEquals(emptyList<String>(), collected.last())
    }

    private fun line(message: String) = DiagnosticEntry("12:00:00.000", message)
}
