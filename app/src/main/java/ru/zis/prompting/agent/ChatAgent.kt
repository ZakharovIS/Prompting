package ru.zis.prompting.agent

import android.os.SystemClock
import ru.zis.prompting.network.RouterAiApi
import ru.zis.prompting.data.InputMessage
import ru.zis.prompting.data.ResponsesRequest
import ru.zis.prompting.data.Usage

class ChatAgent(
    private val api: RouterAiApi,
    private val model: String = "openai/gpt-5.2",
    private val maxHistoryMessages: Int = 40
) {
    private val fullHistory = mutableListOf<InputMessage>()
    private var summary: String? = null
    private var summarizedMessagesCount: Int = 0

    private var cumulativeInputTokensSum: Int = 0
    private var cumulativeOutputTokensSum: Int = 0
    private var hasCumulativeTokens: Boolean = false

    companion object {
        const val RECENT_MESSAGES_COUNT = 5
        const val SUMMARY_BATCH_SIZE = 10
        private const val SUMMARY_ROLE = "system"
    }

    fun clear() {
        fullHistory.clear()
        summary = null
        summarizedMessagesCount = 0
        cumulativeInputTokensSum = 0
        cumulativeOutputTokensSum = 0
        hasCumulativeTokens = false
    }

    fun snapshotHistory(): List<InputMessage> = fullHistory.toList()

    fun snapshotSummary(): String? = summary

    /** Восстанавливает историю из сохранённого состояния (например, при перезапуске). */
    fun restoreHistory(saved: List<InputMessage>, savedSummary: String?) {
        fullHistory.clear()
        fullHistory.addAll(saved)
        summary = savedSummary?.takeIf { it.isNotBlank() }
        summarizedMessagesCount = if (summary != null) {
            (fullHistory.size - RECENT_MESSAGES_COUNT).coerceAtLeast(0)
        } else {
            0
        }
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
        // 1) добавляем пользовательское сообщение в память
        fullHistory += InputMessage(role = "user", content = userText)
        maybeRefreshSummary(temperature)

        val start = SystemClock.elapsedRealtime()

        // 2) запрос с управляемым контекстом: summary + последние N сообщений
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
            // если ошибка — откатывать history или оставлять? обычно лучше оставлять user-turn,
            // чтобы пользователь мог повторить/исправить. Здесь оставляем.
            throw IllegalStateException(resp.error.message ?: "RouterAI error")
        }

        val assistantText = resp.extractText().ifBlank { "(пустой ответ)" }

        // 3) добавляем ответ ассистента в память
        fullHistory += InputMessage(role = "assistant", content = assistantText)
        maybeRefreshSummary(temperature)

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

    private suspend fun maybeRefreshSummary(temperature: Float?) {
        val oldMessagesCount = (fullHistory.size - RECENT_MESSAGES_COUNT).coerceAtLeast(0)
        if (oldMessagesCount <= 0) {
            summary = null
            summarizedMessagesCount = 0
            return
        }

        val shouldRefresh = summary == null ||
            (oldMessagesCount - summarizedMessagesCount) >= SUMMARY_BATCH_SIZE

        if (!shouldRefresh) return

        val oldMessages = fullHistory.take(oldMessagesCount)
        summary = generateSummary(oldMessages, temperature)
        summarizedMessagesCount = oldMessagesCount
    }

    private fun buildContextMessages(): List<InputMessage> {
        val recentMessages = fullHistory.takeLast(maxHistoryMessages.coerceAtMost(RECENT_MESSAGES_COUNT))
        val summaryMessage = summary?.takeIf { it.isNotBlank() }?.let {
            InputMessage(
                role = SUMMARY_ROLE,
                content = "Краткое summary предыдущего диалога:\n$it"
            )
        }
        return listOfNotNull(summaryMessage) + recentMessages
    }

    private suspend fun generateSummary(messages: List<InputMessage>, temperature: Float?): String {
        val dialog = messages.joinToString(separator = "\n") { msg ->
            "${msg.role}: ${msg.content}"
        }

        val summaryPrompt = """
            Суммаризируй диалог кратко и по делу.
            Сохрани:
            - ключевые факты и договорённости,
            - важные требования пользователя,
            - открытые вопросы,
            - технический контекст.

            Не добавляй информацию, которой нет в диалоге.
            Верни только summary без вводных фраз.

            Диалог:
            $dialog
        """.trimIndent()

        val summaryResponse = api.createResponse(
            ResponsesRequest(
                model = model,
                input = listOf(InputMessage(role = "user", content = summaryPrompt)),
                stream = false,
                temperature = temperature
            )
        )

        if (summaryResponse.error != null) {
            throw IllegalStateException(summaryResponse.error.message ?: "RouterAI summary error")
        }

        addToCumulative(summaryResponse.usage)

        return summaryResponse.extractText().ifBlank { "Краткое summary недоступно." }
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

data class AgentTurn(
    val text: String,
    val latencyMs: Long,
    val usage: Usage?
)