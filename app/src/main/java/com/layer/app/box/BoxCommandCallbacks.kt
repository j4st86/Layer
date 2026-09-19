package com.layer.app.box

/**
 * Commands the CommandServer may send back into the Android service.
 * Implemented by LayerVpnService; the libbox handler type stays in the adapter.
 */
interface BoxCommandCallbacks {
    fun onBoxReload()
    fun onBoxStop()
}
