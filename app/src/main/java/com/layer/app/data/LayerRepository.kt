package com.layer.app.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Build
import com.layer.app.R
import com.layer.core.config.ConnectionIdentity
import com.layer.core.config.DuplicateConnectionException
import com.layer.core.config.ParsedVlessLink
import com.layer.core.config.RecommendedApps
import com.layer.core.config.SingBoxConfigGenerator
import com.layer.core.config.SubscriptionParser
import com.layer.core.config.VlessLinkParser
import com.layer.core.config.VpnConnectionInput
import com.layer.core.config.VpnConnectionParser
import com.layer.core.i18n.copy
import com.layer.core.model.AppRoutingRule
import com.layer.core.model.DomainRoutingRule
import com.layer.core.model.LayerSettings
import com.layer.core.model.SavedServer
import com.layer.core.model.SavedSubscription
import com.layer.core.model.VlessServerConfig
import com.layer.core.routing.HostnameNormalizer
import com.layer.core.routing.RoutingEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

data class InstalledApp(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
)

data class LayerSnapshot(
    val settings: LayerSettings,
    val appRules: List<AppRoutingRule>,
    val domainRules: List<DomainRoutingRule>,
    val hasUuid: Boolean,
)

class LayerRepository(
    private val context: Context,
    private val dataStore: LayerDataStore,
    private val credentials: SecureCredentialsStore,
    private val subscriptionClient: SubscriptionClient = SubscriptionClient(),
) {
    private val mutex = Mutex()

    val settings: Flow<LayerSettings> = dataStore.settings
    val appRules: Flow<List<AppRoutingRule>> = dataStore.appRules
    val domainRules: Flow<List<DomainRoutingRule>> = dataStore.domainRules

    val snapshot: Flow<LayerSnapshot> = combine(settings, appRules, domainRules) { s, apps, domains ->
        LayerSnapshot(s, apps, domains, credentials.hasUuid(s.activeServerId))
    }

    suspend fun currentSnapshot(): LayerSnapshot = snapshot.first()

    suspend fun importConnection(raw: String): Result<Unit> {
        val parsed = VpnConnectionParser.parse(raw).getOrElse { return Result.failure(it) }
        return when (parsed) {
            is VpnConnectionInput.Subscription -> addSubscription(parsed.raw)
            is VpnConnectionInput.VlessLink -> addServer(parsed.raw)
        }
    }

    suspend fun addServer(raw: String): Result<Unit> = mutex.withLock {
        val parsed = VlessLinkParser.parse(raw).getOrElse { return@withLock Result.failure(it) }
        val current = dataStore.settings.first()
        val existing = current.servers.find { saved ->
            ConnectionIdentity.matches(
                existingUuid = credentials.getUuid(saved.id),
                existingConfig = saved.config,
                incoming = parsed,
            )
        }
        val incomingConfig = parsed.server
            ?: return@withLock Result.failure(
                IllegalArgumentException(
                    copy("Paste a full vless:// link.", "Вставьте vless:// ссылку целиком."),
                ),
            )
        if (existing != null) {
            val updatedConfig = incomingConfig.copy(displayName = incomingConfig.visibleName())
            val updated = existing.copy(
                name = updatedConfig.visibleName(),
                config = updatedConfig,
            )
            dataStore.saveSettings(current.replaceServer(updated).select(existing.id))
            return@withLock Result.success(Unit)
        }
        val id = UUID.randomUUID().toString()
        val server = SavedServer(
            id = id,
            name = incomingConfig.visibleName(),
            config = incomingConfig.copy(displayName = incomingConfig.visibleName()),
        )
        credentials.setUuid(id, parsed.uuid)
        dataStore.saveSettings(
            current.copy(
                servers = current.servers + server,
                activeServerId = id,
                server = server.config,
            ),
        )
        Result.success(Unit)
    }

    suspend fun addSubscription(url: String): Result<Unit> {
        val trimmed = url.trim()
        val key = ConnectionIdentity.subscriptionKey(trimmed)
        if (dataStore.settings.first().subscriptions.any { ConnectionIdentity.subscriptionKey(it.url) == key }) {
            return Result.failure(DuplicateConnectionException())
        }
        val fetched = fetchParsed(trimmed).getOrElse { return Result.failure(it) }
        return mutex.withLock {
            val current = dataStore.settings.first()
            if (current.subscriptions.any { ConnectionIdentity.subscriptionKey(it.url) == key }) {
                return@withLock Result.failure(DuplicateConnectionException())
            }
            val subscriptionId = UUID.randomUUID().toString()
            val subscription = SavedSubscription(
                id = subscriptionId,
                name = SubscriptionParser.subscriptionName(trimmed),
                url = trimmed,
                lastUpdatedEpochMs = System.currentTimeMillis(),
            )
            val created = createSubscriptionServers(subscriptionId, fetched)
            if (created.isEmpty()) {
                return@withLock Result.failure(IllegalArgumentException(context.getString(R.string.subscription_no_vless)))
            }
            val first = created.first()
            dataStore.saveSettings(
                current.copy(
                    subscriptions = current.subscriptions + subscription,
                    servers = current.servers + created.map { it.server },
                    activeServerId = current.activeServerId ?: first.server.id,
                    server = current.activeServer()?.config ?: first.server.config,
                ),
            )
            Result.success(Unit)
        }
    }

    suspend fun refreshSubscription(id: String): Result<Unit> {
        val subscription = dataStore.settings.first().subscriptions.find { it.id == id }
            ?: return Result.failure(IllegalArgumentException(context.getString(R.string.subscription_not_found)))
        val fetched = fetchParsed(subscription.url).getOrElse { return Result.failure(it) }
        return mutex.withLock {
            val current = dataStore.settings.first()
            if (current.subscriptions.none { it.id == id }) {
                return@withLock Result.failure(IllegalArgumentException(context.getString(R.string.subscription_not_found)))
            }
            val oldServers = current.servers.filter { it.subscriptionId == id }
            val created = mergeSubscriptionServers(id, oldServers, fetched)
            if (created.isEmpty()) {
                return@withLock Result.failure(IllegalArgumentException(context.getString(R.string.subscription_no_vless)))
            }
            val leftover = oldServers.filter { old -> created.none { it.server.id == old.id } }
            leftover.forEach { credentials.removeUuid(it.id) }
            val remaining = current.servers.filterNot { it.subscriptionId == id } + created.map { it.server }
            val nextActive = remaining.find { it.id == current.activeServerId } ?: remaining.firstOrNull()
            dataStore.saveSettings(
                current.copy(
                    subscriptions = current.subscriptions.map {
                        if (it.id == id) it.copy(lastUpdatedEpochMs = System.currentTimeMillis()) else it
                    },
                    servers = remaining,
                    activeServerId = nextActive?.id,
                    server = nextActive?.config ?: VlessServerConfig(),
                ),
            )
            Result.success(Unit)
        }
    }

    suspend fun selectServer(id: String): Result<Unit> = mutex.withLock {
        val current = dataStore.settings.first()
        if (current.servers.none { it.id == id }) {
            return Result.failure(IllegalArgumentException(context.getString(R.string.server_not_found)))
        }
        saveSettings(current.select(id))
        Result.success(Unit)
    }

    suspend fun updateServer(
        id: String,
        name: String,
        address: String,
        port: Int,
        sni: String,
        flow: String,
        fingerprint: String,
        alpn: String,
        note: String,
    ): Result<Unit> = mutex.withLock {
        val current = dataStore.settings.first()
        val existing = current.servers.find { it.id == id }
            ?: return Result.failure(IllegalArgumentException(context.getString(R.string.server_not_found)))
        val trimmedAddress = address.trim()
        val updatedConfig = existing.config.copy(
            address = trimmedAddress,
            port = port,
            flow = flow.trim(),
            serverName = sni.trim().ifBlank { trimmedAddress },
            fingerprint = fingerprint.trim(),
            alpn = alpn.trim(),
            displayName = name.trim().ifBlank { trimmedAddress },
        )
        val updated = existing.copy(
            name = updatedConfig.displayName,
            config = updatedConfig,
            note = note.trim(),
        )
        saveSettings(current.replaceServer(updated))
        Result.success(Unit)
    }

    suspend fun updateSubscriptionNote(id: String, note: String): Result<Unit> = mutex.withLock {
        val current = dataStore.settings.first()
        val existing = current.subscriptions.find { it.id == id }
            ?: return Result.failure(IllegalArgumentException(context.getString(R.string.subscription_not_found)))
        saveSettings(current.replaceSubscription(existing.copy(note = note.trim())))
        Result.success(Unit)
    }

    suspend fun deleteServer(id: String): Result<Unit> = mutex.withLock {
        val current = dataStore.settings.first()
        if (current.servers.none { it.id == id }) {
            return Result.failure(IllegalArgumentException(context.getString(R.string.server_not_found)))
        }
        credentials.removeUuid(id)
        dataStore.saveSettings(current.withoutServer(id))
        Result.success(Unit)
    }

    suspend fun deleteSubscription(id: String): Result<Unit> = mutex.withLock {
        val current = dataStore.settings.first()
        current.servers.filter { it.subscriptionId == id }.forEach { credentials.removeUuid(it.id) }
        dataStore.saveSettings(current.withoutSubscription(id))
        Result.success(Unit)
    }

    suspend fun saveSettings(settings: LayerSettings) = dataStore.saveSettings(settings)

    suspend fun upsertAppRule(rule: AppRoutingRule) {
        val current = dataStore.appRules.first().toMutableList()
        val index = current.indexOfFirst { it.packageName == rule.packageName }
        if (index >= 0) current[index] = rule else current += rule
        dataStore.saveAppRules(current.sortedBy { it.appName.lowercase() })
    }

    suspend fun removeAppRule(packageName: String): AppRoutingRule? {
        val current = dataStore.appRules.first()
        val removed = current.firstOrNull { it.packageName == packageName }
        dataStore.saveAppRules(RoutingEngine.withoutAppRule(current, packageName))
        return removed
    }

    suspend fun upsertDomainRule(rule: DomainRoutingRule): Result<Unit> {
        val normalized = HostnameNormalizer.normalize(rule.domain)
        if (!normalized.isValid) {
            return Result.failure(IllegalArgumentException(normalized.error))
        }
        val clean = rule.copy(domain = normalized.domain)
        val current = dataStore.domainRules.first().toMutableList()
        val index = current.indexOfFirst { it.domain == clean.domain }
        if (index >= 0) current[index] = clean else current += clean
        dataStore.saveDomainRules(current.sortedBy { it.domain })
        return Result.success(Unit)
    }

    suspend fun removeDomainRule(domain: String): DomainRoutingRule? {
        val current = dataStore.domainRules.first()
        val removed = current.firstOrNull { it.domain == domain }
        dataStore.saveDomainRules(RoutingEngine.withoutDomainRule(current, domain))
        return removed
    }

    suspend fun restoreAppRule(rule: AppRoutingRule) = upsertAppRule(rule)

    suspend fun recommendedAppRulesToAdd(): List<AppRoutingRule> = withContext(Dispatchers.IO) {
        val existing = dataStore.appRules.first().map { it.packageName }.toSet()
        val installedLabels = buildMap {
            RecommendedApps.catalog.forEach { entry ->
                entry.packageNames.forEach { pkg ->
                    val label = installedLabel(pkg) ?: return@forEach
                    put(pkg, label)
                }
            }
        }
        RecommendedApps.rulesToAdd(installedLabels, existing)
    }

    suspend fun applyRecommendedApps() {
        addMissingAppRules(recommendedAppRulesToAdd())
        markRecommendedAppsPromptDone()
    }

    suspend fun declineRecommendedApps() {
        markRecommendedAppsPromptDone()
    }

    private suspend fun addMissingAppRules(rules: List<AppRoutingRule>) {
        if (rules.isEmpty()) return
        val current = dataStore.appRules.first().toMutableList()
        val existing = current.map { it.packageName }.toSet()
        val extra = rules.filter { it.packageName !in existing }
        if (extra.isEmpty()) return
        current += extra
        dataStore.saveAppRules(current.sortedBy { it.appName.lowercase() })
    }

    private suspend fun markRecommendedAppsPromptDone() {
        val current = dataStore.settings.first()
        if (!current.recommendedAppsPromptDone) {
            dataStore.saveSettings(current.copy(recommendedAppsPromptDone = true))
        }
    }

    suspend fun restoreDomainRule(rule: DomainRoutingRule) = upsertDomainRule(rule)

    suspend fun resetRouting() = dataStore.resetRouting()

    suspend fun resetAll() {
        mutex.withLock {
            dataStore.resetAll()
            credentials.clearAll()
        }
    }

    suspend fun buildConfig(
        resolvedServerIp: String? = null,
        localRuleSets: Map<String, String> = emptyMap(),
        remoteRuleSetFallback: Boolean = true,
    ): com.layer.core.config.ConfigGenerationResult {
        val snap = currentSnapshot()
        val serverId = snap.settings.activeServerId
        return SingBoxConfigGenerator.generate(
            uuid = credentials.getUuid(serverId),
            settings = snap.settings,
            appRules = snap.appRules,
            domainRules = snap.domainRules,
            ownPackageName = context.packageName,
            resolvedServerIp = resolvedServerIp,
            localRuleSets = localRuleSets,
            remoteRuleSetFallback = remoteRuleSetFallback,
            logLevel = "info",
        )
    }

    suspend fun installedApps(): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcher, 0)
        }
        resolved
            .map { it.activityInfo.applicationInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    icon = runCatching { info.loadIcon(pm) }.getOrNull(),
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    private data class CreatedServer(val server: SavedServer)

    private fun createSubscriptionServers(
        subscriptionId: String,
        links: List<ParsedVlessLink>,
    ): List<CreatedServer> {
        return links.mapNotNull { link ->
            val config = link.server ?: return@mapNotNull null
            val id = UUID.randomUUID().toString()
            credentials.setUuid(id, link.uuid)
            CreatedServer(
                SavedServer(
                    id = id,
                    name = config.visibleName(),
                    config = config.copy(displayName = config.visibleName()),
                    subscriptionId = subscriptionId,
                ),
            )
        }
    }

    private fun mergeSubscriptionServers(
        subscriptionId: String,
        oldServers: List<SavedServer>,
        links: List<ParsedVlessLink>,
    ): List<CreatedServer> {
        val unused = oldServers.toMutableList()
        return links.mapNotNull { link ->
            val config = link.server ?: return@mapNotNull null
            val matchIndex = unused.indexOfFirst { old ->
                credentials.getUuid(old.id).equals(link.uuid, ignoreCase = true) &&
                    old.config.address == config.address &&
                    old.config.port == config.port
            }
            val matched = if (matchIndex >= 0) unused.removeAt(matchIndex) else null
            val id = matched?.id ?: UUID.randomUUID().toString()
            credentials.setUuid(id, link.uuid)
            CreatedServer(
                SavedServer(
                    id = id,
                    name = config.visibleName(),
                    config = config.copy(displayName = config.visibleName()),
                    subscriptionId = subscriptionId,
                    note = matched?.note.orEmpty(),
                ),
            )
        }
    }

    private fun installedLabel(packageName: String): String? {
        val pm = context.packageManager
        return runCatching {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                pm.getApplicationInfo(packageName, 0)
            }
            info.loadLabel(pm).toString()
        }.getOrNull()
    }

    private suspend fun fetchParsed(url: String): Result<List<ParsedVlessLink>> = withContext(Dispatchers.IO) {
        val body = subscriptionClient.fetch(url).getOrElse { return@withContext Result.failure(it) }
        SubscriptionParser.parse(body)
    }
}
