package ru.zis.prompting

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zis.prompting.agent.ChatAgent
import ru.zis.prompting.agent.ContextStrategy
import ru.zis.prompting.data.InputMessage
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
    var temperature by mutableFloatStateOf(1.0f)

    val messages = mutableStateListOf<UiMessage>()

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** true пока идёт начальная загрузка истории из БД */
    var historyLoading by mutableStateOf(true)
        private set

    var strategy by mutableStateOf(ContextStrategy.SLIDING_WINDOW)
        private set

    var facts by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    var branchNames by mutableStateOf<List<String>>(listOf("main"))
        private set

    var checkpointNames by mutableStateOf<List<String>>(emptyList())
        private set

    var activeBranch by mutableStateOf("main")
        private set

    val model = "deepseek/deepseek-v3.2"

    private val api = RouterAiApiFactory.create()
    private val agent = ChatAgent(api = api, model = model)

    private val repository: ChatRepository =
        (application as App).chatRepository

    init {
        loadHistory()
    }

    // ─── Загрузка истории из БД ───────────────────────────────────────────

    private fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = repository.load(strategy)
            withContext(Dispatchers.Main) {
                if (state != null) {
                    strategy = state.strategy
                    messages.addAll(state.uiMessages)
                    agent.restoreState(state.memory)
                } else {
                    agent.setStrategy(strategy)
                }
                refreshDerivedState()
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
        strategy = ContextStrategy.SLIDING_WINDOW
        refreshDerivedState()
        viewModelScope.launch(Dispatchers.IO) { repository.clear() }
    }

    fun switchStrategy(newStrategy: ContextStrategy) {
        if (loading || historyLoading || newStrategy == strategy) return

        loading = true
        error = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                saveCurrentStrategyState()

                val loaded = repository.load(newStrategy)
                withContext(Dispatchers.Main) {
                    strategy = newStrategy
                    messages.clear()

                    if (loaded != null) {
                        agent.restoreState(loaded.memory.copy(strategy = newStrategy))
                        messages.addAll(loaded.uiMessages)
                    } else {
                        agent.clear()
                        agent.setStrategy(newStrategy)
                    }

                    refreshDerivedState()
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

    fun saveCheckpoint(name: String) {
        if (strategy != ContextStrategy.BRANCHING) return
        if (!agent.saveCheckpoint(name)) return
        refreshDerivedState()
        persistCurrentState()
    }

    fun createBranch(checkpointName: String, branchName: String) {
        if (strategy != ContextStrategy.BRANCHING) return
        if (!agent.createBranch(checkpointName, branchName)) return
        refreshDerivedState()
        persistCurrentState()
    }

    fun switchBranch(name: String) {
        if (strategy != ContextStrategy.BRANCHING) return
        if (!agent.switchBranch(name)) return
        messages.clear()
        messages.addAll(agent.activeBranchHistory().toUiMessages())
        refreshDerivedState()
        persistCurrentState()
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
                saveCurrentStrategyState()
                withContext(Dispatchers.Main) { refreshDerivedState() }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                    loading = false
                }
            }
        }
    }

    private fun refreshDerivedState() {
        facts = agent.currentFacts()
        branchNames = agent.branchNames()
        checkpointNames = agent.checkpointNames()
        activeBranch = agent.activeBranch()
    }

    private fun persistCurrentState() {
        viewModelScope.launch(Dispatchers.IO) {
            saveCurrentStrategyState()
        }
    }

    private suspend fun saveCurrentStrategyState() {
        repository.save(
            strategy = strategy,
            uiMessages = messages.toList(),
            memory = agent.snapshotState()
        )
    }
}

private fun List<InputMessage>.toUiMessages(): List<UiMessage> =
    map { UiMessage(role = it.role, text = it.content) }
