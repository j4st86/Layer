package com.layer.app.data

import android.content.Context
import android.net.Network
import com.layer.core.config.AdBlockPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class AdBlockFetch(
    val path: String? = null,
    val bytes: Long = 0,
    val fromCache: Boolean = false,
    val fromAssets: Boolean = false,
    val version: String? = null,
    val error: String? = null,
) {
    val isPresent: Boolean get() = path != null
}

class AdBlockDownloader(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "adblock")
    private val file get() = File(dir, AdBlockPolicy.fileName)

    fun existingFile(): File? {
        val local = file
        return if (isUsable(local)) local else null
    }

    fun debugSnapshot(): String {
        val local = existingFile() ?: return "missing"
        val ageMs = (System.currentTimeMillis() - local.lastModified()).coerceAtLeast(0L)
        val ageHours = ageMs / (60L * 60L * 1000L)
        val version = readVersion(local)
        return buildString {
            append("${local.length()} bytes")
            if (version != null) append(" version=$version")
            append(" age=${ageHours}h")
        }
    }

    suspend fun ensureCopy(network: Network?, freshnessMs: Long): AdBlockFetch =
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val local = file
            if (isUsable(local) && System.currentTimeMillis() - local.lastModified() < freshnessMs) {
                return@withContext AdBlockFetch(
                    path = local.absolutePath,
                    bytes = local.length(),
                    fromCache = true,
                    version = readVersion(local),
                )
            }
            val error = download(AdBlockPolicy.listUrl, local, network)
            if (error == null && isUsable(local)) {
                return@withContext AdBlockFetch(
                    path = local.absolutePath,
                    bytes = local.length(),
                    fromCache = false,
                    version = readVersion(local),
                )
            }
            if (isUsable(local)) {
                return@withContext AdBlockFetch(
                    path = local.absolutePath,
                    bytes = local.length(),
                    fromCache = true,
                    version = readVersion(local),
                    error = error,
                )
            }
            if (copyFromAssets(local)) {
                return@withContext AdBlockFetch(
                    path = local.absolutePath,
                    bytes = local.length(),
                    fromAssets = true,
                    version = readVersion(local) ?: AdBlockPolicy.BUNDLED_VERSION,
                    error = error,
                )
            }
            AdBlockFetch(error = error ?: "missing")
        }

    private fun copyFromAssets(destination: File): Boolean {
        val assetPath = "${AdBlockPolicy.ASSET_DIR}/${AdBlockPolicy.fileName}"
        return runCatching {
            app.assets.open(assetPath).use { input ->
                val tmp = File(destination.parentFile, destination.name + ".tmp")
                tmp.outputStream().use { output -> input.copyTo(output) }
                if (tmp.length() < MIN_BYTES) {
                    tmp.delete()
                    return false
                }
                if (destination.exists()) destination.delete()
                tmp.renameTo(destination)
            }
        }.getOrDefault(false)
    }

    private fun download(url: String, destination: File, network: Network?): String? {
        val connection = runCatching {
            val target = URL(url)
            val opened = if (network != null) network.openConnection(target) else target.openConnection()
            (opened as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 60_000
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept", "text/plain,*/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
        }.getOrElse { error ->
            return describe(error)
        }
        return try {
            val code = connection.responseCode
            val type = connection.contentType.orEmpty()
            if (code !in 200..299) return "HTTP $code"
            if (type.contains("text/html", ignoreCase = true)) return "HTML instead of filter"
            val tmp = File(destination.parentFile, destination.name + ".tmp")
            connection.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() < MIN_BYTES) {
                tmp.delete()
                return "too small"
            }
            if (destination.exists()) destination.delete()
            if (!tmp.renameTo(destination)) return "save failed"
            null
        } catch (error: Exception) {
            describe(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun readVersion(local: File): String? {
        return runCatching {
            local.bufferedReader().use { reader ->
                repeat(12) {
                    val line = reader.readLine() ?: return@use null
                    if (line.startsWith("! Version:", ignoreCase = true)) {
                        return@use line.substringAfter(':').trim().ifBlank { null }
                    }
                }
                null
            }
        }.getOrNull()
    }

    private fun describe(error: Throwable): String {
        val cls = error.javaClass.simpleName
        val msg = error.message?.replace('\n', ' ')?.take(120).orEmpty()
        return if (msg.isBlank()) cls else "$cls: $msg"
    }

    private fun isUsable(file: File): Boolean = file.exists() && file.length() >= MIN_BYTES

    companion object {
        private const val MIN_BYTES = 50_000L

        fun logLine(fetch: AdBlockFetch): String {
            val size = sizeLabel(fetch.bytes)
            val version = fetch.version?.let { " $it" }.orEmpty()
            return when {
                !fetch.isPresent ->
                    "[ADS] AdGuard DNS filter нет" + fetch.error?.let { ": $it" }.orEmpty()
                fetch.fromAssets ->
                    "[ADS] AdGuard DNS filter из APK (${AdBlockPolicy.BUNDLED_VERSION}) $size$version" +
                        fetch.error?.let { ", GitHub: $it" }.orEmpty()
                fetch.fromCache && fetch.error != null ->
                    "[ADS] AdGuard DNS filter кэш $size$version (${fetch.error})"
                fetch.fromCache ->
                    "[ADS] AdGuard DNS filter свежий $size$version"
                else ->
                    "[ADS] AdGuard DNS filter скачан $size$version"
            }
        }

        private fun sizeLabel(bytes: Long): String {
            if (bytes >= 1_000_000L) {
                val mb = bytes / 1_000_000.0
                return String.format(java.util.Locale.US, "%.1f МБ", mb)
            }
            if (bytes >= 1_000L) return "${bytes / 1_000L} КБ"
            return "$bytes Б"
        }
    }
}
