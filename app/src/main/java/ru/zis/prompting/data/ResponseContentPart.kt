package ru.zis.prompting.data

import kotlinx.serialization.Serializable

@Serializable
data class ResponseContentPart(
    val type: String? = null,   // "output_text"
    val text: String? = null
)