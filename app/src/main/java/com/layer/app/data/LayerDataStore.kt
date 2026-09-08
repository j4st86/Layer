package com.layer.app.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.layer.core.config.AdBlockPolicy
import com.layer.core.config.AutoServerPolicy
import com.layer.core.model.AppRoutingRule
import com.layer.core.model.DomainRoutingRule
import com.layer.core.model.LayerSettings
import com.layer.core.model.SavedServer
import com.layer.core.model.SavedSubscription
import com.layer.core.model.VlessServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.layerStore by preferencesDataStore("layer_settings")

class LayerDataStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    val settings: Flow<LayerSettings> = context.layerStore.data.map { prefs ->
        prefsToSettings(prefs)
    }

    val appRules: Flow<List<AppRoutingRule>> = context.layerStore.data.map { prefs ->
        decodeList(prefs[Keys.APP_RULES])
    }

    val domainRules: Flow<List<DomainRoutingRule>> = context.layerStore.data.map { prefs ->
        decodeList(prefs[Keys.DOMAIN_RULES])
    }

    suspend fun saveSettings(settings: LayerSettings) {
        context.layerStore.edit { prefs ->
            writeSettings(prefs, settings)
        }
    }

    suspend fun saveAppRules(rules: List<AppRoutingRule>) {
        context.layerStore.edit { it[Keys.APP_RULES] = json.encodeToString(rules) }
    }

    suspend fun saveDomainRules(rules: List<DomainRoutingRule>) {
        context.layerStore.edit { it[Keys.DOMAIN_RULES] = json.encodeToString(rules) }
    }

    suspend fun resetRouting() {
        context.layerStore.edit { prefs ->
            prefs[Keys.APP_RULES] = "[]"
            prefs[Keys.DOMAIN_RULES] = "[]"
        }
    }

    suspend fun resetAll() {
        context.layerStore.edit { it.clear() }
    }

    private fun prefsToSettings(prefs: Preferences): LayerSettings {
        val servers = decodeList<SavedServer>(prefs[Keys.SERVERS])
        val subscriptions = decodeList<SavedSubscription>(prefs[Keys.SUBSCRIPTIONS])
        val requestedId = prefs[Keys.ACTIVE_SERVER_ID]?.ifBlank { null }
        val active = servers.find { it.id == requestedId } ?: servers.firstOrNull()
        return LayerSettings(
            server = active?.config ?: VlessServerConfig(),
            activeServerId = active?.id,
            servers = servers,
            subscriptions = subscriptions,
            automaticRuleSetEnabled = prefs[Keys.AUTO_RULESET] ?: true,
            ipv6Enabled = prefs[Keys.IPV6] ?: false,
            recommendedAppsPromptDone = prefs[Keys.RECOMMENDED_APPS_PROMPT] ?: false,
            autoSelectServerEnabled = prefs[Keys.AUTO_SELECT_SERVER] ?: false,
            autoSelectIntervalMinutes = AutoServerPolicy.clampInterval(
                prefs[Keys.AUTO_SELECT_INTERVAL] ?: AutoServerPolicy.defaultIntervalMinutes,
            ),
            adBlockEnabled = prefs[Keys.AD_BLOCK] ?: false,
            adBlockUpdateIntervalDays = AdBlockPolicy.clampIntervalDays(
                prefs[Keys.AD_BLOCK_INTERVAL_DAYS] ?: AdBlockPolicy.defaultIntervalDays,
            ),
        )
    }

    private fun writeSettings(prefs: MutablePreferences, settings: LayerSettings) {
        prefs[Keys.SERVERS] = json.encodeToString(settings.servers)
        prefs[Keys.SUBSCRIPTIONS] = json.encodeToString(settings.subscriptions)
        prefs[Keys.ACTIVE_SERVER_ID] = settings.activeServerId.orEmpty()
        prefs[Keys.AUTO_RULESET] = settings.automaticRuleSetEnabled
        prefs[Keys.IPV6] = settings.ipv6Enabled
        prefs[Keys.RECOMMENDED_APPS_PROMPT] = settings.recommendedAppsPromptDone
        prefs[Keys.AUTO_SELECT_SERVER] = settings.autoSelectServerEnabled
        prefs[Keys.AUTO_SELECT_INTERVAL] = AutoServerPolicy.clampInterval(settings.autoSelectIntervalMinutes)
        prefs[Keys.AD_BLOCK] = settings.adBlockEnabled
        prefs[Keys.AD_BLOCK_INTERVAL_DAYS] = AdBlockPolicy.clampIntervalDays(settings.adBlockUpdateIntervalDays)
    }

    private inline fun <reified T> decodeList(raw: String?): List<T> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching { json.decodeFromString<List<T>>(raw) }.getOrDefault(emptyList())
    }

    private object Keys {
        val SERVERS = stringPreferencesKey("servers")
        val SUBSCRIPTIONS = stringPreferencesKey("subscriptions")
        val ACTIVE_SERVER_ID = stringPreferencesKey("active_server_id")
        val AUTO_RULESET = booleanPreferencesKey("auto_ruleset")
        val IPV6 = booleanPreferencesKey("ipv6")
        val RECOMMENDED_APPS_PROMPT = booleanPreferencesKey("recommended_apps_prompt_done")
        val AUTO_SELECT_SERVER = booleanPreferencesKey("auto_select_server")
        val AUTO_SELECT_INTERVAL = intPreferencesKey("auto_select_interval_min")
        val AD_BLOCK = booleanPreferencesKey("ad_block")
        val AD_BLOCK_INTERVAL_DAYS = intPreferencesKey("ad_block_interval_days")
        val APP_RULES = stringPreferencesKey("app_rules")
        val DOMAIN_RULES = stringPreferencesKey("domain_rules")
    }
}
