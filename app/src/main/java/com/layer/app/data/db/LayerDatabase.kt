package com.layer.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        SettingsEntity::class,
        ServerEntity::class,
        SubscriptionEntity::class,
        AppRuleEntity::class,
        DomainRuleEntity::class,
        AdBlockAppEntity::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class LayerDatabase : RoomDatabase() {
    abstract fun settings(): SettingsDao
    abstract fun servers(): ServerDao
    abstract fun subscriptions(): SubscriptionDao
    abstract fun appRules(): AppRuleDao
    abstract fun domainRules(): DomainRuleDao
    abstract fun adBlockApps(): AdBlockAppDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `ad_block_apps` " +
                        "(`packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, " +
                        "PRIMARY KEY(`packageName`))",
                )
            }
        }

        fun create(context: Context): LayerDatabase {
            return Room.databaseBuilder(context, LayerDatabase::class.java, "layer.db")
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
