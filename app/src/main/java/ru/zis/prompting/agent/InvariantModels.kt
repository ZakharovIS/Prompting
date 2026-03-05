package ru.zis.prompting.agent

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class InvariantItem(
    val id: String = UUID.randomUUID().toString(),
    val rule: String,
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class InvariantValidationResult(
    val violated: Boolean = false,
    val rule: String? = null,
    val explanation: String? = null
)
