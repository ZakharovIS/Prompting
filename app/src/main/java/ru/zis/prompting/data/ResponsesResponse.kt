package ru.zis.prompting.data

import kotlinx.serialization.Serializable

@Serializable
data class ResponsesResponse(
    val output: List<ResponseOutputItem> = emptyList(),
    val usage: Usage? = null,
    val error: RouterAiError? = null
) {
    fun extractText(): String =
        output
            .asSequence()
            .filter { it.type == "message" }
            .flatMap { it.content.asSequence() }
            .filter { part ->
                val t = part.type
                t == "output_text" || t == "text"
            }
            .mapNotNull { it.text }
            .joinToString(separator = "")
}