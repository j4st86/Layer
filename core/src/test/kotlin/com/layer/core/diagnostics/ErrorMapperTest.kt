package com.layer.core.diagnostics

import com.layer.core.i18n.UiLanguage
import com.layer.core.i18n.UiLanguageState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class ErrorMapperTest {
    @Before
    fun useRussianCopy() {
        UiLanguageState.current = UiLanguage.RUSSIAN
    }
    @Test
    fun unknownRealityFieldIsConfigNotTls() {
        val mapped = ErrorMapper.map(
            """decode config: outbounds[0].tls.reality.spider_x: json: unknown field "spider_x"""",
        )
        assertEquals(DiagnosticKind.CONFIG, mapped.kind)
        assertEquals("Ошибка конфигурации", mapped.title)
    }

    @Test
    fun handshakeFailureIsTls() {
        val mapped = ErrorMapper.map("tls handshake failed")
        assertEquals(DiagnosticKind.TLS, mapped.kind)
    }

    @Test
    fun unknownVersionIsWrongTransport() {
        val mapped = ErrorMapper.map("connection download closed: unknown version: 72")
        assertEquals(DiagnosticKind.CONFIG, mapped.kind)
        assertEquals("Неверный транспорт VLESS", mapped.title)
    }

    @Test
    fun realityVerificationIsRealityError() {
        val mapped = ErrorMapper.map("dns: exchange failed for youtube.com. IN A: reality verification failed")
        assertEquals("Ошибка Reality", mapped.title)
        assertEquals(DiagnosticKind.TLS, mapped.kind)
    }

    @Test
    fun englishTitlesForSameKinds() {
        UiLanguageState.current = UiLanguage.ENGLISH
        assertEquals("Configuration error", ErrorMapper.map("""json: unknown field "spider_x"""").title)
        assertEquals("Wrong VLESS transport", ErrorMapper.map("unknown version: 72").title)
        assertEquals("Reality error", ErrorMapper.map("reality verification failed").title)
    }
}
