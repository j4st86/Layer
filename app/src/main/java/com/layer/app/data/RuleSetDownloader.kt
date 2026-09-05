package com.layer.app.data

import android.content.Context
import android.net.Network
import com.layer.core.config.RuleSetCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class RuleSetDirectFetch(
    val files: Map<String, String>,
    val error: String? = null,
    val seededFromAssets: Int = 0,
)

class RuleSetDownloader(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "sing-box/rule-sets")

    fun existingLocalCopies(): Map<String, String> {
        dir.mkdirs()
        return RuleSetCatalog.vpnLists.mapNotNull { set ->
            val file = File(dir, fileName(set.tag))
            if (isUsable(file)) set.tag to file.absolutePath else null
        }.toMap()
    }

    suspend fun ensureDirectCopies(network: Network?): RuleSetDirectFetch = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val firstError = AtomicReference<String?>(null)
        val seeded = AtomicInteger(0)
        val files = supervisorScope {
            RuleSetCatalog.vpnLists.map { set ->
                async {
                    val file = File(dir, fileName(set.tag))
                    val usable = isUsable(file)
                    val fresh = usable && System.currentTimeMillis() - file.lastModified() < RuleSetCatalog.FRESHNESS_MS
                    if (fresh) return@async set.tag to file.absolutePath
                    val error = download(set.url, file, network)
                    when {
                        error == null -> set.tag to file.absolutePath
                        usable -> set.tag to file.absolutePath
                        copyFromAssets(set.tag, file) -> {
                            seeded.incrementAndGet()
                            set.tag to file.absolutePath
                        }
                        else -> {
                            firstError.compareAndSet(null, error)
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull().toMap()
        }
        RuleSetDirectFetch(
            files = files,
            error = firstError.get(),
            seededFromAssets = seeded.get(),
        )
    }

    private fun copyFromAssets(tag: String, destination: File): Boolean {
        val assetPath = "${RuleSetCatalog.ASSET_DIR}/${fileName(tag)}"
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
                readTimeout = 30_000
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
                )
                setRequestProperty("Accept", "application/octet-stream,*/*")
                setRequestProperty("Accept-Encoding", "identity")
            }
        }.getOrElse { error ->
            return describe(error)
        }
        return try {
            val code = connection.responseCode
            val type = connection.contentType.orEmpty()
            if (code !in 200..299) {
                return "HTTP $code"
            }
            if (type.contains("text/html", ignoreCase = true)) {
                return "HTML instead of rule-set"
            }
            val tmp = File(destination.parentFile, destination.name + ".tmp")
            connection.inputStream.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            if (tmp.length() < MIN_BYTES) {
                tmp.delete()
                return "too small"
            }
            if (destination.exists()) destination.delete()
            if (!tmp.renameTo(destination)) {
                return "save failed"
            }
            null
        } catch (error: Exception) {
            describe(error)
        } finally {
            connection.disconnect()
        }
    }

    private fun describe(error: Throwable): String {
        val cls = error.javaClass.simpleName
        val msg = error.message?.replace('\n', ' ')?.take(120).orEmpty()
        return if (msg.isBlank()) cls else "$cls: $msg"
    }

    private fun isUsable(file: File): Boolean = file.exists() && file.length() >= MIN_BYTES

    private fun fileName(tag: String): String = "$tag.srs"

    companion object {
        private const val MIN_BYTES = 64L
    }
}
