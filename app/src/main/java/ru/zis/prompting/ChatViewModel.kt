package ru.zis.prompting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zis.prompting.agent.ChatAgent
import ru.zis.prompting.data.Usage
import ru.zis.prompting.network.RouterAiApi
import ru.zis.prompting.network.RouterAiApiFactory

data class UiMessage(
    val role: String,              // "user" | "assistant"
    val text: String,
    val latencyMs: Long? = null,   // только для assistant
    val usage: Usage? = null       // только для assistant
)

class ChatViewModel : ViewModel() {
    var inputText by mutableStateOf("")
    var temperature by mutableFloatStateOf(0.7f)

    val messages = mutableStateListOf<UiMessage>()

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private val api: RouterAiApi = RouterAiApiFactory.create()
    private val agent = ChatAgent(api = api, model = "openai/gpt-5.2")

    fun clearChat() {
        agent.clear()
        messages.clear()
        error = null
        loading = false
    }

    fun send() {
        val text = inputText.trim()
        if (text.isBlank()) return
        sendText(text)
    }

    fun retryLastUser() {
        val lastUserText = messages.lastOrNull { it.role == "user" }?.text ?: return
        sendText(lastUserText)
    }

    private fun sendText(text: String) {
        if (loading) return

        error = null
        loading = true

        // UI: сразу добавляем user-сообщение
        messages += UiMessage(role = "user", text = text)

        // если отправили из поля ввода — очищаем
        if (inputText.trim() == text) inputText = ""

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val turn = agent.send(userText = text, temperature = temperature)

                withContext(Dispatchers.Main) {
                    messages += UiMessage(
                        role = "assistant",
                        text = turn.text,
                        latencyMs = turn.latencyMs,
                        usage = turn.usage
                    )
                    loading = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                    loading = false
                }
            }
        }
    }
}