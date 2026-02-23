package ru.zis.prompting.data

import kotlinx.serialization.Serializable

@Serializable
data class InputMessage(
    val role: String,
    val content: String
)