package com.jayathu.automata.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jayathu.automata.data.model.TaskConfig
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskConfigDao {
    @Query("SELECT * FROM task_configs ORDER BY id DESC")
    fun getAll(): Flow<List<TaskConfig>>

    @Query("SELECT * FROM task_configs WHERE id = :id")
    suspend fun getById(id: Long): TaskConfig?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(config: TaskConfig): Long

    @Update
    suspend fun update(config: TaskConfig)

    @Delete
    suspend fun delete(config: TaskConfig)
}
