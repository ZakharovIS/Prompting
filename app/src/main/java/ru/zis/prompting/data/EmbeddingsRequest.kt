package ru.zis.prompting.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class EmbeddingsRequest(
    val model: String,
    val input: List<String>,
    @SerialName("encoding_format") val encodingFormat: String = "float"
)
