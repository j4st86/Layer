package com.layer.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SettingsEntity::class,
        ServerEntity::class,
        SubscriptionEntity::class,
        AppRuleEntity::class,
        DomainRuleEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class LayerDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun servers(): ServerDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun appRules(): AppRuleDao
    abstract fun domainRules(): DomainRuleDao

    companion object {
        fun create(context: Context): LayerDatabase {
            return Room.databaseBuilder(context, LayerDatabase::class.java, "layer.db")
                .build()
        }
    }
}
