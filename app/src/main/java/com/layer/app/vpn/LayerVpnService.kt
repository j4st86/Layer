package com.layer.app.vpn

import android.content.Context
import android.content.Intent
import android.net.VpnService

/**
 * Android VpnService shell. Lifecycle, TUN, and libbox live in [LayerBoxService],
 * matching SFA's VPNService → BoxService split.
 */
class LayerVpnService : VpnService() {
    private val box = LayerBoxService(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return box.onStartCommand(intent)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        box.onTaskRemoved()
    }

    override fun onCreate() {
        super.onCreate()
        running = this
    }

    override fun onDestroy() {
        if (running === this) running = null
        box.onDestroy()
        super.onDestroy()
    }

    override fun onRevoke() {
        box.onRevoke()
        super.onRevoke()
    }

    companion object {
        const val ACTION_START = "com.layer.app.START"
        const val ACTION_STOP = "com.layer.app.STOP"
        const val ACTION_RELOAD = "com.layer.app.RELOAD"
        const val ACTION_REWIRE = "com.layer.app.REWIRE"
        const val EXTRA_SERVER_NAME = "com.layer.app.EXTRA_SERVER_NAME"

        @Volatile
        private var running: LayerVpnService? = null

        fun wakeAfterHandoff(reason: String) {
            running?.box?.recoverAfterHandoff(reason)
        }

        fun start(context: Context, serverName: String = "") {
            val intent = Intent(context, LayerVpnService::class.java).setAction(ACTION_START)
            if (serverName.isNotBlank()) {
                intent.putExtra(EXTRA_SERVER_NAME, serverName)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(stopIntent(context))
        }

        fun reload(context: Context) {
            val intent = Intent(context, LayerVpnService::class.java).setAction(ACTION_RELOAD)
            context.startForegroundService(intent)
        }

        fun stopIntent(context: Context): Intent {
            return Intent(context, LayerVpnService::class.java).setAction(ACTION_STOP)
        }

        fun rewireIntent(context: Context): Intent {
            return Intent(context, LayerVpnService::class.java).setAction(ACTION_REWIRE)
        }
    }
}
