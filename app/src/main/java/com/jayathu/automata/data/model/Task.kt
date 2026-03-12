package com.jayathu.automata.data.model

data class Task(
    val config: TaskConfig,
    val status: TaskStatus = TaskStatus.IDLE,
    val pickMePrice: String? = null,
    val uberPrice: String? = null,
    val chosenApp: RideApp? = null,
    val errorMessage: String? = null
)

enum class TaskStatus {
    IDLE,
    RUNNING,
    COMPARING,
    BOOKING,
    COMPLETED,
    ERROR
}
