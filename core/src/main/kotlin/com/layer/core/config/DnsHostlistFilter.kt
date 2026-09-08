package com.layer.core.config

import com.layer.core.routing.HostnameNormalizer
import java.io.File
import java.io.Reader
import java.io.Writer

/**
 * Turns an ABP-style DNS hostlist (`||domain^`, `@@||domain^`) into a sing-box
 * source rule-set. The text list is not a legal sing-box source format, so
 * Layer writes `domain_suffix` JSON.
 */
data class DnsHostlistFilterSet(
    val block: List<String>,
    val allow: List<String>,
    val skipped: Int,
) {
    val isUsable: Boolean get() = block.size >= MIN_BLOCK_RULES

    fun writeSourceJson(destination: File) {
        destination.parentFile?.mkdirs()
        destination.bufferedWriter().use { writeSourceJson(it) }
    }

    fun writeSourceJson(out: Writer) {
        out.append("""{"version":3,"rules":[""")
        if (allow.isNotEmpty()) {
            out.append("""{"type":"logical","mode":"and","rules":[""")
            writeDomainSuffixRule(out, allow, invert = true)
            out.append(',')
            writeDomainSuffixRule(out, block, invert = false)
            out.append("]}")
        } else {
            writeDomainSuffixRule(out, block, invert = false)
        }
        out.append("]}")
    }

    private fun writeDomainSuffixRule(out: Writer, domains: List<String>, invert: Boolean) {
        out.append("""{"domain_suffix":[""")
        domains.forEachIndexed { index, domain ->
            if (index > 0) out.append(',')
            out.append('"').append(escapeJson(domain)).append('"')
        }
        out.append(']')
        if (invert) out.append(""","invert":true""")
        out.append('}')
    }

    private fun escapeJson(value: String): String {
        if (value.none { it == '\\' || it == '"' }) return value
        return buildString(value.length + 8) {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    else -> append(ch)
                }
            }
        }
    }

    companion object {
        const val MIN_BLOCK_RULES = 1
    }
}

object DnsHostlistFilter {
    private val ipv4Regex = Regex("""^(\d{1,3}\.){3}\d{1,3}$""")

    fun parse(text: String): DnsHostlistFilterSet = parse(text.reader())

    fun parse(reader: Reader): DnsHostlistFilterSet {
        val block = LinkedHashSet<String>()
        val allow = LinkedHashSet<String>()
        var skipped = 0
        reader.buffered().forEachLine { raw ->
            when (val parsed = parseLine(raw)) {
                is Line.Skip -> skipped++
                is Line.Block -> block += parsed.domain
                is Line.Allow -> allow += parsed.domain
            }
        }
        block.removeAll(allow)
        return DnsHostlistFilterSet(
            block = block.toList(),
            allow = allow.toList(),
            skipped = skipped,
        )
    }

    private fun parseLine(raw: String): Line {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("!") || line.startsWith("#")) {
            return Line.Skip
        }
        val exception = line.startsWith("@@")
        if (exception) line = line.removePrefix("@@")
        if (line.startsWith("/") && line.endsWith("/")) return Line.Skip
        if ('$' in line) {
            val modifiers = line.substringAfter('$').split(',')
            if (modifiers.any { !supportedModifier(it) }) return Line.Skip
            line = line.substringBefore('$')
        }
        if (line.startsWith("||")) {
            line = line.removePrefix("||")
        } else if (line.startsWith("|")) {
            line = line.removePrefix("|")
        } else if (line.startsWith(".")) {
            line = line.removePrefix(".")
        }
        if (line.endsWith("^")) line = line.removeSuffix("^")
        if (line.endsWith("|")) line = line.removeSuffix("|")
        line = line.trim('.')
        if (line.isEmpty() || '/' in line || ':' in line || '*' in line) return Line.Skip
        if (ipv4Regex.matches(line)) return Line.Skip
        val normalized = HostnameNormalizer.normalize(line)
        if (!normalized.isValid || ipv4Regex.matches(normalized.domain)) return Line.Skip
        return if (exception) Line.Allow(normalized.domain) else Line.Block(normalized.domain)
    }

    private fun supportedModifier(raw: String): Boolean {
        val name = raw.substringBefore('=').trim().lowercase()
        return name == "important" || name == "dnsrewrite"
    }

    private sealed interface Line {
        data object Skip : Line
        data class Block(val domain: String) : Line
        data class Allow(val domain: String) : Line
    }
}
