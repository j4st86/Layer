package com.layer.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SettingsDao {
    @Query("SELECT * FROM settings WHERE id = 1")
    fun observe(): Flow<SettingsEntity?>

    @Query("SELECT * FROM settings WHERE id = 1")
    suspend fun get(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SettingsEntity)
}

@Dao
interface ServerDao {
    @Query("SELECT * FROM servers")
    fun observeAll(): Flow<List<ServerEntity>>

    @Query("SELECT * FROM servers")
    suspend fun getAll(): List<ServerEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<ServerEntity>)

    @Query("DELETE FROM servers")
    suspend fun deleteAll()
}

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions")
    suspend fun getAll(): List<SubscriptionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SubscriptionEntity>)

    @Query("DELETE FROM subscriptions")
    suspend fun deleteAll()
}

@Dao
interface AppRuleDao {
    @Query("SELECT * FROM app_rules ORDER BY appName COLLATE NOCASE")
    fun observeAll(): Flow<List<AppRuleEntity>>

    @Query("SELECT * FROM app_rules")
    suspend fun getAll(): List<AppRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<AppRuleEntity>)

    @Query("DELETE FROM app_rules")
    suspend fun deleteAll()
}

@Dao
interface DomainRuleDao {
    @Query("SELECT * FROM domain_rules ORDER BY domain COLLATE NOCASE")
    fun observeAll(): Flow<List<DomainRuleEntity>>

    @Query("SELECT * FROM domain_rules")
    suspend fun getAll(): List<DomainRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<DomainRuleEntity>)

    @Query("DELETE FROM domain_rules")
    suspend fun deleteAll()
}
