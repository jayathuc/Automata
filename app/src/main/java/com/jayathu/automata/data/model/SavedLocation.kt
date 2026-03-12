package com.jayathu.automata.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_locations")
data class SavedLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)
