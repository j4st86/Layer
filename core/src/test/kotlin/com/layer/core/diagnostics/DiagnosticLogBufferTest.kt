package com.layer.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLogBufferTest {
    @Test
    fun dropsOldestWhenOverEntryCap() {
        val buffer = DiagnosticLogBuffer(maxEntries = 3, maxBytes = 1_000_000)
        buffer.add(line("a"))
        buffer.add(line("b"))
        buffer.add(line("c"))
        buffer.add(line("d"))
        assertEquals(listOf("b", "c", "d"), buffer.snapshot().map { it.message })
        assertEquals(
            buffer.snapshot().sumOf { DiagnosticLogBuffer.exportedBytes(it) },
            buffer.storedBytes,
        )
    }

    @Test
    fun dropsOldestWhenOverByteCap() {
        val buffer = DiagnosticLogBuffer(maxEntries = 50, maxBytes = 80)
        repeat(20) { index -> buffer.add(line("m$index")) }
        assertTrue(buffer.storedBytes <= 80)
        assertTrue(buffer.size in 1..20)
        assertEquals("m19", buffer.snapshot().last().message)
        assertEquals(
            buffer.snapshot().sumOf { DiagnosticLogBuffer.exportedBytes(it) },
            buffer.storedBytes,
        )
    }

    @Test
    fun keepsTheLastLineEvenIfItExceedsByteCap() {
        val buffer = DiagnosticLogBuffer(maxEntries = 10, maxBytes = 20)
        buffer.add(line("x".repeat(200)))
        assertEquals(1, buffer.size)
        assertTrue(buffer.storedBytes > 20)
    }

    @Test
    fun exportMatchesSnapshotAndClears() {
        val buffer = DiagnosticLogBuffer()
        buffer.add(DiagnosticEntry("12:00:00.000", "one"))
        buffer.add(DiagnosticEntry("12:00:01.000", "two"))
        assertEquals(
            "12:00:00.000  one\n12:00:01.000  two",
            buffer.exportText(),
        )
        buffer.clear()
        assertTrue(buffer.isEmpty())
        assertEquals(0, buffer.storedBytes)
        assertEquals("", buffer.exportText())
    }

    @Test
    fun countsUtf8BytesNotCharacters() {
        val ascii = DiagnosticLogBuffer.exportedBytes(line("aa"))
        val cyrillic = DiagnosticLogBuffer.exportedBytes(line("яя"))
        assertTrue(cyrillic > ascii)
        assertEquals(
            "12:00:00.000  яя\n".toByteArray(Charsets.UTF_8).size,
            cyrillic,
        )
    }

    private fun line(message: String) = DiagnosticEntry("12:00:00.000", message)
}
