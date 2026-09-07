package com.layer.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import com.layer.app.MainActivity
import com.layer.app.R

class VpnNotification(private val service: Service) {
    fun ensureChannel() = Companion.ensureChannel(service)

    fun build(state: VpnConnectionState, serverName: String? = null): Notification {
        val open = PendingIntent.getActivity(
            service,
            0,
            android.content.Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            service,
            1,
            LayerVpnService.stopIntent(service),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val rewire = PendingIntent.getService(
            service,
            2,
            LayerVpnService.rewireIntent(service),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = serverName?.takeIf { it.isNotBlank() }
        val title = when (state) {
            VpnConnectionState.CONNECTED -> if (name != null) "Wired to $name" else "Wired to"
            VpnConnectionState.RECONNECTING -> "Reconnecting…"
            VpnConnectionState.CONNECTING -> "Connecting…"
            else -> "Layer"
        }
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(0, service.getString(R.string.notification_rewire), rewire)
            .addAction(0, service.getString(R.string.notification_unwire), stop)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    fun startForeground(state: VpnConnectionState, serverName: String? = null) {
        ensureChannel()
        val notification = build(state, serverName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            service.startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
            )
        } else {
            service.startForeground(NOTIFICATION_ID, notification)
        }
    }

    fun update(state: VpnConnectionState, serverName: String? = null) {
        service.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(state, serverName))
    }

    fun cancel() {
        service.getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    companion object {
        const val CHANNEL_ID = "layer_vpn"
        const val NOTIFICATION_ID = 1

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Layer",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.vpn_channel_description)
                    setShowBadge(false)
                }
                context.getSystemService(NotificationManager::class.java)
                    .createNotificationChannel(channel)
            }
        }
    }
}
