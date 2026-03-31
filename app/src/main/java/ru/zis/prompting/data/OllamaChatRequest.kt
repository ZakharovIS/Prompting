package ru.zis.prompting.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OllamaChatRequest(
    val model: String,
    val messages: List<InputMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null,
    val options: OllamaOptions? = null
)

@Serializable
data class OllamaOptions(
    @SerialName("num_ctx")
    val numCtx: Int? = null,
    @SerialName("num_predict")
    val numPredict: Int? = null,
    @SerialName("temperature")
    val temperature: Float? = null
)
