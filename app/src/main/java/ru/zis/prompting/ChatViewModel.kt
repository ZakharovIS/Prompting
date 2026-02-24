package ru.zis.prompting

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zis.prompting.agent.ChatAgent
import ru.zis.prompting.data.Usage
import ru.zis.prompting.db.ChatRepository
import ru.zis.prompting.network.RouterAiApiFactory

data class UiMessage(
    val role: String,              // "user" | "assistant"
    val text: String,
    val latencyMs: Long? = null,   // только для assistant
    val usage: Usage? = null       // только для assistant
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    var inputText by mutableStateOf("")
    var temperature by mutableFloatStateOf(0.7f)

    val messages = mutableStateListOf<UiMessage>()

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** true пока идёт начальная загрузка истории из БД */
    var historyLoading by mutableStateOf(true)
        private set

    private val api = RouterAiApiFactory.create()
    private val agent = ChatAgent(api = api, model = "openai/gpt-5.2")

    private val repository: ChatRepository =
        (application as App).chatRepository

    init {
        loadHistory()
    }

    // ─── Загрузка истории из БД ───────────────────────────────────────────

    private fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = repository.load()
            withContext(Dispatchers.Main) {
                if (state != null) {
                    messages.addAll(state.uiMessages)
                    agent.restoreHistory(state.agentHistory)
                }
                historyLoading = false
            }
        }
    }

    // ─── Публичное API ────────────────────────────────────────────────────

    fun clearChat() {
        agent.clear()
        messages.clear()
        error = null
        loading = false
        viewModelScope.launch(Dispatchers.IO) { repository.clear() }
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

        messages += UiMessage(role = "user", text = text)

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

                // Сохраняем после успешного ответа
                repository.save(
                    uiMessages = messages.toList(),
                    agentHistory = agent.snapshotHistory()
                )
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                    loading = false
                }
            }
        }
    }
}
