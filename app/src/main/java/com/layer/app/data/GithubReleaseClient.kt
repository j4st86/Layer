package com.layer.app.data

import com.layer.core.i18n.copy
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

class GithubReleaseClient(
    private val ownerRepo: String = "j4st86/Layer",
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun fetchLatest(): Result<GithubRelease> {
        val uri = URI("https://api.github.com/repos/$ownerRepo/releases/latest")
        return runCatching {
            val connection = (uri.toURL().openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Layer-Android")
            }
            try {
                val code = connection.responseCode
                val stream = if (code in 200..299) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                if (code == 404) {
                    error(copy("No GitHub release found.", "На GitHub ещё нет релиза."))
                }
                if (code !in 200..299) {
                    error(copy("GitHub unavailable (HTTP $code).", "GitHub недоступен (HTTP $code)."))
                }
                val dto = json.decodeFromString<GithubReleaseDto>(body)
                val apk = dto.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                GithubRelease(
                    tag = dto.tagName,
                    pageUrl = dto.htmlUrl,
                    apkUrl = apk?.browserDownloadUrl,
                )
            } finally {
                connection.disconnect()
            }
        }
    }

    @Serializable
    private data class GithubReleaseDto(
        @SerialName("tag_name") val tagName: String,
        @SerialName("html_url") val htmlUrl: String,
        val assets: List<GithubAssetDto> = emptyList(),
    )

    @Serializable
    private data class GithubAssetDto(
        val name: String = "",
        @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    )
}

data class GithubRelease(
    val tag: String,
    val pageUrl: String,
    val apkUrl: String?,
)
