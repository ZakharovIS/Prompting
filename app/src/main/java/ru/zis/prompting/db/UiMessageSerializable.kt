package ru.zis.prompting.db

import kotlinx.serialization.Serializable
import ru.zis.prompting.UiMessage
import ru.zis.prompting.data.Usage

/**
 * Сериализуемая копия UiMessage для хранения в JSON.
 * Usage уже помечен @Serializable, поэтому используем его напрямую.
 */
@Serializable
data class UiMessageSerializable(
    val role: String,
    val text: String,
    val latencyMs: Long? = null,
    val usage: Usage? = null
)

fun UiMessage.toSerializable() = UiMessageSerializable(
    role = role,
    text = text,
    latencyMs = latencyMs,
    usage = usage
)

fun UiMessageSerializable.toUiMessage() = UiMessage(
    role = role,
    text = text,
    latencyMs = latencyMs,
    usage = usage
)
