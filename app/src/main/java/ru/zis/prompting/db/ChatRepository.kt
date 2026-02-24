package ru.zis.prompting.db

import kotlinx.serialization.json.Json
import ru.zis.prompting.UiMessage
import ru.zis.prompting.data.InputMessage

/**
 * Репозиторий для сохранения и загрузки состояния чата из Room.
 * Использует kotlinx.serialization для JSON.
 */
class ChatRepository(private val dao: ChatSessionDao) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Сохраняет текущее состояние UI-сообщений и истории агента. */
    suspend fun save(uiMessages: List<UiMessage>, agentHistory: List<InputMessage>) {
        val entity = ChatSessionEntity(
            sessionId = 1,
            messagesJson = json.encodeToString(uiMessages.map { it.toSerializable() }),
            agentHistoryJson = json.encodeToString(agentHistory),
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(entity)
    }

    /** Загружает сохранённое состояние, или null если ничего нет. */
    suspend fun load(): ChatState? {
        val entity = dao.loadCurrent() ?: return null
        if (entity.messagesJson == "[]") return null
        return try {
            val uiMessages = json
                .decodeFromString<List<UiMessageSerializable>>(entity.messagesJson)
                .map { it.toUiMessage() }
            val agentHistory = json
                .decodeFromString<List<InputMessage>>(entity.agentHistoryJson)
            ChatState(uiMessages, agentHistory)
        } catch (e: Exception) {
            null
        }
    }

    /** Удаляет сохранённую сессию (сброс чата). */
    suspend fun clear() {
        dao.deleteCurrent()
    }
}

data class ChatState(
    val uiMessages: List<UiMessage>,
    val agentHistory: List<InputMessage>
)
