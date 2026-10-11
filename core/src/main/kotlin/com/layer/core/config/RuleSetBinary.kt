package com.layer.core.config

import java.io.InputStream
import java.io.OutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream

/**
 * Writes a sing-box binary rule-set (SRS version 5) for `domain_suffix` rules.
 *
 * sing-box 1.14 loads `format: binary` without parsing the host list again.
 * The domain item is the succinct set from sagernet/sing `domain.NewMatcher`
 * (root-label suffixes, not the version-1 legacy encoding). An allow list
 * becomes the same logical AND that the source JSON used: inverted allow
 * suffixes, then block suffixes.
 */
object RuleSetBinary {
    const val VERSION: Int = 5

    private const val ROOT_LABEL: Byte = '\n'.code.toByte()
    private const val PREFIX_LABEL: Byte = '\r'.code.toByte()
    private const val RULE_DEFAULT: Int = 0
    private const val RULE_LOGICAL: Int = 1
    private const val LOGICAL_AND: Int = 0
    private const val ITEM_DOMAIN: Int = 2
    private const val ITEM_FINAL: Int = 0xFF

    fun write(out: OutputStream, block: List<String>, allow: List<String>) {
        require(block.isNotEmpty()) { "ad block rule-set has no domains" }
        out.write(byteArrayOf(0x53, 0x52, 0x53))
        out.write(VERSION)
        val deflater = Deflater(Deflater.BEST_COMPRESSION, false)
        val zipped = DeflaterOutputStream(out, deflater)
        try {
            writeBody(zipped, block, allow)
            zipped.finish()
        } finally {
            deflater.end()
        }
    }

    /**
     * Reads the complete zlib member so its Adler-32 checksum is verified.
     * A header-only check can accept a truncated or damaged file and make
     * every subsequent VPN start fail before the list can be regenerated.
     */
    fun isValid(input: InputStream): Boolean = runCatching {
        val header = ByteArray(4)
        if (input.read(header) != header.size ||
            header[0] != 0x53.toByte() ||
            header[1] != 0x52.toByte() ||
            header[2] != 0x53.toByte() ||
            header[3] != VERSION.toByte()
        ) {
            return@runCatching false
        }
        var inflatedBytes = 0L
        val buffer = ByteArray(32 * 1024)
        InflaterInputStream(input).use { inflated ->
            while (true) {
                val read = inflated.read(buffer)
                if (read < 0) break
                inflatedBytes += read
            }
        }
        inflatedBytes >= MIN_BODY_BYTES
    }.getOrDefault(false)

    private fun writeBody(out: OutputStream, block: List<String>, allow: List<String>) {
        if (allow.isEmpty()) {
            out.writeUvarint(1)
            writeSuffixRule(out, block, invert = false)
            return
        }
        out.writeUvarint(1)
        out.write(RULE_LOGICAL)
        out.write(LOGICAL_AND)
        out.writeUvarint(2)
        writeSuffixRule(out, allow, invert = true)
        writeSuffixRule(out, block, invert = false)
        out.write(0)
    }

    private fun writeSuffixRule(out: OutputStream, domains: List<String>, invert: Boolean) {
        out.write(RULE_DEFAULT)
        out.write(ITEM_DOMAIN)
        writeMatcher(out, suffixKeys(domains))
        out.write(ITEM_FINAL)
        out.write(if (invert) 1 else 0)
    }

    private fun suffixKeys(domains: List<String>): List<String> {
        val keys = ArrayList<String>(domains.size)
        for (domain in domains) {
            if (domain.isEmpty()) continue
            keys.add(domain)
        }
        require(keys.isNotEmpty()) { "ad block rule-set has no domains" }
        keys.sortWith(::compareReversed)
        var unique = 1
        for (index in 1 until keys.size) {
            if (keys[index] != keys[unique - 1]) {
                keys[unique++] = keys[index]
            }
        }
        if (unique < keys.size) keys.subList(unique, keys.size).clear()
        return keys
    }

    /**
     * Succinct set from sagernet/sing `common/domain/set.go` `newSuccinctSet`.
     * Ranks and selects are rebuilt by the reader, so only the three arrays
     * that [Write] emits are stored.
     */
    private fun writeMatcher(out: OutputStream, keys: List<String>) {
        val leaves = BitWords()
        val labelBitmap = BitWords()
        val labels = ByteArrayOutput()
        var labelIndex = 0
        val queue = NodeQueue()
        queue.add(0, keys.size, 0)
        var node = 0
        while (node < queue.size) {
            var start = queue.start(node)
            val end = queue.end(node)
            val column = queue.column(node)
            if (column == keyLength(keys[start])) {
                start++
                leaves.set(node)
            }
            var cursor = start
            while (cursor < end) {
                val from = cursor
                val label = keyByteAt(keys[from], column)
                cursor++
                while (cursor < end && keyByteAt(keys[cursor], column) == label) cursor++
                queue.add(from, cursor, column + 1)
                labels.write(label)
                labelIndex++
            }
            labelBitmap.set(labelIndex)
            labelIndex++
            node++
        }
        out.write(0)
        leaves.writeTo(out)
        labelBitmap.writeTo(out)
        out.writeUvarint(labels.size.toLong())
        labels.writeTo(out)
    }

    private fun writeUint64(out: OutputStream, word: Long) {
        out.write((word ushr 56).toInt())
        out.write((word ushr 48).toInt())
        out.write((word ushr 40).toInt())
        out.write((word ushr 32).toInt())
        out.write((word ushr 24).toInt())
        out.write((word ushr 16).toInt())
        out.write((word ushr 8).toInt())
        out.write(word.toInt())
    }

    private fun compareReversed(left: String, right: String): Int {
        val length = minOf(keyLength(left), keyLength(right))
        for (index in 0 until length) {
            val diff =
                (keyByteAt(left, index).toInt() and 0xFF) -
                    (keyByteAt(right, index).toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return keyLength(left) - keyLength(right)
    }

    private fun keyLength(domain: String): Int = domain.length + 1

    private fun keyByteAt(domain: String, index: Int): Byte {
        if (index == domain.length) {
            return if (domain.startsWith('.')) PREFIX_LABEL else ROOT_LABEL
        }
        // DnsHostlistFilter normalizes IDNs to ASCII/punycode before this
        // writer runs, so one UTF-16 code unit is one matcher byte.
        return domain[domain.length - 1 - index].code.toByte()
    }

    private class BitWords {
        private val chunks = ArrayList<LongArray>()
        private var wordCount = 0

        fun set(index: Int) {
            val word = index ushr 6
            while (word >= wordCount) {
                if (wordCount % WORD_CHUNK_SIZE == 0) chunks += LongArray(WORD_CHUNK_SIZE)
                wordCount++
            }
            val chunk = word / WORD_CHUNK_SIZE
            val offset = word % WORD_CHUNK_SIZE
            chunks[chunk][offset] = chunks[chunk][offset] or (1L shl (index and 63))
        }

        fun writeTo(out: OutputStream) {
            out.writeUvarint(wordCount.toLong())
            var remaining = wordCount
            for (chunk in chunks) {
                val count = minOf(remaining, chunk.size)
                for (index in 0 until count) writeUint64(out, chunk[index])
                remaining -= count
            }
        }
    }

    /**
     * Breadth-first construction needs one range per trie node. Primitive
     * chunks avoid retaining ~2.4 million Range objects for Multi PRO and
     * avoid large array-copy spikes when the queue grows.
     */
    private class NodeQueue {
        private val starts = ArrayList<IntArray>()
        private val ends = ArrayList<IntArray>()
        private val columns = ArrayList<ByteArray>()
        var size: Int = 0
            private set

        fun add(start: Int, end: Int, column: Int) {
            require(column <= 0xFF) { "domain is too long" }
            val chunk = size / NODE_CHUNK_SIZE
            val offset = size % NODE_CHUNK_SIZE
            if (chunk == starts.size) {
                starts += IntArray(NODE_CHUNK_SIZE)
                ends += IntArray(NODE_CHUNK_SIZE)
                columns += ByteArray(NODE_CHUNK_SIZE)
            }
            starts[chunk][offset] = start
            ends[chunk][offset] = end
            columns[chunk][offset] = column.toByte()
            size++
        }

        fun start(index: Int): Int = starts[index / NODE_CHUNK_SIZE][index % NODE_CHUNK_SIZE]
        fun end(index: Int): Int = ends[index / NODE_CHUNK_SIZE][index % NODE_CHUNK_SIZE]
        fun column(index: Int): Int =
            columns[index / NODE_CHUNK_SIZE][index % NODE_CHUNK_SIZE].toInt() and 0xFF
    }

    private class ByteArrayOutput {
        private val chunks = ArrayList<ByteArray>()
        var size: Int = 0
            private set

        fun write(value: Byte) {
            val chunk = size / LABEL_CHUNK_SIZE
            val offset = size % LABEL_CHUNK_SIZE
            if (chunk == chunks.size) chunks += ByteArray(LABEL_CHUNK_SIZE)
            chunks[chunk][offset] = value
            size++
        }

        fun writeTo(out: OutputStream) {
            var remaining = size
            for (chunk in chunks) {
                val count = minOf(remaining, chunk.size)
                out.write(chunk, 0, count)
                remaining -= count
            }
        }
    }

    private const val MIN_BODY_BYTES = 16L
    private const val WORD_CHUNK_SIZE = 1024
    private const val NODE_CHUNK_SIZE = 64 * 1024
    private const val LABEL_CHUNK_SIZE = 64 * 1024
}

private fun OutputStream.writeUvarint(value: Long) {
    var rest = value
    while (rest >= 0x80L) {
        write(((rest and 0x7F) or 0x80).toInt())
        rest = rest ushr 7
    }
    write(rest.toInt())
}
