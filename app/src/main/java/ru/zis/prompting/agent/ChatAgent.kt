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
    private val history = mutableListOf<InputMessage>()
    private var historyTokensSum: Int = 0
    private var hasHistoryTokens: Boolean = false

    fun clear() {
        history.clear()
        historyTokensSum = 0
        hasHistoryTokens = false
    }

    fun snapshotHistory(): List<InputMessage> = history.toList()

    /** Восстанавливает историю из сохранённого состояния (например, при перезапуске). */
    fun restoreHistory(saved: List<InputMessage>) {
        history.clear()
        history.addAll(saved)
    }

    /** Восстанавливает накопленные токены истории из сохранённого UI-состояния. */
    fun restoreHistoryTokens(savedHistoryTokens: Int?) {
        if (savedHistoryTokens == null) {
            historyTokensSum = 0
            hasHistoryTokens = false
            return
        }
        historyTokensSum = savedHistoryTokens.coerceAtLeast(0)
        hasHistoryTokens = true
    }

    suspend fun send(userText: String, temperature: Float?): AgentTurn {
        // 1) добавляем пользовательское сообщение в память
        history += InputMessage(role = "user", content = userText)
        trimHistoryIfNeeded()

        val start = SystemClock.elapsedRealtime()

        // 2) запрос с полным контекстом
        val resp = api.createResponse(
            ResponsesRequest(
                model = model,
                input = history,
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
        history += InputMessage(role = "assistant", content = assistantText)
        trimHistoryIfNeeded()

        val apiUsage = resp.usage
        val currentRequestTokens = apiUsage?.inputTokens
        val modelResponseTokens = apiUsage?.outputTokens
        val turnTotalTokens = apiUsage?.totalTokens ?: when {
            currentRequestTokens != null || modelResponseTokens != null ->
                (currentRequestTokens ?: 0) + (modelResponseTokens ?: 0)
            else -> null
        }

        if (turnTotalTokens != null) {
            historyTokensSum += turnTotalTokens
            hasHistoryTokens = true
        }

        val mergedUsage = (apiUsage ?: Usage()).copy(
            currentRequestTokens = currentRequestTokens,
            modelResponseTokens = modelResponseTokens,
            historyTokens = if (hasHistoryTokens) historyTokensSum else null
        )

        return AgentTurn(
            text = assistantText,
            latencyMs = latencyMs,
            usage = mergedUsage
        )
    }

    private fun trimHistoryIfNeeded() {
        if (history.size <= maxHistoryMessages) return
        val extra = history.size - maxHistoryMessages
        repeat(extra) { history.removeAt(0) }
    }
}

data class AgentTurn(
    val text: String,
    val latencyMs: Long,
    val usage: Usage?
)