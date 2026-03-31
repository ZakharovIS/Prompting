package ru.zis.prompting.db

import kotlinx.serialization.json.Json
import ru.zis.prompting.UiMessage
import ru.zis.prompting.agent.LongTermMemoryItem
import ru.zis.prompting.agent.WorkingMemory
import ru.zis.prompting.data.InputMessage

/**
 * Репозиторий для сохранения и загрузки состояния чата из Room.
 * Использует kotlinx.serialization для JSON.
 */
class ChatRepository(
    private val chatDao: ChatSessionDao,
    private val longTermDao: LongTermMemoryDao
) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Сохраняет текущее состояние UI-сообщений, истории агента, summary и рабочей памяти. */
    suspend fun save(
        uiMessages: List<UiMessage>,
        agentHistory: List<InputMessage>,
        summary: String?,
        workingMemory: WorkingMemory
    ) {
        val entity = ChatSessionEntity(
            sessionId = 1,
            messagesJson = json.encodeToString(uiMessages.map { it.toSerializable() }),
            agentHistoryJson = json.encodeToString(agentHistory),
            summaryJson = summary.orEmpty(),
            workingMemoryJson = json.encodeToString(workingMemory),
            updatedAt = System.currentTimeMillis()
        )
        chatDao.upsert(entity)
    }

    /** Полностью пересохраняет долговременную память. */
    suspend fun saveLongTermMemory(items: List<LongTermMemoryItem>) {
        longTermDao.clearAll()
        if (items.isEmpty()) return
        longTermDao.insertAll(items.map { it.toEntity() })
    }

    /** Загружает сохранённое состояние, или null если ничего нет. */
    suspend fun load(): ChatState? {
        val entity = chatDao.loadCurrent() ?: return null
        return try {
            val uiMessages = json
                .decodeFromString<List<UiMessageSerializable>>(entity.messagesJson)
                .map { it.toUiMessage() }
            val agentHistory = json
                .decodeFromString<List<InputMessage>>(entity.agentHistoryJson)
            val workingMemory = runCatching {
                json.decodeFromString<WorkingMemory>(entity.workingMemoryJson)
            }.getOrElse { WorkingMemory() }

            ChatState(
                uiMessages = uiMessages,
                agentHistory = agentHistory,
                summary = entity.summaryJson.takeIf { it.isNotBlank() },
                workingMemory = workingMemory
            )
        } catch (e: Exception) {
            null
        }
    }

    suspend fun loadLongTermMemory(): List<LongTermMemoryItem> =
        longTermDao.getAll().map { it.toModel() }

    /** Удаляет сохранённую сессию (сброс чата). */
    suspend fun clear() {
        chatDao.deleteCurrent()
    }
}

data class ChatState(
    val uiMessages: List<UiMessage>,
    val agentHistory: List<InputMessage>,
    val summary: String?,
    val workingMemory: WorkingMemory
)

private fun LongTermMemoryItem.toEntity() = LongTermMemoryEntity(
    category = category,
    content = content,
    createdAt = createdAt
)

private fun LongTermMemoryEntity.toModel() = LongTermMemoryItem(
    category = category,
    content = content,
    createdAt = createdAt
)
