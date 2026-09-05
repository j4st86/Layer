package com.layer.app.data

import com.layer.core.i18n.copy
import java.net.HttpURLConnection
import java.net.URI

class SubscriptionClient {
    fun fetch(url: String): Result<String> {
        val uri = runCatching { URI(url.trim()) }.getOrElse {
            return Result.failure(
                IllegalArgumentException(copy("Invalid subscription link.", "Некорректная ссылка подписки.")),
            )
        }
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return Result.failure(
                IllegalArgumentException(copy("The subscription must use HTTPS.", "Подписка должна быть по HTTPS.")),
            )
        }
        return runCatching {
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "v2rayNG/1.10.23")
                setRequestProperty("Accept", "text/plain,*/*")
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    error(copy("Subscription unavailable (HTTP $code).", "Подписка недоступна (HTTP $code)."))
                }
                if (body.isBlank()) {
                    error(copy("The subscription is empty.", "Подписка пустая."))
                }
                body
            } finally {
                connection.disconnect()
            }
        }
    }
}
