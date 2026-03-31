package ru.zis.prompting.agent

import kotlinx.serialization.Serializable

@Serializable
data class WorkingMemory(
    val goal: String? = null,
    val clarifications: List<String> = emptyList(),
    val constraints: List<String> = emptyList(),
    val keyFacts: List<String> = emptyList(),
    val openQuestions: List<String> = emptyList(),
    val taskProfileType: String? = null,
    val currentStage: String? = null
)

@Serializable
data class LongTermMemoryItem(
    val category: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
