package ru.zis.prompting.db

import kotlinx.serialization.json.Json
import ru.zis.prompting.UiMessage
import ru.zis.prompting.agent.AgentMemoryState
import ru.zis.prompting.agent.ContextStrategy
import ru.zis.prompting.data.InputMessage

/**
 * Репозиторий для сохранения и загрузки состояния чата из Room.
 * Использует kotlinx.serialization для JSON.
 */
class ChatRepository(private val dao: ChatSessionDao) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Сохраняет состояние конкретной стратегии. */
    suspend fun save(
        strategy: ContextStrategy,
        uiMessages: List<UiMessage>,
        memory: AgentMemoryState
    ) {
        val entity = ChatSessionEntity(
            sessionId = strategy.sessionId,
            messagesJson = json.encodeToString(uiMessages.map { it.toSerializable() }),
            agentHistoryJson = json.encodeToString(memory.fullHistory),
            factsJson = json.encodeToString(memory.facts),
            branchesJson = json.encodeToString(
                BranchingStateSerializable(
                    branches = memory.branches,
                    checkpoints = memory.checkpoints
                )
            ),
            activeBranch = memory.activeBranch,
            strategyName = strategy.name,
            updatedAt = System.currentTimeMillis()
        )
        dao.upsert(entity)
    }

    /** Загружает состояние конкретной стратегии, или null если ничего нет. */
    suspend fun load(strategy: ContextStrategy): ChatState? {
        val entity = dao.loadById(strategy.sessionId) ?: return null
        return try {
            val uiMessages = json
                .decodeFromString<List<UiMessageSerializable>>(entity.messagesJson)
                .map { it.toUiMessage() }

            val fullHistory = json.decodeFromString<List<InputMessage>>(entity.agentHistoryJson)
            val facts = json.decodeFromString<Map<String, String>>(entity.factsJson)

            val branching = json.decodeFromString<BranchingStateSerializable>(entity.branchesJson)
            val strategyFromDb = ContextStrategy.fromName(entity.strategyName)

            ChatState(
                uiMessages = uiMessages,
                strategy = strategyFromDb,
                memory = AgentMemoryState(
                    strategy = strategyFromDb,
                    fullHistory = fullHistory,
                    facts = facts,
                    branches = branching.branches,
                    checkpoints = branching.checkpoints,
                    activeBranch = entity.activeBranch
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Удаляет сохранённую сессию стратегии. */
    suspend fun clear(strategy: ContextStrategy) {
        dao.deleteById(strategy.sessionId)
    }

    /** Удаляет все стратегии (полный сброс). */
    suspend fun clear() {
        dao.deleteAll()
    }
}

data class ChatState(
    val uiMessages: List<UiMessage>,
    val strategy: ContextStrategy,
    val memory: AgentMemoryState
)

@kotlinx.serialization.Serializable
private data class BranchingStateSerializable(
    val branches: Map<String, List<InputMessage>> = emptyMap(),
    val checkpoints: Map<String, List<InputMessage>> = emptyMap()
)
