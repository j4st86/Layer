package com.layer.app.vpn

import android.app.Activity
import android.app.ActivityManager
import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings

/**
 * Android does not let an app turn on Unrestricted battery usage by itself.
 * Layer treats only Unrestricted as OK: Optimized can still be frozen or
 * have "Allow background usage" turned off by the system.
 *
 * "Allow background usage" off is AppOps RUN_ANY_IN_BACKGROUND = ignored.
 * [ActivityManager.isBackgroundRestricted] does not always match that toggle
 * on Android 14+ and OEM skins, so both signals are checked.
 */
object BackgroundKeepAlive {
    enum class Status {
        UNRESTRICTED,
        OPTIMIZED,
        RESTRICTED,
    }

    fun status(context: Context): Status {
        if (isBackgroundRestricted(context)) return Status.RESTRICTED
        return if (isIgnoringBatteryOptimizations(context)) {
            Status.UNRESTRICTED
        } else {
            Status.OPTIMIZED
        }
    }

    fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    fun isBackgroundRestricted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val am = context.getSystemService(ActivityManager::class.java)
            if (am?.isBackgroundRestricted == true) return true
        }
        val mode = runAnyInBackgroundMode(context) ?: return false
        return mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED
    }

    fun requestFix(context: Context) {
        openAppBatterySettings(context)
    }

    fun openAppBatterySettings(context: Context) {
        val pkg = context.packageName
        val candidates = listOf(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", pkg, null)
            },
            Intent("android.settings.APP_BATTERY_SETTINGS").apply {
                data = Uri.parse("package:$pkg")
                putExtra("android.provider.extra.APP_PACKAGE", pkg)
            },
        )
        for (intent in candidates) {
            if (startActivity(context, intent)) return
        }
    }

    fun debugSnapshot(context: Context): String {
        val mode = runAnyInBackgroundMode(context)
        val stopped = context.applicationInfo.flags and ApplicationInfo.FLAG_STOPPED != 0
        val bucket = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                context.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
            } else {
                null
            }
        }.getOrNull()
        return buildString {
            append("status=${status(context)}")
            append(" ignoreBattery=${isIgnoringBatteryOptimizations(context)}")
            append(" amRestricted=")
            append(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    context.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted
                } else {
                    "n/a"
                },
            )
            append(" runAnyInBackground=${modeName(mode)}")
            append(" standbyBucket=$bucket")
            append(" forceStopped=$stopped")
        }
    }

    private fun runAnyInBackgroundMode(context: Context): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return null
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                appOps.unsafeCheckOpNoThrow(
                    OPSTR_RUN_ANY_IN_BACKGROUND,
                    Process.myUid(),
                    context.packageName,
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    OPSTR_RUN_ANY_IN_BACKGROUND,
                    Process.myUid(),
                    context.packageName,
                )
            }
        }.getOrNull()
    }

    private fun modeName(mode: Int?): String = when (mode) {
        null -> "n/a"
        AppOpsManager.MODE_ALLOWED -> "allowed"
        AppOpsManager.MODE_IGNORED -> "ignored"
        AppOpsManager.MODE_ERRORED -> "errored"
        AppOpsManager.MODE_DEFAULT -> "default"
        AppOpsManager.MODE_FOREGROUND -> "foreground"
        else -> mode.toString()
    }

    private const val OPSTR_RUN_ANY_IN_BACKGROUND = "android:run_any_in_background"

    private fun startActivity(context: Context, intent: Intent): Boolean {
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching {
            context.startActivity(intent)
        }.isSuccess
    }
}
