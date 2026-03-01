package ru.zis.prompting.agent

import android.os.SystemClock
import ru.zis.prompting.network.RouterAiApi
import ru.zis.prompting.data.InputMessage
import ru.zis.prompting.data.ResponsesRequest
import ru.zis.prompting.data.Usage

class ChatAgent(
    private val api: RouterAiApi,
    private val model: String = "openai/gpt-5.2",
    private val maxHistoryMessages: Int = 40,
    private var strategy: ContextStrategy = ContextStrategy.SLIDING_WINDOW
) {
    private val fullHistory = mutableListOf<InputMessage>()
    private val factsManager = StickyFactsManager(api = api, model = model)
    private val branchManager = BranchManager()

    private var cumulativeInputTokensSum: Int = 0
    private var cumulativeOutputTokensSum: Int = 0
    private var hasCumulativeTokens: Boolean = false

    fun clear() {
        fullHistory.clear()
        factsManager.clear()
        branchManager.clear()
        cumulativeInputTokensSum = 0
        cumulativeOutputTokensSum = 0
        hasCumulativeTokens = false
    }

    fun setStrategy(newStrategy: ContextStrategy) {
        strategy = newStrategy
    }

    fun currentStrategy(): ContextStrategy = strategy

    fun currentFacts(): Map<String, String> = factsManager.currentFacts()

    fun branchNames(): List<String> = branchManager.branchNames()

    fun checkpointNames(): List<String> = branchManager.checkpointNames()

    fun activeBranch(): String = branchManager.activeBranch()

    fun activeBranchHistory(): List<InputMessage> = branchManager.activeHistory()

    fun saveCheckpoint(name: String): Boolean = branchManager.saveCheckpoint(name)

    fun createBranch(checkpointName: String, branchName: String): Boolean =
        branchManager.createBranch(checkpointName, branchName)

    fun switchBranch(name: String): Boolean = branchManager.switchBranch(name)

    fun snapshotState(): AgentMemoryState = AgentMemoryState(
        strategy = strategy,
        fullHistory = fullHistory.toList(),
        facts = factsManager.snapshotFacts(),
        branches = branchManager.snapshotBranches(),
        checkpoints = branchManager.snapshotCheckpoints(),
        activeBranch = branchManager.activeBranch(),
        cumulativeInputTokens = if (hasCumulativeTokens) cumulativeInputTokensSum else null,
        cumulativeOutputTokens = if (hasCumulativeTokens) cumulativeOutputTokensSum else null
    )

    fun restoreState(state: AgentMemoryState) {
        strategy = state.strategy
        fullHistory.clear()
        fullHistory.addAll(state.fullHistory)
        factsManager.restoreFacts(state.facts)
        branchManager.restore(
            savedBranches = state.branches,
            savedCheckpoints = state.checkpoints,
            activeBranch = state.activeBranch,
            fallbackMainHistory = state.fullHistory
        )
        restoreCumulativeTokens(state.cumulativeInputTokens, state.cumulativeOutputTokens)
    }

    /** Восстанавливает накопленные токены сессии из сохранённого UI-состояния. */
    fun restoreCumulativeTokens(savedInputTokens: Int?, savedOutputTokens: Int?) {
        if (savedInputTokens == null && savedOutputTokens == null) {
            cumulativeInputTokensSum = 0
            cumulativeOutputTokensSum = 0
            hasCumulativeTokens = false
            return
        }

        cumulativeInputTokensSum = savedInputTokens?.coerceAtLeast(0) ?: 0
        cumulativeOutputTokensSum = savedOutputTokens?.coerceAtLeast(0) ?: 0
        hasCumulativeTokens = true
    }

    suspend fun send(userText: String, temperature: Float?): AgentTurn {
        val userMessage = InputMessage(role = "user", content = userText)
        addMessageToMemory(userMessage)

        if (strategy == ContextStrategy.STICKY_FACTS) {
            val factsUsage = factsManager.refreshFromDialog(
                dialogHistory = fullHistory,
                temperature = temperature
            )
            addToCumulative(factsUsage)
        }

        val start = SystemClock.elapsedRealtime()

        val resp = api.createResponse(
            ResponsesRequest(
                model = model,
                input = buildContextMessages(),
                stream = false,
                temperature = temperature
            )
        )

        val latencyMs = SystemClock.elapsedRealtime() - start

        if (resp.error != null) {
            throw IllegalStateException(resp.error.message ?: "RouterAI error")
        }

        val assistantText = resp.extractText().ifBlank { "(пустой ответ)" }

        addMessageToMemory(InputMessage(role = "assistant", content = assistantText))

        val apiUsage = resp.usage
        val currentRequestTokens = apiUsage?.inputTokens
        val modelResponseTokens = apiUsage?.outputTokens

        addToCumulative(apiUsage)

        val mergedUsage = (apiUsage ?: Usage()).copy(
            currentRequestTokens = currentRequestTokens,
            modelResponseTokens = modelResponseTokens,
            cumulativeInputTokens = if (hasCumulativeTokens) cumulativeInputTokensSum else null,
            cumulativeOutputTokens = if (hasCumulativeTokens) cumulativeOutputTokensSum else null
        )

        return AgentTurn(
            text = assistantText,
            latencyMs = latencyMs,
            usage = mergedUsage
        )
    }

    private fun addMessageToMemory(message: InputMessage) {
        when (strategy) {
            ContextStrategy.BRANCHING -> branchManager.appendToActive(message)
            else -> fullHistory += message
        }
    }

    private fun buildContextMessages(): List<InputMessage> {
        val windowSize = maxHistoryMessages.coerceAtLeast(1)
        return when (strategy) {
            ContextStrategy.SLIDING_WINDOW -> {
                fullHistory.takeLast(windowSize)
            }

            ContextStrategy.STICKY_FACTS -> {
                val recentMessages = fullHistory.takeLast(windowSize)
                val factsSystem = factsManager.buildFactsSystemMessage()
                listOfNotNull(factsSystem) + recentMessages
            }

            ContextStrategy.BRANCHING -> {
                branchManager.activeHistory().takeLast(windowSize)
            }
        }
    }

    private fun addToCumulative(usage: Usage?) {
        if (usage == null) return

        val input = usage.inputTokens
        val output = usage.outputTokens
        if (input == null && output == null) return

        cumulativeInputTokensSum += (input ?: 0)
        cumulativeOutputTokensSum += (output ?: 0)
        hasCumulativeTokens = true
    }
}

data class AgentMemoryState(
    val strategy: ContextStrategy,
    val fullHistory: List<InputMessage> = emptyList(),
    val facts: Map<String, String> = emptyMap(),
    val branches: Map<String, List<InputMessage>> = emptyMap(),
    val checkpoints: Map<String, List<InputMessage>> = emptyMap(),
    val activeBranch: String = BranchManager.MAIN_BRANCH,
    val cumulativeInputTokens: Int? = null,
    val cumulativeOutputTokens: Int? = null
)

data class AgentTurn(
    val text: String,
    val latencyMs: Long,
    val usage: Usage?
)