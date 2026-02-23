package ru.zis.prompting.data

import kotlinx.serialization.Serializable

@Serializable
data class Reasoning(
    val effort: String? = null // "none", "low", "medium", "high" — если поддерживается
)