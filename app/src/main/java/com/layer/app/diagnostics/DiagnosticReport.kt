package com.layer.app.diagnostics

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.layer.app.R
import com.layer.app.BuildConfig
import com.layer.app.data.RuleSetDownloader
import com.layer.app.di.AppContainer
import com.layer.app.vpn.BackgroundKeepAlive
import com.layer.core.diagnostics.Branding
import com.layer.core.diagnostics.LogSanitizer
import io.nekohasekai.libbox.Libbox
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticReport {
    private const val LOGS_FOLDER = "Layer logs"

    suspend fun build(context: Context, container: AppContainer): String {
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale("ru")).format(Date())
        val status = container.vpnController.status.value
        val snapshot = runCatching { container.repository.currentSnapshot() }.getOrNull()
        val lastConfig = container.diagnostics.lastStartedConfig
        val configJson = if (lastConfig.isNotBlank()) {
            ""
        } else {
            val localRuleSets = runCatching {
                RuleSetDownloader(context).existingLocalCopies()
            }.getOrDefault(emptyMap())
            runCatching {
                container.repository.buildConfig(
                    localRuleSets = localRuleSets,
                    remoteRuleSetFallback = false,
                ).json
            }.getOrDefault("")
        }
        val libboxVersion = Branding.libboxVersion(runCatching { Libbox.version() }.getOrNull())
        return buildString {
            appendLine("Layer diagnostic report")
            appendLine("Generated: $stamp")
            appendLine("Package: ${context.packageName}")
            appendLine("App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
            appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("libbox: $libboxVersion")
            appendLine("Build: debug=${BuildConfig.DEBUG}")
            appendLine("VPN state: ${status.state} / ${status.message}")
            appendLine("Background: ${BackgroundKeepAlive.debugSnapshot(context)}")
            if (!status.errorTitle.isNullOrBlank()) {
                appendLine("Error: ${status.errorTitle}")
            }
            if (!status.errorDetails.isNullOrBlank()) {
                appendLine("Details: ${LogSanitizer.sanitize(status.errorDetails)}")
            }
            if (snapshot != null) {
                appendLine("Connection: ${snapshot.settings.activeConnectionName()}")
                appendLine("Server: ${snapshot.settings.server.address}:${snapshot.settings.server.port}")
                appendLine("Servers: ${snapshot.settings.servers.size}")
                appendLine("Subscriptions: ${snapshot.settings.subscriptions.size}")
                appendLine("SNI: ${snapshot.settings.server.serverName}")
                appendLine("Network: ${snapshot.settings.server.network}")
                appendLine("Transport mode: ${snapshot.settings.server.transportMode.ifBlank { "-" }}")
                appendLine("Security: ${snapshot.settings.server.security}")
                appendLine("Flow: ${snapshot.settings.server.flow}")
                appendLine("Fingerprint: ${snapshot.settings.server.fingerprint}")
                appendLine("ALPN: ${snapshot.settings.server.alpn}")
                appendLine("Automatic lists: ${snapshot.settings.automaticRuleSetEnabled}")
                appendLine("Auto select server: ${snapshot.settings.autoSelectServerEnabled}")
                appendLine("Auto select interval min: ${snapshot.settings.autoSelectIntervalMinutes}")
                appendLine("Auto select latency ms: ${container.autoServerSelector.status.value.latencyMs ?: "-"}")
                appendLine("Display ping ms: ${container.connectionPing.status.value.latencyMs ?: "-"}")
                appendLine("IPv6: ${snapshot.settings.ipv6Enabled}")
                appendLine("App rules: ${snapshot.appRules.size}")
                appendLine("Domain rules: ${snapshot.domainRules.size}")
                appendLine("UUID configured: ${snapshot.hasUuid}")
            }
            appendLine()
            appendLine("=== Logs ===")
            val logs = container.diagnostics.exportText()
            appendLine(logs.ifBlank { "(empty)" })
            if (container.diagnostics.lastTunDump.isNotBlank()) {
                appendLine()
                appendLine("=== Last TUN dump ===")
                appendLine(container.diagnostics.lastTunDump)
            }
            if (lastConfig.isNotBlank()) {
                appendLine()
                appendLine("=== Last started sing-box config (secrets removed) ===")
                appendLine(lastConfig)
            } else if (configJson.isNotBlank()) {
                appendLine()
                appendLine("=== Current sing-box config (secrets removed) ===")
                appendLine(LogSanitizer.sanitize(configJson))
            }
            val working = File(context.filesDir, "sing-box")
            if (working.isDirectory) {
                appendLine()
                appendLine("=== working dir ===")
                working.walkTopDown().maxDepth(2).forEach { file ->
                    if (file != working) {
                        appendLine("${file.relativeTo(working)} ${file.length()}b")
                    }
                }
            }
        }
    }

    suspend fun share(context: Context, container: AppContainer): Result<String> {
        return runCatching {
            val text = build(context, container)
            saveAndShare(context, text).getOrThrow()
        }
    }

    fun saveAndShare(context: Context, text: String): Result<String> {
        val name = "layer-report-${SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())}.txt"
        val saved = runCatching { writeToDownloads(context, name, text) }.getOrNull()
        val shareFile = File(File(context.cacheDir, "reports").apply { mkdirs() }, name)
        shareFile.writeText(text, Charsets.UTF_8)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", shareFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, context.getString(R.string.report_subject))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.send_report_chooser)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return Result.success(saved ?: shareFile.absolutePath)
    }

    private fun writeToDownloads(context: Context, name: String, text: String): String {
        val relative = "${Environment.DIRECTORY_DOWNLOADS}/$LOGS_FOLDER"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(MediaStore.Downloads.RELATIVE_PATH, "$relative/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("Downloads insert failed")
            resolver.openOutputStream(uri)?.use { stream ->
                stream.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))
                stream.write(text.toByteArray(Charsets.UTF_8))
            }
                ?: error("Downloads stream failed")
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return "$relative/$name"
        }
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            LOGS_FOLDER,
        )
        dir.mkdirs()
        val file = File(dir, name)
        file.writeText(text, Charsets.UTF_8)
        return file.absolutePath
    }
}
