package com.layer.app.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "settings")
data class SettingsEntity(
    @PrimaryKey val id: Int = ROW_ID,
    val activeServerId: String? = null,
    val automaticRuleSetEnabled: Boolean = true,
    val ipv6Enabled: Boolean = false,
    val recommendedAppsPromptDone: Boolean = false,
    val autoSelectServerEnabled: Boolean = false,
    val autoSelectIntervalMinutes: Int = 25,
    val adBlockEnabled: Boolean = false,
) {
    companion object {
        const val ROW_ID = 1
    }
}

@Entity(tableName = "servers")
data class ServerEntity(
    @PrimaryKey val id: String,
    val name: String,
    val configJson: String,
    val subscriptionId: String?,
    val note: String,
)

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val lastUpdatedEpochMs: Long,
    val note: String,
)

@Entity(tableName = "app_rules")
data class AppRuleEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
    val mode: String,
)

@Entity(tableName = "domain_rules")
data class DomainRuleEntity(
    @PrimaryKey val domain: String,
    val mode: String,
)

@Entity(tableName = "ad_block_apps")
data class AdBlockAppEntity(
    @PrimaryKey val packageName: String,
    val appName: String,
)
