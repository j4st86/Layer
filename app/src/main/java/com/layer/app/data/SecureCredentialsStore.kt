package com.layer.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class SecureCredentialsStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val prefs: SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "layer_secure",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences("layer_secure_fallback", Context.MODE_PRIVATE)
    }

    fun getUuid(serverId: String?): String {
        if (serverId.isNullOrBlank()) return ""
        return readMap()[serverId]?.trim().orEmpty()
    }

    fun setUuid(serverId: String, value: String) {
        val map = readMap().toMutableMap()
        val trimmed = value.trim()
        if (trimmed.isBlank()) {
            map.remove(serverId)
        } else {
            map[serverId] = trimmed
        }
        writeMap(map)
    }

    fun removeUuid(serverId: String) {
        val map = readMap().toMutableMap()
        map.remove(serverId)
        writeMap(map)
    }

    fun hasUuid(serverId: String?): Boolean = getUuid(serverId).isNotBlank()

    fun clearAll() {
        prefs.edit().remove(KEY_UUID_MAP).apply()
    }

    private fun readMap(): Map<String, String> {
        val raw = prefs.getString(KEY_UUID_MAP, "")?.trim().orEmpty()
        if (raw.isBlank()) return emptyMap()
        return runCatching { json.decodeFromString<Map<String, String>>(raw) }.getOrDefault(emptyMap())
    }

    private fun writeMap(map: Map<String, String>) {
        prefs.edit().putString(KEY_UUID_MAP, json.encodeToString(map)).apply()
    }

    companion object {
        private const val KEY_UUID_MAP = "vless_uuid_map"
    }
}
