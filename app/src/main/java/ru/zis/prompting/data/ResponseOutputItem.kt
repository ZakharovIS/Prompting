package ru.zis.prompting.data

import kotlinx.serialization.Serializable

@Serializable
data class ResponseOutputItem(
    val id: String? = null,
    val type: String? = null,   // "message"
    val role: String? = null,   // "assistant"
    val status: String? = null,
    val content: List<ResponseContentPart> = emptyList()
)