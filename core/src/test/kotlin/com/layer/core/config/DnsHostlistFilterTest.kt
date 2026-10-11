package com.layer.core.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.StringWriter
import java.util.zip.InflaterInputStream

class DnsHostlistFilterTest {
    @Test
    fun parsesSuffixExceptionsAndSkipsNoise() {
        val parsed = DnsHostlistFilter.parse(
            """
            ! Title: test
            ||ads.example.com^
            ||194.63.143.96^
            @@||ad.10010.com^
            ||adsrvmedia.adk2.co^${'$'}important
            ||tn.porngo.xxx^${'$'}badfilter
            .bbelements.com^
            /^88\.208\.22\.[1234]:/
            ||fxhpaoxqyajvmdg.
            """.trimIndent(),
        )
        assertEquals(
            listOf("ads.example.com", "adsrvmedia.adk2.co", "bbelements.com"),
            parsed.block,
        )
        assertEquals(listOf("ad.10010.com"), parsed.allow)
        assertTrue(parsed.skipped >= 4)
        assertTrue(parsed.isUsable)
    }

    @Test
    fun exceptionIsRemovedFromBlockList() {
        val parsed = DnsHostlistFilter.parse(
            """
            ||ad.10010.com^
            @@||ad.10010.com^
            ||tracker.example.com^
            """.trimIndent(),
        )
        assertEquals(listOf("tracker.example.com"), parsed.block)
        assertEquals(listOf("ad.10010.com"), parsed.allow)
    }

    @Test
    fun writesLogicalSourceRuleSetWhenAllowExists() {
        val parsed = DnsHostlistFilter.parse(
            """
            ||ads.example.com^
            @@||safe.example.com^
            """.trimIndent(),
        )
        val json = StringWriter().also { parsed.writeSourceJson(it) }.toString()
        val root = Json.parseToJsonElement(json).jsonObject
        assertEquals(3, root["version"]!!.jsonPrimitive.content.toInt())
        val logical = root["rules"]!!.jsonArray.first().jsonObject
        assertEquals("logical", logical["type"]!!.jsonPrimitive.content)
        assertEquals("and", logical["mode"]!!.jsonPrimitive.content)
        val rules = logical["rules"]!!.jsonArray
        val allow = rules[0].jsonObject
        assertTrue(allow["invert"]!!.jsonPrimitive.boolean)
        assertEquals("safe.example.com", allow["domain_suffix"]!!.jsonArray.first().jsonPrimitive.content)
        val block = rules[1].jsonObject
        assertFalse(block.containsKey("invert"))
        assertEquals("ads.example.com", block["domain_suffix"]!!.jsonArray.first().jsonPrimitive.content)
    }

    @Test
    fun writesFlatRuleSetWithoutAllow() {
        val parsed = DnsHostlistFilter.parse("||ads.example.com^\n")
        val json = StringWriter().also { parsed.writeSourceJson(it) }.toString()
        val root = Json.parseToJsonElement(json).jsonObject
        val rule = root["rules"]!!.jsonArray.first().jsonObject
        assertFalse(rule.containsKey("type"))
        assertEquals("ads.example.com", rule["domain_suffix"]!!.jsonArray.first().jsonPrimitive.content)
    }

    @Test
    fun binaryRuleSetMatchesSingBox1141() {
        assertBinaryBody(
            DnsHostlistFilter.parse("||ads.example.com^\n||tracker.test^\n"),
            "010002000100000000240000000106b55555555555541d6d746f7363652e74652e6c7270656d6b6163786165722e74730a64610aff00",
        )
        assertBinaryBody(
            DnsHostlistFilter.parse(
                """
                ||ads.example.com^
                ||bbelements.com^
                @@||safe.example.com^
                """.trimIndent(),
            ),
            "010100020002000100000000000200000100000006aaaaaaaa116d6f632e656c706d6178652e656661730aff0100020001000000000c0000000100755555555554aa1b6d6f632e65736c74706e6d65616d7865656c2e6573626462610a0aff0000",
        )
        assertBinaryBody(
            DnsHostlistFilter.parse(
                """
                ||ads.example.com^
                ||track.example.com^
                ||example.com^
                ||a.b.test^
                ||b.b.test^
                ||other.net^
                """.trimIndent(),
            ),
            "010002000100000a015800000002375552aaaaaaaa9400000000006d55532b6d746f6573636e652e2e7465722e6c656270682e6d746162616f0a0a780a650a2e6b7363646161720a740aff00",
        )
    }

    @Test
    fun binaryValidationRejectsCorruptionAndTruncation() {
        val compiled = ByteArrayOutputStream().also {
            DnsHostlistFilter.parse("||ads.example.com^\n||tracker.test^\n").writeBinary(it)
        }.toByteArray()
        assertTrue(RuleSetBinary.isValid(ByteArrayInputStream(compiled)))

        val corrupted = compiled.copyOf()
        corrupted[corrupted.size / 2] = (corrupted[corrupted.size / 2].toInt() xor 0x5A).toByte()
        assertFalse(RuleSetBinary.isValid(ByteArrayInputStream(corrupted)))

        val truncated = compiled.copyOf(compiled.size - 5)
        assertFalse(RuleSetBinary.isValid(ByteArrayInputStream(truncated)))
    }

    @Test
    fun bundledFilterCompilesToBinaryRuleSet() {
        val file = bundledFilter() ?: return
        val parsed = file.bufferedReader().use { DnsHostlistFilter.parse(it) }
        val compiled = ByteArrayOutputStream().also { parsed.writeBinary(it) }.toByteArray()
        assertEquals(0x53, compiled[0].toInt() and 0xFF)
        assertEquals(0x52, compiled[1].toInt() and 0xFF)
        assertEquals(0x53, compiled[2].toInt() and 0xFF)
        assertEquals(RuleSetBinary.VERSION, compiled[3].toInt() and 0xFF)
        assertTrue(RuleSetBinary.isValid(ByteArrayInputStream(compiled)))
        assertTrue(compiled.size < file.length())
        val body = inflateRuleSet(compiled)
        assertEquals(1, body[0].toInt() and 0xFF)
        assertEquals(0, body[1].toInt() and 0xFF)
    }

    private fun assertBinaryBody(parsed: DnsHostlistFilterSet, expectedBody: String) {
        val compiled = ByteArrayOutputStream().also { parsed.writeBinary(it) }.toByteArray()
        assertEquals(RuleSetBinary.VERSION, compiled[3].toInt() and 0xFF)
        val body = inflateRuleSet(compiled)
        assertEquals(expectedBody, body.joinToString("") { "%02x".format(it) })
    }

    private fun inflateRuleSet(compiled: ByteArray): ByteArray {
        return InflaterInputStream(ByteArrayInputStream(compiled, 4, compiled.size - 4)).use { it.readBytes() }
    }

    private fun bundledFilter(): java.io.File? = listOf(
        java.io.File("app/src/main/assets/adblock/${AdBlockPolicy.fileName}"),
        java.io.File("../app/src/main/assets/adblock/${AdBlockPolicy.fileName}"),
    ).firstOrNull { it.isFile }

    @Test
    fun bundledFilterHasEnoughSuffixRules() {
        val file = bundledFilter() ?: return
        val parsed = file.bufferedReader().use { DnsHostlistFilter.parse(it) }
        assertTrue(parsed.block.size > 100_000)
        assertTrue(parsed.isUsable)
    }
}
