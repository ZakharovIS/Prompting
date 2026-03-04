package ru.zis.prompting.profile

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class UserProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val style: String = "",
    val constraints: String = "",
    val context: String = ""
)
