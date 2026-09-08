package com.layer.app.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.layer.core.config.AdBlockPolicy
import com.layer.core.config.DnsHostlistFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class AdBlockFetch(
    val path: String? = null,
    val bytes: Long = 0,
    val fromCache: Boolean = false,
    val fromAssets: Boolean = false,
    val waitForWifi: Boolean = false,
    val version: String? = null,
    val error: String? = null,
) {
    val isPresent: Boolean get() = path != null
}

class AdBlockDownloader(context: Context) {
    private val app = context.applicationContext
    private val dir = File(app.filesDir, "adblock")
    private val filterFile get() = File(dir, AdBlockPolicy.fileName)
    private val ruleSetFile get() = File(dir, AdBlockPolicy.ruleSetFileName)
    private val mutex = Mutex()

    fun existingRuleSet(): File? {
        val local = ruleSetFile
        return if (isUsableRuleSet(local)) local else null
    }

    fun debugSnapshot(): String {
        val local = existingRuleSet() ?: return "missing"
        val ageMs = (System.currentTimeMillis() - local.lastModified()).coerceAtLeast(0L)
        val ageHours = ageMs / (60L * 60L * 1000L)
        val version = readVersion(filterFile)
        return buildString {
            append("${local.length()} bytes")
            if (version != null) append(" version=$version")
            append(" age=${ageHours}h")
        }
    }

    suspend fun ensureCopy(network: Network?): AdBlockFetch = mutex.withLock {
        withContext(Dispatchers.IO) {
            dir.mkdirs()
            val json = ruleSetFile
            val txt = filterFile
            if (isFresh(json) && isUsableRuleSet(json)) {
                return@withContext AdBlockFetch(
                    path = json.absolutePath,
                    bytes = json.length(),
                    fromCache = true,
                    version = readVersion(txt),
                )
            }
            val downloadNetwork = adListNetwork(network)
            var fromAssets = false
            var downloaded = false
            var waitForWifi = false
            var error: String? = null
            if (!isFresh(txt) || !isUsableFilter(txt)) {
                if (downloadNetwork != null) {
                    error = RemoteFileFetcher.download(
                        url = AdBlockPolicy.listUrl,
                        destination = txt,
                        network = downloadNetwork,
                        minBytes = MIN_FILTER_BYTES,
                        accept = "text/plain,*/*",
                        htmlError = "HTML instead of filter",
                    )
                    downloaded = error == null
                } else {
                    waitForWifi = true
                }
                if (!isUsableFilter(txt) && copyFromAssets(txt)) {
                    fromAssets = true
                }
            }
            if (isUsableFilter(txt) && needsConvert(txt, json)) {
                val converted = convert(txt, json)
                if (converted != null) error = converted
            }
            if (isUsableRuleSet(json)) {
                return@withContext AdBlockFetch(
                    path = json.absolutePath,
                    bytes = json.length(),
                    fromCache = !downloaded && !fromAssets,
                    fromAssets = fromAssets,
                    waitForWifi = waitForWifi,
                    version = readVersion(txt) ?: AdBlockPolicy.BUNDLED_VERSION.takeIf { fromAssets },
                    error = error,
                )
            }
            AdBlockFetch(waitForWifi = waitForWifi, error = error ?: "missing")
        }
    }

    private fun adListNetwork(preferred: Network?): Network? {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        if (preferred != null && isWifiOrEthernet(connectivity, preferred)) return preferred
        return connectivity.allNetworks.filter { isWifiOrEthernet(connectivity, it) }
            .maxByOrNull { network ->
                val caps = connectivity.getNetworkCapabilities(network)
                when {
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> 2
                    caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> 1
                    else -> 0
                }
            }
    }

    private fun isWifiOrEthernet(connectivity: ConnectivityManager, network: Network): Boolean {
        val caps = connectivity.getNetworkCapabilities(network) ?: return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun convert(txt: File, json: File): String? {
        val parsed = runCatching {
            txt.bufferedReader().use { DnsHostlistFilter.parse(it) }
        }.getOrElse { return RemoteFileFetcher.describe(it) }
        if (parsed.block.size < MIN_PARSED_RULES) {
            return "too few rules (${parsed.block.size})"
        }
        val written = RemoteFileFetcher.copyStream(
            write = { tmp -> parsed.writeSourceJson(tmp) },
            destination = json,
            minBytes = MIN_RULESET_BYTES,
        )
        return if (written) null else "convert failed"
    }

    private fun copyFromAssets(destination: File): Boolean {
        val assetPath = "${AdBlockPolicy.ASSET_DIR}/${AdBlockPolicy.fileName}"
        return RemoteFileFetcher.copyStream(
            write = { tmp ->
                app.assets.open(assetPath).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
            },
            destination = destination,
            minBytes = MIN_FILTER_BYTES,
        )
    }

    private fun readVersion(local: File): String? {
        if (!local.exists()) return null
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

    private fun needsConvert(txt: File, json: File): Boolean {
        if (!isUsableRuleSet(json)) return true
        return json.lastModified() < txt.lastModified()
    }

    private fun isFresh(file: File): Boolean {
        if (!file.exists()) return false
        return System.currentTimeMillis() - file.lastModified() < AdBlockPolicy.FRESHNESS_MS
    }

    private fun isUsableFilter(file: File): Boolean = file.exists() && file.length() >= MIN_FILTER_BYTES

    private fun isUsableRuleSet(file: File): Boolean = file.exists() && file.length() >= MIN_RULESET_BYTES

    companion object {
        private const val MIN_FILTER_BYTES = 50_000L
        private const val MIN_RULESET_BYTES = 10_000L
        private const val MIN_PARSED_RULES = 1_000

        fun logLine(fetch: AdBlockFetch): String {
            val size = sizeLabel(fetch.bytes)
            val version = fetch.version?.let { " $it" }.orEmpty()
            val wifi = if (fetch.waitForWifi) " (скачивание только по Wi‑Fi)" else ""
            return when {
                !fetch.isPresent ->
                    "[ADS] DNS-фильтр рекламы нет" + fetch.error?.let { ": $it" }.orEmpty() + wifi
                fetch.fromAssets ->
                    "[ADS] DNS-фильтр рекламы из APK (${AdBlockPolicy.BUNDLED_VERSION}) $size$version" +
                        fetch.error?.let { ", сеть: $it" }.orEmpty() + wifi
                fetch.fromCache && fetch.error != null ->
                    "[ADS] DNS-фильтр рекламы кэш $size$version (${fetch.error})$wifi"
                fetch.fromCache ->
                    "[ADS] DNS-фильтр рекламы свежий $size$version$wifi"
                else ->
                    "[ADS] DNS-фильтр рекламы скачан $size$version"
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
