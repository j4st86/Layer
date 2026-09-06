package com.layer.app.data

import android.content.Context
import com.layer.app.BuildConfig
import com.layer.core.config.AppVersion
import com.layer.core.i18n.copy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed class UpdateCheckResult {
    data class UpToDate(val installed: String, val latest: String) : UpdateCheckResult()
    data class Available(
        val installed: String,
        val latest: String,
        val pageUrl: String,
        val apkUrl: String?,
    ) : UpdateCheckResult()
    data class Failed(val message: String) : UpdateCheckResult()
}

class UpdateChecker(
    context: Context,
    private val client: GithubReleaseClient = GithubReleaseClient(),
    private val installedVersion: String = BuildConfig.VERSION_NAME,
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun shouldAutoCheck(nowMs: Long = System.currentTimeMillis()): Boolean {
        val last = prefs.getLong(KEY_LAST_CHECK_MS, 0L)
        return last <= 0L || nowMs - last >= INTERVAL_MS
    }

    fun shouldPrompt(latest: String): Boolean {
        val dismissed = prefs.getString(KEY_DISMISSED, "").orEmpty()
        if (dismissed.isBlank()) return true
        return AppVersion.isNewer(dismissed, latest)
    }

    fun rememberDismissed(latest: String) {
        prefs.edit().putString(KEY_DISMISSED, AppVersion.normalize(latest)).apply()
    }

    suspend fun check(force: Boolean, nowMs: Long = System.currentTimeMillis()): UpdateCheckResult {
        if (!force && !shouldAutoCheck(nowMs)) {
            return UpdateCheckResult.UpToDate(installedVersion, installedVersion)
        }
        val result = withContext(Dispatchers.IO) { client.fetchLatest() }
        prefs.edit().putLong(KEY_LAST_CHECK_MS, nowMs).apply()
        return result.fold(
            onSuccess = { release ->
                if (AppVersion.isNewer(installedVersion, release.tag)) {
                    UpdateCheckResult.Available(
                        installed = installedVersion,
                        latest = AppVersion.normalize(release.tag),
                        pageUrl = release.pageUrl,
                        apkUrl = release.apkUrl?.ifBlank { null },
                    )
                } else {
                    UpdateCheckResult.UpToDate(
                        installed = installedVersion,
                        latest = AppVersion.normalize(release.tag),
                    )
                }
            },
            onFailure = { error ->
                UpdateCheckResult.Failed(
                    error.message?.ifBlank { null }
                        ?: copy("Could not check for updates.", "Не удалось проверить обновления."),
                )
            },
        )
    }

    companion object {
        const val INTERVAL_MS = 7L * 24 * 60 * 60 * 1000
        private const val PREFS = "layer_updates"
        private const val KEY_LAST_CHECK_MS = "last_check_ms"
        private const val KEY_DISMISSED = "dismissed_version"
    }
}
