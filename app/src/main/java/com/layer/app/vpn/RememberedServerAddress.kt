package com.layer.app.vpn

import android.content.Context
import com.layer.core.config.ServerDialAddress

/**
 * Last IPv4 that resolved for a VPN hostname. Used when phone DNS is empty
 * at start or Rewire, so the config does not fall back to dialing the name.
 */
internal class RememberedServerAddress(private val context: Context) {
    // VpnService constructs LayerBoxService before attachBaseContext, so the
    // application context is still null. Touch prefs only on the first dial.
    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun get(host: String): String? {
        return prefs.getString(key(host), null)?.let(ServerDialAddress::rememberable)
    }

    fun put(host: String, ip: String) {
        val clean = ServerDialAddress.rememberable(ip) ?: return
        prefs.edit().putString(key(host), clean).commit()
    }

    private fun key(host: String): String = host.trim().lowercase()

    private companion object {
        const val PREFS = "layer_server_dial"
    }
}
