package com.layer.app.data

import android.content.Context
import android.net.Network
import com.layer.core.config.RuleSetCatalog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
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
    private val locks = ConcurrentHashMap<String, Mutex>()

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
                    lockFor(set.tag).withLock {
                        val file = File(dir, fileName(set.tag))
                        val usable = isUsable(file)
                        val fresh = usable &&
                            System.currentTimeMillis() - file.lastModified() < RuleSetCatalog.FRESHNESS_MS
                        if (fresh) return@withLock set.tag to file.absolutePath
                        val error = RemoteFileFetcher.download(
                            url = set.url,
                            destination = file,
                            network = network,
                            minBytes = MIN_BYTES,
                            accept = "application/octet-stream,*/*",
                            htmlError = "HTML instead of rule-set",
                            readTimeoutMs = 30_000,
                        )
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
                }
            }.awaitAll().filterNotNull().toMap()
        }
        RuleSetDirectFetch(
            files = files,
            error = firstError.get(),
            seededFromAssets = seeded.get(),
        )
    }

    private fun lockFor(tag: String): Mutex = locks.getOrPut(tag) { Mutex() }

    private fun copyFromAssets(tag: String, destination: File): Boolean {
        val assetPath = "${RuleSetCatalog.ASSET_DIR}/${fileName(tag)}"
        return RemoteFileFetcher.copyStream(
            write = { tmp ->
                app.assets.open(assetPath).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            },
            destination = destination,
            minBytes = MIN_BYTES,
        )
    }

    private fun isUsable(file: File): Boolean = file.exists() && file.length() >= MIN_BYTES

    private fun fileName(tag: String): String = "$tag.srs"

    companion object {
        private const val MIN_BYTES = 64L
    }
}
