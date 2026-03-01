package ru.zis.prompting.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Одна запись в таблице — весь диалог (список сообщений) сериализован в JSON.
 * sessionId = 1 — «текущий» диалог. При сбросе чата запись обновляется (upsert).
 */
@Entity(tableName = "chat_sessions")
data class ChatSessionEntity(
    @PrimaryKey val sessionId: Int = 0,
    /** JSON-массив объектов UiMessage */
    val messagesJson: String = "[]",
    /** JSON-массив объектов InputMessage (история агента) */
    val agentHistoryJson: String = "[]",
    /** Legacy поле, оставлено для совместимости миграций. */
    val summaryJson: String = "",
    /** JSON-словарь sticky facts */
    val factsJson: String = "{}",
    /** JSON-объект branching состояния: branches + checkpoints */
    val branchesJson: String = "{}",
    /** Имя активной ветки в branching-режиме */
    val activeBranch: String = "main",
    /** Название стратегии для удобной отладки */
    val strategyName: String = "SLIDING_WINDOW",
    val updatedAt: Long = System.currentTimeMillis()
)
