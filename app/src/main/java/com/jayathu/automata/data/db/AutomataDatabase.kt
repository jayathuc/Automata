package com.jayathu.automata.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.jayathu.automata.data.model.SavedLocation
import com.jayathu.automata.data.model.TaskConfig

@Database(
    entities = [TaskConfig::class, SavedLocation::class],
    version = 1,
    exportSchema = false
)
abstract class AutomataDatabase : RoomDatabase() {
    abstract fun taskConfigDao(): TaskConfigDao
    abstract fun savedLocationDao(): SavedLocationDao

    companion object {
        @Volatile
        private var INSTANCE: AutomataDatabase? = null

        fun getInstance(context: Context): AutomataDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AutomataDatabase::class.java,
                    "automata_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
