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
import ru.zis.prompting.data.Usage
import ru.zis.prompting.db.ChatRepository
import ru.zis.prompting.db.InvariantRepository
import ru.zis.prompting.db.UserProfileRepository
import ru.zis.prompting.network.RouterAiApiFactory
import ru.zis.prompting.profile.UserProfile

data class UiMessage(
    val role: String,              // "user" | "assistant"
    val text: String,
    val latencyMs: Long? = null,   // только для assistant
    val usage: Usage? = null       // только для assistant
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val app: App = application as App

    var inputText by mutableStateOf("")
    var temperature by mutableFloatStateOf(1.0f)

    val messages = mutableStateListOf<UiMessage>()

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var ragEnabled by mutableStateOf(false)
        private set

    var useLocalLlm by mutableStateOf(false)
        private set

    /** true пока идёт начальная загрузка истории из БД */
    var historyLoading by mutableStateOf(true)
        private set

    val model = "openai/gpt-5.2"
    val localModel = "qwen2.5:14b"

    private val api = RouterAiApiFactory.create()
    private val agent = ChatAgent(
        api = api,
        model = model,
        ollamaApi = app.ollamaApi,
        localModel = localModel,
        mcpRegistry = app.mcpRegistry,
        ragRepository = app.ragRepository
    )

    private val repository: ChatRepository =
        app.chatRepository

    private val profileRepository: UserProfileRepository =
        app.userProfileRepository

    private val invariantRepository: InvariantRepository =
        app.invariantRepository

    var activeProfileName by mutableStateOf<String?>(null)
        private set

    var taskProfileLabel by mutableStateOf<String?>(null)
        private set

    var taskStageLabel by mutableStateOf<String?>(null)
        private set

    var taskProfileCode by mutableStateOf<String?>(null)
        private set

    var taskStageCode by mutableStateOf<String?>(null)
        private set

    init {
        loadHistory()
    }

    // ─── Загрузка истории из БД ───────────────────────────────────────────

    private fun loadHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val state = repository.load()
            val longTermMemory = repository.loadLongTermMemory()
            val activeProfile = profileRepository.getActive()
            val invariants = invariantRepository.getAll()
            withContext(Dispatchers.Main) {
                if (state != null) {
                    messages.addAll(state.uiMessages)
                    agent.restoreHistory(state.agentHistory, state.summary)
                    agent.restoreMemoryLayers(state.workingMemory, longTermMemory)
                    refreshTaskLifecycleState()
                    val (savedInput, savedOutput) = extractSavedCumulativeTokens(state.uiMessages)
                    agent.restoreCumulativeTokens(savedInput, savedOutput)
                } else {
                    agent.restoreMemoryLayers(savedWorkingMemory = null, savedLongTermMemory = longTermMemory)
                    refreshTaskLifecycleState()
                }
                applyActiveProfile(activeProfile)
                agent.setInvariants(invariants)
                agent.setRagEnabled(ragEnabled)
                agent.setUseLocalLlm(useLocalLlm)
                historyLoading = false
            }
        }
    }

    fun updateRagEnabled(enabled: Boolean) {
        ragEnabled = enabled
        agent.setRagEnabled(enabled)
    }

    fun updateUseLocalLlm(enabled: Boolean) {
        useLocalLlm = enabled
        agent.setUseLocalLlm(enabled)
    }

    fun refreshActiveProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val active = profileRepository.getActive()
            withContext(Dispatchers.Main) {
                applyActiveProfile(active)
            }
        }
    }

    private fun applyActiveProfile(profile: UserProfile?) {
        agent.setProfile(profile)
        activeProfileName = profile?.name
    }

    fun shortTermMemoryDump(): String {
        val history = agent.snapshotHistory()
        if (history.isEmpty()) return "Краткосрочная память пуста"

        return buildString {
            appendLine("Текущий диалог (${history.size} сообщений):")
            history.forEachIndexed { index, m ->
                appendLine("${index + 1}. ${m.role}: ${m.content}")
            }
        }.trim()
    }

    fun workingMemoryDump(): String {
        val wm = agent.snapshotWorkingMemory()
        if (wm.goal.isNullOrBlank() &&
            wm.clarifications.isEmpty() &&
            wm.constraints.isEmpty() &&
            wm.keyFacts.isEmpty() &&
            wm.openQuestions.isEmpty()
        ) {
            return "Рабочая память пуста"
        }

        return buildString {
            appendLine("Цель: ${wm.goal ?: "—"}")
            appendLine()
            appendLine("Что пользователь уже уточнил:")
            if (wm.clarifications.isEmpty()) appendLine("- —") else wm.clarifications.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Ограничения и зафиксированные термины:")
            if (wm.constraints.isEmpty()) appendLine("- —") else wm.constraints.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Ключевые данные:")
            if (wm.keyFacts.isEmpty()) appendLine("- —") else wm.keyFacts.forEach { appendLine("- $it") }
            appendLine()
            appendLine("Открытые вопросы:")
            if (wm.openQuestions.isEmpty()) appendLine("- —") else wm.openQuestions.forEach { appendLine("- $it") }
        }.trim()
    }

    fun longTermMemoryDump(): String {
        val items = agent.snapshotLongTermMemory()
        if (items.isEmpty()) return "Долговременная память пуста"

        return buildString {
            appendLine("Профиль/знания (${items.size}):")
            items.forEachIndexed { index, item ->
                appendLine("${index + 1}. [${item.category}] ${item.content}")
            }
        }.trim()
    }

    private fun extractSavedCumulativeTokens(uiMessages: List<UiMessage>): Pair<Int?, Int?> {
        val lastInputFromUsage = uiMessages
            .asReversed()
            .firstNotNullOfOrNull { it.usage?.cumulativeInputTokens }

        val lastOutputFromUsage = uiMessages
            .asReversed()
            .firstNotNullOfOrNull { it.usage?.cumulativeOutputTokens }

        if (lastInputFromUsage != null || lastOutputFromUsage != null) {
            return (lastInputFromUsage?.coerceAtLeast(0)) to (lastOutputFromUsage?.coerceAtLeast(0))
        }

        // Fallback для старых сохранений: суммируем usage по assistant-ходам.
        val inputSum = uiMessages
            .asSequence()
            .filter { it.role == "assistant" }
            .mapNotNull { msg ->
                val u = msg.usage ?: return@mapNotNull null
                u.currentRequestTokens ?: u.inputTokens
            }
            .sum()

        val outputSum = uiMessages
            .asSequence()
            .filter { it.role == "assistant" }
            .mapNotNull { msg ->
                val u = msg.usage ?: return@mapNotNull null
                u.modelResponseTokens ?: u.outputTokens
            }
            .sum()

        return (if (inputSum > 0) inputSum else null) to (if (outputSum > 0) outputSum else null)
    }

    // ─── Публичное API ────────────────────────────────────────────────────

    fun clearChat() {
        agent.clear()
        messages.clear()
        refreshTaskLifecycleState()
        error = null
        loading = false
        viewModelScope.launch(Dispatchers.IO) { repository.clear() }
    }

    fun resetTaskState() {
        agent.resetTaskLifecycle()
        refreshTaskLifecycleState()
        viewModelScope.launch(Dispatchers.IO) {
            repository.save(
                uiMessages = messages.toList(),
                agentHistory = agent.snapshotHistory(),
                summary = agent.snapshotSummary(),
                workingMemory = agent.snapshotWorkingMemory()
            )
        }
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
                val activeProfile = profileRepository.getActive()
                val invariants = invariantRepository.getAll()
                withContext(Dispatchers.Main) {
                    applyActiveProfile(activeProfile)
                    agent.setInvariants(invariants)
                }

                val turn = agent.send(userText = text, temperature = temperature)

                withContext(Dispatchers.Main) {
                    messages += UiMessage(
                        role = "assistant",
                        text = turn.text,
                        latencyMs = turn.latencyMs,
                        usage = turn.usage
                    )
                    refreshTaskLifecycleState()
                    loading = false
                }

                // Сохраняем после успешного ответа
                repository.save(
                    uiMessages = messages.toList(),
                    agentHistory = agent.snapshotHistory(),
                    summary = agent.snapshotSummary(),
                    workingMemory = agent.snapshotWorkingMemory()
                )
                repository.saveLongTermMemory(agent.snapshotLongTermMemory())
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                    loading = false
                }
            }
        }
    }

    private fun refreshTaskLifecycleState() {
        val snapshot = agent.snapshotTaskLifecycle()
        taskProfileLabel = snapshot.profileLabel
        taskStageLabel = snapshot.stageLabel
        taskProfileCode = snapshot.profileType?.name
        taskStageCode = snapshot.currentStage?.name
    }
}
