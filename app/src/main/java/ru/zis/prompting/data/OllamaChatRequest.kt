package ru.zis.prompting.data

import kotlinx.serialization.Serializable

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<InputMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null
)
