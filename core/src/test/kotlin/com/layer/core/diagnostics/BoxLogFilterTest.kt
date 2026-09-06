package com.layer.core.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoxLogFilterTest {
    @Test
    fun dropsTraceDebugAndTunNoise() {
        assertFalse(BoxLogFilter.keep("trace", "outbound/vless[proxy]: XtlsPadding 1448"))
        assertFalse(BoxLogFilter.keep("debug", "router: match[0] => sniff"))
        assertFalse(
            BoxLogFilter.keep(
                "info",
                "[4145204994 1ms] inbound/tun[tun-in]: inbound connection from 172.19.0.1:41260",
            ),
        )
        assertFalse(BoxLogFilter.keep("info", "router: found package name: org.telegram.messenger"))
    }

    @Test
    fun keepsVlessErrors() {
        assertTrue(
            BoxLogFilter.keep(
                "error",
                "connection: open connection to 149.154.167.50:443 using outbound/vless[proxy]: " +
                    "dial tcp 203.0.113.1:443: operation was canceled",
            ),
        )
        assertTrue(
            BoxLogFilter.keep(
                "info",
                "outbound/vless[proxy]: outbound connection to 149.154.167.50:443",
            ),
        )
    }

    @Test
    fun rateLimiterHidesRepeatsThenFlushes() {
        val limiter = BoxLogRateLimiter(windowMs = 8_000L, burst = 3)
        val fp = BoxLogFilter.fingerprint(
            "error",
            "[7612] [4133356062 10ms] connection: open connection to 149.154.167.41:443 " +
                "using outbound/vless[proxy]: dial tcp 203.0.113.1:443: operation was canceled",
        )
        val same = BoxLogFilter.fingerprint(
            "error",
            "[7612] [1795397205 10ms] connection: open connection to 149.154.167.51:443 " +
                "using outbound/vless[proxy]: dial tcp 203.0.113.1:443: operation was canceled",
        )
        assertEquals(fp, same)
        assertEquals(BoxLogRateLimiter.Decision.Emit, limiter.allow(fp, 0L))
        assertEquals(BoxLogRateLimiter.Decision.Emit, limiter.allow(fp, 10L))
        assertEquals(BoxLogRateLimiter.Decision.Emit, limiter.allow(fp, 20L))
        assertEquals(BoxLogRateLimiter.Decision.Drop, limiter.allow(fp, 30L))
        assertEquals(BoxLogRateLimiter.Decision.Drop, limiter.allow(fp, 40L))
        val flushed = limiter.allow(fp, 8_100L)
        assertEquals(BoxLogRateLimiter.Decision.EmitHidden(2), flushed)
    }
}
