package ru.zis.prompting.data

import kotlinx.serialization.Serializable

@Serializable
data class RouterAiError(
    val message: String? = null,
    val type: String? = null
)