package ru.zis.prompting.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmbeddingsResponse(
    val data: List<EmbeddingItem> = emptyList(),
    val error: RouterAiError? = null
)

@Serializable
data class EmbeddingItem(
    val embedding: List<Double> = emptyList(),
    val index: Int? = null,
    @SerialName("object") val objectType: String? = null
)
