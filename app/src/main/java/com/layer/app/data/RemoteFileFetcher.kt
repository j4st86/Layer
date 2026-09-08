package com.layer.app.data

import android.net.Network
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a remote list into [destination] via a unique temp file, then
 * rename. Parallel fetches of different files cannot clobber each other;
 * two fetches of the same path still need a mutex around this call.
 */
object RemoteFileFetcher {
    fun download(
        url: String,
        destination: File,
        network: Network?,
        minBytes: Long,
        accept: String,
        htmlError: String,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 60_000,
    ): String? {
        val parent = destination.parentFile ?: return "save failed"
        parent.mkdirs()
        val connection = runCatching {
            val target = URL(url)
            val opened = if (network != null) network.openConnection(target) else target.openConnection()
            (opened as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept", accept)
                setRequestProperty("Accept-Encoding", "identity")
            }
        }.getOrElse { error ->
            return describe(error)
        }
        val tmp = File.createTempFile(safePrefix(destination.name), ".part", parent)
        return try {
            val code = connection.responseCode
            val type = connection.contentType.orEmpty()
            if (code !in 200..299) return "HTTP $code"
            if (type.contains("text/html", ignoreCase = true)) return htmlError
            connection.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() < minBytes) {
                tmp.delete()
                return "too small"
            }
            if (!commit(tmp, destination)) return "save failed"
            null
        } catch (error: Exception) {
            tmp.delete()
            describe(error)
        } finally {
            if (tmp.exists()) tmp.delete()
            connection.disconnect()
        }
    }

    fun copyStream(write: (File) -> Unit, destination: File, minBytes: Long): Boolean {
        val parent = destination.parentFile ?: return false
        parent.mkdirs()
        val tmp = File.createTempFile(safePrefix(destination.name), ".part", parent)
        return try {
            write(tmp)
            if (tmp.length() < minBytes) return false
            commit(tmp, destination)
        } catch (_: Exception) {
            false
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    fun describe(error: Throwable): String {
        val cls = error.javaClass.simpleName
        val msg = error.message?.replace('\n', ' ')?.take(120).orEmpty()
        return if (msg.isBlank()) cls else "$cls: $msg"
    }

    private fun commit(tmp: File, destination: File): Boolean {
        if (destination.exists()) destination.delete()
        if (tmp.renameTo(destination)) return true
        return runCatching {
            tmp.copyTo(destination, overwrite = true)
            destination.exists()
        }.getOrDefault(false)
    }

    private fun safePrefix(name: String): String {
        val trimmed = name.filter { it.isLetterOrDigit() || it == '-' }.ifBlank { "list" }
        return if (trimmed.length >= 3) trimmed.take(24) else (trimmed + "xxx").take(24)
    }
}
