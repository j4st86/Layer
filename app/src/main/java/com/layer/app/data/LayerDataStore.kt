package com.layer.app.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.withTransaction
import com.layer.app.data.db.AppRuleEntity
import com.layer.app.data.db.DomainRuleEntity
import com.layer.app.data.db.LayerDatabase
import com.layer.app.data.db.ServerEntity
import com.layer.app.data.db.SettingsEntity
import com.layer.app.data.db.SubscriptionEntity
import com.layer.core.config.AutoServerPolicy
import com.layer.core.model.AppRoutingMode
import com.layer.core.model.AppRoutingRule
import com.layer.core.model.DomainRoutingMode
import com.layer.core.model.DomainRoutingRule
import com.layer.core.model.LayerSettings
import com.layer.core.model.SavedServer
import com.layer.core.model.SavedSubscription
import com.layer.core.model.VlessServerConfig
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.legacyLayerStore by preferencesDataStore("layer_settings")

/**
 * Profiles, rules, and scalar settings live in Room. UUIDs stay in
 * [SecureCredentialsStore]. Existing Preferences DataStore rows are imported
 * once if the database is empty, then left unread.
 */
class LayerDataStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val db = LayerDatabase.create(context)
    private val importMutex = Mutex()

    val settings: Flow<LayerSettings> = combine(
        db.settings().observe(),
        db.servers().observeAll(),
        db.subscriptions().observeAll(),
    ) { settings, servers, subscriptions ->
        toSettings(settings ?: SettingsEntity(), servers, subscriptions)
    }.onStart { importFromDataStoreIfEmpty() }

    val appRules: Flow<List<AppRoutingRule>> = db.appRules().observeAll()
        .map { rows -> rows.map { it.toModel() } }
        .onStart { importFromDataStoreIfEmpty() }

    val domainRules: Flow<List<DomainRoutingRule>> = db.domainRules().observeAll()
        .map { rows -> rows.map { it.toModel() } }
        .onStart { importFromDataStoreIfEmpty() }

    suspend fun saveSettings(settings: LayerSettings) {
        importFromDataStoreIfEmpty()
        db.withTransaction {
            db.servers().deleteAll()
            db.subscriptions().deleteAll()
            db.servers().upsertAll(settings.servers.map { it.toEntity() })
            db.subscriptions().upsertAll(settings.subscriptions.map { it.toEntity() })
            db.settings().upsert(settings.toEntity())
        }
    }

    suspend fun saveAppRules(rules: List<AppRoutingRule>) {
        importFromDataStoreIfEmpty()
        db.withTransaction {
            db.appRules().deleteAll()
            db.appRules().upsertAll(rules.map { it.toEntity() })
        }
    }

    suspend fun saveDomainRules(rules: List<DomainRoutingRule>) {
        importFromDataStoreIfEmpty()
        db.withTransaction {
            db.domainRules().deleteAll()
            db.domainRules().upsertAll(rules.map { it.toEntity() })
        }
    }

    suspend fun resetRouting() {
        importFromDataStoreIfEmpty()
        db.withTransaction {
            db.appRules().deleteAll()
            db.domainRules().deleteAll()
        }
    }

    suspend fun resetAll() {
        db.withTransaction {
            db.servers().deleteAll()
            db.subscriptions().deleteAll()
            db.appRules().deleteAll()
            db.domainRules().deleteAll()
            db.settings().upsert(SettingsEntity())
        }
        context.legacyLayerStore.updateData { emptyPreferences() }
    }

    private suspend fun importFromDataStoreIfEmpty() {
        importMutex.withLock {
            if (db.settings().get() != null) return@withLock
            val prefs = context.legacyLayerStore.data.first()
            val imported = prefsToSettings(prefs)
            val appRules = decodeList<AppRoutingRule>(prefs[Keys.APP_RULES])
            val domainRules = decodeList<DomainRoutingRule>(prefs[Keys.DOMAIN_RULES])
            db.withTransaction {
                db.servers().upsertAll(imported.servers.map { it.toEntity() })
                db.subscriptions().upsertAll(imported.subscriptions.map { it.toEntity() })
                db.appRules().upsertAll(appRules.map { it.toEntity() })
                db.domainRules().upsertAll(domainRules.map { it.toEntity() })
                db.settings().upsert(imported.toEntity())
            }
        }
    }

    private fun toSettings(
        settings: SettingsEntity,
        servers: List<ServerEntity>,
        subscriptions: List<SubscriptionEntity>,
    ): LayerSettings {
        val savedServers = servers.map { it.toModel() }
        val savedSubs = subscriptions.map { it.toModel() }
        val active = savedServers.find { it.id == settings.activeServerId } ?: savedServers.firstOrNull()
        return LayerSettings(
            server = active?.config ?: VlessServerConfig(),
            activeServerId = active?.id,
            servers = savedServers,
            subscriptions = savedSubs,
            automaticRuleSetEnabled = settings.automaticRuleSetEnabled,
            ipv6Enabled = settings.ipv6Enabled,
            recommendedAppsPromptDone = settings.recommendedAppsPromptDone,
            autoSelectServerEnabled = settings.autoSelectServerEnabled,
            autoSelectIntervalMinutes = AutoServerPolicy.clampInterval(settings.autoSelectIntervalMinutes),
            adBlockEnabled = settings.adBlockEnabled,
        )
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
        )
    }

    private fun LayerSettings.toEntity(): SettingsEntity = SettingsEntity(
        activeServerId = activeServerId,
        automaticRuleSetEnabled = automaticRuleSetEnabled,
        ipv6Enabled = ipv6Enabled,
        recommendedAppsPromptDone = recommendedAppsPromptDone,
        autoSelectServerEnabled = autoSelectServerEnabled,
        autoSelectIntervalMinutes = AutoServerPolicy.clampInterval(autoSelectIntervalMinutes),
        adBlockEnabled = adBlockEnabled,
    )

    private fun SavedServer.toEntity(): ServerEntity = ServerEntity(
        id = id,
        name = name,
        configJson = json.encodeToString(config),
        subscriptionId = subscriptionId,
        note = note,
    )

    private fun ServerEntity.toModel(): SavedServer = SavedServer(
        id = id,
        name = name,
        config = runCatching { json.decodeFromString<VlessServerConfig>(configJson) }
            .getOrDefault(VlessServerConfig()),
        subscriptionId = subscriptionId,
        note = note,
    )

    private fun SavedSubscription.toEntity(): SubscriptionEntity = SubscriptionEntity(
        id = id,
        name = name,
        url = url,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        note = note,
    )

    private fun SubscriptionEntity.toModel(): SavedSubscription = SavedSubscription(
        id = id,
        name = name,
        url = url,
        lastUpdatedEpochMs = lastUpdatedEpochMs,
        note = note,
    )

    private fun AppRoutingRule.toEntity(): AppRuleEntity = AppRuleEntity(
        packageName = packageName,
        appName = appName,
        mode = mode.name,
    )

    private fun AppRuleEntity.toModel(): AppRoutingRule = AppRoutingRule(
        packageName = packageName,
        appName = appName,
        mode = runCatching { AppRoutingMode.valueOf(mode) }.getOrDefault(AppRoutingMode.SMART),
    )

    private fun DomainRoutingRule.toEntity(): DomainRuleEntity = DomainRuleEntity(
        domain = domain,
        mode = mode.name,
    )

    private fun DomainRuleEntity.toModel(): DomainRoutingRule = DomainRoutingRule(
        domain = domain,
        mode = runCatching { DomainRoutingMode.valueOf(mode) }.getOrDefault(DomainRoutingMode.VPN),
    )

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
        val APP_RULES = stringPreferencesKey("app_rules")
        val DOMAIN_RULES = stringPreferencesKey("domain_rules")
    }
}
