package com.jayathu.automata.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jayathu.automata.data.model.SavedLocation
import com.jayathu.automata.data.model.TaskConfig

@Database(
    entities = [TaskConfig::class, SavedLocation::class],
    version = 2,
    exportSchema = false
)
abstract class AutomataDatabase : RoomDatabase() {
    abstract fun taskConfigDao(): TaskConfigDao
    abstract fun savedLocationDao(): SavedLocationDao

    companion object {
        @Volatile
        private var INSTANCE: AutomataDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE task_configs ADD COLUMN roundTrip INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getInstance(context: Context): AutomataDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutomataDatabase::class.java,
                    "automata_db"
                ).addMigrations(MIGRATION_1_2)
                .build().also { INSTANCE = it }
            }
        }
    }
}
