package ru.zis.prompting.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Одна запись в таблице — весь диалог (список сообщений) сериализован в JSON.
 * sessionId = 1 — «текущий» диалог. При сбросе чата запись обновляется (upsert).
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val sessionId: Int = 1,
    /** JSON-массив объектов UiMessage */
    val messagesJson: String = "[]",
    /** JSON-массив объектов InputMessage (история агента) */
    val agentHistoryJson: String = "[]",
    val updatedAt: Long = System.currentTimeMillis()
)
