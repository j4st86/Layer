package com.layer.core.diagnostics

import com.layer.core.i18n.copy

enum class DiagnosticKind {
    SERVER_UNREACHABLE,
    TLS,
    INVALID_UUID,
    CONFIG,
    TUN,
    DNS,
    PERMISSION,
    UNKNOWN,
}

data class UserFacingError(
    val title: String,
    val details: String,
    val kind: DiagnosticKind,
)

object ErrorMapper {
    fun map(raw: String?): UserFacingError {
        val message = LogSanitizer.sanitize(raw).lowercase()
        return when {
            message.contains("missing vpn permission") || message.contains("not prepared") ->
                UserFacingError(
                    title = copy("VPN permission needed", "Нужно разрешение VPN"),
                    details = copy(
                        "Allow Layer to create a VPN connection in the Android system dialog.",
                        "Разрешите Layer создать VPN-подключение в системном окне Android.",
                    ),
                    kind = DiagnosticKind.PERMISSION,
                )
            message.contains("unknown transport") ->
                UserFacingError(
                    title = copy("Transport not supported", "Транспорт не поддерживается"),
                    details = copy(
                        "This build cannot use that VLESS transport. Update Layer.",
                        "Эта сборка не умеет такой VLESS-транспорт. Обновите Layer.",
                    ),
                    kind = DiagnosticKind.CONFIG,
                )
            message.contains("unknown field") || message.contains("decode config") ||
                message.contains("json: unknown") ->
                UserFacingError(
                    title = copy("Configuration error", "Ошибка конфигурации"),
                    details = copy(
                        "Could not apply the server settings. Try another server or refresh the subscription.",
                        "Не удалось применить настройки сервера. Попробуйте другой сервер или обновите подписку.",
                    ),
                    kind = DiagnosticKind.CONFIG,
                )
            message.contains("unknown version") ->
                UserFacingError(
                    title = copy("Wrong VLESS transport", "Неверный транспорт VLESS"),
                    details = copy(
                        "The server answered HTTP instead of Vision. An xhttp/ws link was saved as TCP+Vision — paste it again.",
                        "Сервер отвечает HTTP вместо Vision. Ссылка xhttp/ws была сохранена как TCP+Vision — вставьте её ещё раз.",
                    ),
                    kind = DiagnosticKind.CONFIG,
                )
            message.contains("reality verification") ->
                UserFacingError(
                    title = copy("Reality error", "Ошибка Reality"),
                    details = copy(
                        "The Reality server did not confirm the handshake. On Xray 26.7+ this is often a client version check: Happ works, sing-box does not. Update Layer or set Min Client Ver = 1.0.0 in the panel.",
                        "Сервер Reality не подтвердил рукопожатие. На Xray 26.7+ это часто из‑за проверки версии клиента: Happ проходит, а sing-box нет. Обновите Layer или в панели поставьте Min Client Ver = 1.0.0.",
                    ),
                    kind = DiagnosticKind.TLS,
                )
            message.contains("uuid") || message.contains("invalid user") || message.contains("auth") ->
                UserFacingError(
                    title = copy("Wrong UUID", "Неправильный UUID"),
                    details = copy(
                        "The server rejected the connection. Check the UUID in settings.",
                        "Сервер отклонил подключение. Проверьте UUID в настройках.",
                    ),
                    kind = DiagnosticKind.INVALID_UUID,
                )
            message.contains("tls") || message.contains("certificate") || message.contains("x509") ||
                message.contains("handshake") ->
                UserFacingError(
                    title = copy("TLS error", "Ошибка TLS"),
                    details = copy(
                        "Could not establish a secure connection to the VPN server.",
                        "Не удалось установить защищённое соединение с VPN-сервером.",
                    ),
                    kind = DiagnosticKind.TLS,
                )
            message.contains("dns") || message.contains("no such host") || message.contains("resolve") ->
                UserFacingError(
                    title = copy("DNS error", "Ошибка DNS"),
                    details = copy(
                        "Could not resolve the server or domain name.",
                        "Не удалось разрешить имя сервера или домена.",
                    ),
                    kind = DiagnosticKind.DNS,
                )
            message.contains("tun") || message.contains("establish") ->
                UserFacingError(
                    title = copy("TUN did not start", "TUN не запущен"),
                    details = copy(
                        "Android could not create the VPN interface. Disconnect and connect again.",
                        "Android не смог создать VPN-интерфейс. Попробуйте отключить и включить VPN снова.",
                    ),
                    kind = DiagnosticKind.TUN,
                )
            message.contains("parse") || message.contains("json") || message.contains("decode") ||
                message.contains("config") ->
                UserFacingError(
                    title = copy("Configuration error", "Ошибка конфигурации"),
                    details = copy(
                        "Could not apply routing settings. Reset the rules or check the server.",
                        "Не удалось применить настройки маршрутизации. Сбросьте правила или проверьте сервер.",
                    ),
                    kind = DiagnosticKind.CONFIG,
                )
            message.contains("timeout") || message.contains("refused") || message.contains("unreachable") ||
                message.contains("network is unreachable") || message.contains("failed to dial") ||
                message.contains("i/o") || message.contains("connect") ->
                UserFacingError(
                    title = copy("Could not connect to the VPN server.", "Не удалось подключиться к VPN-серверу."),
                    details = copy(
                        "The server is unreachable. Check the internet and the server address.",
                        "Сервер недоступен. Проверьте интернет и адрес сервера.",
                    ),
                    kind = DiagnosticKind.SERVER_UNREACHABLE,
                )
            else -> UserFacingError(
                title = copy("Could not connect to the VPN server.", "Не удалось подключиться к VPN-серверу."),
                details = if (message.isBlank()) {
                    copy(
                        "Unknown error. Open diagnostics for details.",
                        "Неизвестная ошибка. Откройте диагностику для подробностей.",
                    )
                } else {
                    LogSanitizer.sanitize(raw)
                },
                kind = DiagnosticKind.UNKNOWN,
            )
        }
    }
}
