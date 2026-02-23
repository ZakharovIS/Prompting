package ru.zis.prompting.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ResponsesRequest(
    val model: String,
    val input: List<InputMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    val reasoning: Reasoning? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null
)