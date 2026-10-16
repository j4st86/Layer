package com.layer.app.vpn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
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
            VpnConnectionState.CONNECTED ->
                if (name != null) service.getString(R.string.notification_wired_to, name)
                else service.getString(R.string.notification_wired)
            VpnConnectionState.RECONNECTING -> service.getString(R.string.status_reconnecting)
            VpnConnectionState.CONNECTING -> service.getString(R.string.status_connecting_ellipsis)
            else -> service.getString(R.string.app_name)
        }
        return Notification.Builder(service, CHANNEL_ID)
            .setContentTitle(title)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .addAction(action(service.getString(R.string.notification_rewire), rewire))
            .addAction(action(service.getString(R.string.notification_unwire), stop))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun action(title: String, intent: PendingIntent): Notification.Action {
        // Action icons are required by the platform. Phones hide them and show
        // the title; watches and notification listeners still receive the icon.
        return Notification.Action.Builder(
            Icon.createWithResource(service, R.drawable.ic_notification),
            title,
            intent,
        ).build()
    }

    fun startForeground(state: VpnConnectionState, serverName: String? = null) {
        ensureChannel()
        val notification = build(state, serverName)
        service.startForeground(
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED,
        )
    }

    fun update(state: VpnConnectionState, serverName: String? = null) {
        service.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(state, serverName))
    }

    fun cancel() {
        service.getSystemService(NotificationManager::class.java)
            .cancel(NOTIFICATION_ID)
    }

    /** Stays in the shade after the VPN service stops. The user just pressed Rewire. */
    fun showServerUnreachable(serverName: String?) {
        ensureChannel()
        val open = PendingIntent.getActivity(
            service,
            0,
            android.content.Intent(service, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val name = serverName?.takeIf { it.isNotBlank() }
        val builder = Notification.Builder(service, CHANNEL_ID)
            .setContentTitle(service.getString(R.string.notification_server_unreachable))
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setCategory(Notification.CATEGORY_STATUS)
        if (name != null) {
            builder.setContentText(service.getString(R.string.notification_server_unreachable_named, name))
        }
        service.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, builder.build())
    }

    companion object {
        const val CHANNEL_ID = "layer_vpn"
        const val NOTIFICATION_ID = 1

        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.app_name),
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
