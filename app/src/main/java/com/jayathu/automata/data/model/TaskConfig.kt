package com.jayathu.automata.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "task_configs")
data class TaskConfig(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pickupAddress: String,
    val destinationAddress: String,
    val rideType: String = "Bike",
    val enablePickMe: Boolean = true,
    val enableUber: Boolean = true,
    val decisionMode: DecisionMode = DecisionMode.CHEAPEST
)

enum class DecisionMode {
    CHEAPEST,
    FASTEST
}
