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

    fun clear() {
        history.clear()
    }

    fun snapshotHistory(): List<InputMessage> = history.toList()

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

        return AgentTurn(
            text = assistantText,
            latencyMs = latencyMs,
            usage = resp.usage
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