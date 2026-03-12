package com.jayathu.automata.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jayathu.automata.data.model.SavedLocation
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedLocationDao {
    @Query("SELECT * FROM saved_locations ORDER BY name ASC")
    fun getAll(): Flow<List<SavedLocation>>

    @Query("SELECT * FROM saved_locations WHERE id = :id")
    suspend fun getById(id: Long): SavedLocation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(location: SavedLocation): Long

    @Update
    suspend fun update(location: SavedLocation)

    @Delete
    suspend fun delete(location: SavedLocation)
}
