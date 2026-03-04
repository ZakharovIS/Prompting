package ru.zis.prompting.task

import ru.zis.prompting.UiMessage

enum class TaskStage {
    IDLE,
    PLANNING,
    EXECUTION,
    VALIDATION,
    DONE
}

enum class TaskStatus {
    ACTIVE,
    PAUSED
}

data class StageEntry(
    val stage: TaskStage,
    val summary: String,
    val messages: List<UiMessage>
)

data class TaskState(
    val stage: TaskStage = TaskStage.IDLE,
    val status: TaskStatus = TaskStatus.ACTIVE,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val expectedAction: String = "Введите задачу для старта",
    val taskDescription: String = "",
    val stageLog: List<StageEntry> = emptyList()
)
