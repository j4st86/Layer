package com.layer.app.vpn

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

object NotificationPermission {
    fun isGranted(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
}
