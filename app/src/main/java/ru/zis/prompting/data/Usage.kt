package ru.zis.prompting.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
    /** Токены текущего запроса из API (input_tokens). */
    @SerialName("current_request_tokens") val currentRequestTokens: Int? = null,
    /** Токены ответа модели за текущий ход из API (output_tokens). */
    @SerialName("model_response_tokens") val modelResponseTokens: Int? = null,
    /** Накопленная сумма токенов по всей истории чата на основе API usage. */
    @SerialName("history_tokens") val historyTokens: Int? = null
)