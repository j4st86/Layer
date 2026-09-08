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
import java.io.StringWriter

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
    fun bundledFilterHasEnoughSuffixRules() {
        val file = listOf(
            java.io.File("app/src/main/assets/adblock/${AdBlockPolicy.fileName}"),
            java.io.File("../app/src/main/assets/adblock/${AdBlockPolicy.fileName}"),
        ).firstOrNull { it.isFile } ?: return
        val parsed = file.bufferedReader().use { DnsHostlistFilter.parse(it) }
        assertTrue(parsed.block.size > 100_000)
        assertTrue(parsed.isUsable)
    }
}
