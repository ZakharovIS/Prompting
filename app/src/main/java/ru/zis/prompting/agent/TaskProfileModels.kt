package ru.zis.prompting.agent

import kotlinx.serialization.Serializable

enum class TaskProfileType {
    DEV,
    ANALYTICS,
    FREE
}

enum class TaskStage {
    IDLE,
    CLARIFICATION,
    PLANNING,
    PLAN_APPROVED,
    IMPLEMENTATION,
    REVIEW,
    DONE,
    DATA_COLLECTION,
    ANALYSIS,
    VALIDATION,
    REPORT,
    FREE
}

data class TaskProfile(
    val type: TaskProfileType,
    val orderedStages: List<TaskStage>,
    val allowedTransitions: Map<TaskStage, Set<TaskStage>>
)

@Serializable
data class TaskProfileClassificationResult(
    val profile: String = "FREE"
)

@Serializable
data class TransitionIntentResult(
    val transitionRequested: Boolean = false,
    val targetStage: String? = null
)

object TaskProfiles {

    val dev: TaskProfile = buildLinearProfile(
        type = TaskProfileType.DEV,
        stages = listOf(
            TaskStage.CLARIFICATION,
            TaskStage.PLANNING,
            TaskStage.PLAN_APPROVED,
            TaskStage.IMPLEMENTATION,
            TaskStage.REVIEW,
            TaskStage.DONE
        )
    )

    val analytics: TaskProfile = buildLinearProfile(
        type = TaskProfileType.ANALYTICS,
        stages = listOf(
            TaskStage.CLARIFICATION,
            TaskStage.DATA_COLLECTION,
            TaskStage.ANALYSIS,
            TaskStage.VALIDATION,
            TaskStage.REPORT,
            TaskStage.DONE
        )
    )

    val free: TaskProfile = TaskProfile(
        type = TaskProfileType.FREE,
        orderedStages = listOf(TaskStage.FREE),
        allowedTransitions = mapOf(TaskStage.FREE to emptySet())
    )

    fun of(type: TaskProfileType): TaskProfile = when (type) {
        TaskProfileType.DEV -> dev
        TaskProfileType.ANALYTICS -> analytics
        TaskProfileType.FREE -> free
    }

    fun initialStage(type: TaskProfileType): TaskStage =
        of(type).orderedStages.firstOrNull() ?: TaskStage.FREE

    fun allowedNext(type: TaskProfileType, current: TaskStage): Set<TaskStage> =
        of(type).allowedTransitions[current].orEmpty()

    fun canTransition(type: TaskProfileType, from: TaskStage, to: TaskStage): Boolean =
        to in allowedNext(type, from)

    fun parseProfile(raw: String?): TaskProfileType? {
        val normalized = raw?.trim()?.uppercase() ?: return null
        return TaskProfileType.entries.firstOrNull { it.name == normalized }
    }

    fun parseStage(raw: String?): TaskStage? {
        val normalized = raw?.trim()?.uppercase() ?: return null
        return TaskStage.entries.firstOrNull { it.name == normalized }
    }

    fun stageLabel(stage: TaskStage): String = when (stage) {
        TaskStage.IDLE -> "Ожидание"
        TaskStage.CLARIFICATION -> "Уточнение"
        TaskStage.PLANNING -> "Планирование"
        TaskStage.PLAN_APPROVED -> "План утверждён"
        TaskStage.IMPLEMENTATION -> "Реализация"
        TaskStage.REVIEW -> "Ревью"
        TaskStage.DONE -> "Готово"
        TaskStage.DATA_COLLECTION -> "Сбор данных"
        TaskStage.ANALYSIS -> "Анализ"
        TaskStage.VALIDATION -> "Валидация"
        TaskStage.REPORT -> "Отчёт"
        TaskStage.FREE -> "Свободный режим"
    }

    fun profileLabel(type: TaskProfileType): String = when (type) {
        TaskProfileType.DEV -> "Разработка"
        TaskProfileType.ANALYTICS -> "Аналитика"
        TaskProfileType.FREE -> "Свободный режим"
    }

    fun detectRequestedWorkStage(type: TaskProfileType, userText: String): TaskStage? {
        val lower = userText.lowercase()
        return when (type) {
            TaskProfileType.DEV -> when {
                listOf("реализац", "напиши код", "код", "имплемент", "программ").any { lower.contains(it) } -> TaskStage.IMPLEMENTATION
                listOf("финал", "заверши", "итог", "готовый результат", "завершён").any { lower.contains(it) } -> TaskStage.DONE
                else -> null
            }

            TaskProfileType.ANALYTICS -> when {
                listOf("собери данные", "сбор данных", "источники").any { lower.contains(it) } -> TaskStage.DATA_COLLECTION
                listOf("проанализируй", "анализ").any { lower.contains(it) } -> TaskStage.ANALYSIS
                listOf("валид", "проверь корректность", "верифиц").any { lower.contains(it) } -> TaskStage.VALIDATION
                listOf("отчёт", "финальный вывод", "резюме").any { lower.contains(it) } -> TaskStage.REPORT
                listOf("готово", "заверши", "финал").any { lower.contains(it) } -> TaskStage.DONE
                else -> null
            }

            TaskProfileType.FREE -> null
        }
    }

    private fun buildLinearProfile(type: TaskProfileType, stages: List<TaskStage>): TaskProfile {
        val transitions = mutableMapOf<TaskStage, Set<TaskStage>>()
        stages.forEachIndexed { index, stage ->
            transitions[stage] = if (index < stages.lastIndex) setOf(stages[index + 1]) else emptySet()
        }
        return TaskProfile(type = type, orderedStages = stages, allowedTransitions = transitions)
    }
}
