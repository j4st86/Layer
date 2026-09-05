package com.layer.core.config

/**
 * VLESS share-link `type=` values and which XTLS flow is legal with them.
 * Vision is TCP-only. Applying it to xhttp/ws/grpc makes Reality fall through
 * to dest; the dest then speaks HTTP and Vision logs `unknown version: 72` ('H').
 */
object VlessTransport {
    const val TCP = "tcp"
    const val WS = "ws"
    const val GRPC = "grpc"
    const val HTTP = "http"
    const val HTTPUPGRADE = "httpupgrade"
    const val XHTTP = "xhttp"

    fun normalize(raw: String): String {
        return when (raw.trim().lowercase()) {
            "", "tcp" -> TCP
            "ws", "websocket" -> WS
            "grpc" -> GRPC
            "http", "h2" -> HTTP
            "httpupgrade" -> HTTPUPGRADE
            "xhttp", "splithttp" -> XHTTP
            else -> raw.trim().lowercase()
        }
    }

    fun isTcp(network: String): Boolean = normalize(network) == TCP

    fun isXhttp(network: String): Boolean = normalize(network) == XHTTP

    fun isSupported(network: String): Boolean {
        return normalize(network) in setOf(TCP, WS, GRPC, HTTP, HTTPUPGRADE, XHTTP)
    }

    /** Vision (and any other flow) is only valid on raw TCP. */
    fun effectiveFlow(network: String, flow: String): String {
        if (!isTcp(network)) return ""
        return flow.trim()
    }
}
