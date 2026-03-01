package ru.zis.prompting.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import ru.zis.prompting.data.InputMessage
import ru.zis.prompting.data.ResponsesRequest
import ru.zis.prompting.data.Usage
import ru.zis.prompting.network.RouterAiApi

class StickyFactsManager(
    private val api: RouterAiApi,
    private val model: String
) {
    private val facts = linkedMapOf<String, String>()
    private val json = Json { ignoreUnknownKeys = true }

    fun clear() {
        facts.clear()
    }

    fun snapshotFacts(): Map<String, String> = facts.toMap()

    fun restoreFacts(saved: Map<String, String>) {
        facts.clear()
        facts.putAll(saved.filter { it.key.isNotBlank() && it.value.isNotBlank() })
    }

    fun currentFacts(): Map<String, String> = facts.toMap()

    fun buildFactsSystemMessage(): InputMessage? {
        if (facts.isEmpty()) return null

        val content = buildString {
            appendLine("Важные факты из диалога (актуальное состояние):")
            facts.forEach { (key, value) ->
                appendLine("- $key: $value")
            }
            append("Используй эти факты как контекст и не противоречь им без явного уточнения пользователя.")
        }
        return InputMessage(role = "system", content = content)
    }

    suspend fun refreshFromDialog(
        dialogHistory: List<InputMessage>,
        temperature: Float?
    ): Usage? {
        if (dialogHistory.isEmpty()) return null

        val factsJson = if (facts.isEmpty()) "{}" else json.encodeToString(facts)
        val dialog = dialogHistory.joinToString("\n") { "${it.role}: ${it.content}" }

        val prompt = """
            Обнови JSON-словарь фактов по диалогу.
            Нужны только важные, устойчивые факты:
            - цель пользователя,
            - ограничения,
            - предпочтения,
            - принятые решения,
            - договорённости.

            Правила:
            1) Верни ТОЛЬКО валидный JSON-объект формата {"ключ":"значение"}.
            2) Значения должны быть короткими и по делу.
            3) Если факт устарел, обнови его или удали.
            4) Не добавляй информацию, которой нет в диалоге.

            Текущие facts JSON:
            $factsJson

            Диалог:
            $dialog
        """.trimIndent()

        val response = api.createResponse(
            ResponsesRequest(
                model = model,
                input = listOf(InputMessage(role = "user", content = prompt)),
                stream = false,
                temperature = temperature
            )
        )

        if (response.error != null) {
            throw IllegalStateException(response.error.message ?: "RouterAI facts update error")
        }

        val responseText = response.extractText().trim()
        val parsed = parseFactsJson(responseText)
        if (parsed != null) {
            facts.clear()
            facts.putAll(parsed)
        }

        return response.usage
    }

    private fun parseFactsJson(raw: String): Map<String, String>? {
        val candidate = extractJsonObject(raw) ?: return null
        return runCatching {
            val obj = json.parseToJsonElement(candidate).jsonObject
            obj.mapNotNull { (k, v) ->
                val value = (v as? JsonPrimitive)?.content?.trim()
                val key = k.trim()
                if (key.isBlank() || value.isNullOrBlank()) null else key to value
            }.toMap(linkedMapOf())
        }.getOrNull()
    }

    private fun extractJsonObject(text: String): String? {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return text.substring(start, end + 1)
    }
}
