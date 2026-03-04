package ru.zis.prompting

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zis.prompting.agent.ChatAgent
import ru.zis.prompting.db.UserProfileRepository
import ru.zis.prompting.network.RouterAiApiFactory
import ru.zis.prompting.profile.UserProfile
import ru.zis.prompting.task.StageEntry
import ru.zis.prompting.task.TaskStage
import ru.zis.prompting.task.TaskState
import ru.zis.prompting.task.TaskStatus

class TaskAgentViewModel(application: Application) : AndroidViewModel(application) {

    private val api = RouterAiApiFactory.create()
    private val agent = ChatAgent(
        api = api,
        model = "openai/gpt-5.2",
        maxHistoryMessages = 40
    )

    private val pausedChatAgent = ChatAgent(
        api = api,
        model = "openai/gpt-5.2",
        maxHistoryMessages = 40
    )

    private val profileRepository: UserProfileRepository =
        (application as App).userProfileRepository

    var loading by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    var inputTask by mutableStateOf("")
    var userInput by mutableStateOf("")
    var activeProfileName by mutableStateOf<String?>(null)
        private set

    var taskState by mutableStateOf(TaskState())
        private set

    val messages = mutableStateListOf<UiMessage>()

    private var plannedSteps: List<String> = emptyList()
    private var executionStepIndex: Int = 0
    private var stageContext: String = ""

    init {
        refreshActiveProfile()
    }

    fun refreshActiveProfile() {
        viewModelScope.launch(Dispatchers.IO) {
            val active = profileRepository.getActive()
            withContext(Dispatchers.Main) {
                applyActiveProfile(active)
            }
        }
    }

    fun startTask() {
        val description = inputTask.trim()
        if (description.isBlank() || loading) return

        resetInternal(keepInput = false)
        messages += UiMessage(role = "user", text = description)
        taskState = taskState.copy(
            stage = TaskStage.PLANNING,
            status = TaskStatus.ACTIVE,
            currentStep = 1,
            totalSteps = 1,
            expectedAction = "Построение плана выполнения задачи",
            taskDescription = description
        )

        val prompt = buildPlanningPrompt(description)
        requestAgent(
            stage = TaskStage.PLANNING,
            systemPrompt = prompt
        ) { answer ->
            plannedSteps = parseSteps(answer)
            executionStepIndex = 0

            val total = plannedSteps.size.coerceAtLeast(1)
            val nextAction = if (plannedSteps.isNotEmpty()) {
                "Продолжить: выполнить шаг 1 — ${plannedSteps.first()}"
            } else {
                "Продолжить: перейти к выполнению задачи"
            }

            stageContext = answer.take(600)

            taskState = taskState.copy(
                stage = TaskStage.EXECUTION,
                currentStep = 0,
                totalSteps = total,
                expectedAction = nextAction,
                stageLog = taskState.stageLog + StageEntry(
                    stage = TaskStage.PLANNING,
                    summary = answer.take(300),
                    messages = messages.toList()
                )
            )
        }
    }

    fun pause() {
        if (taskState.stage == TaskStage.IDLE || taskState.stage == TaskStage.DONE) return
        taskState = taskState.copy(status = TaskStatus.PAUSED)
    }

    fun continueStage(userAdjustment: String? = null) {
        if (loading) return
        if (taskState.stage == TaskStage.IDLE || taskState.stage == TaskStage.DONE) return

        if (taskState.status == TaskStatus.PAUSED) {
            taskState = taskState.copy(status = TaskStatus.ACTIVE)
        }

        when (taskState.stage) {
            TaskStage.EXECUTION -> continueExecution(userAdjustment)
            TaskStage.VALIDATION -> continueValidation(userAdjustment)
            else -> Unit
        }
    }

    fun sendUserInput() {
        val text = userInput.trim()
        if (text.isBlank() || loading) return
        userInput = ""

        if (taskState.stage == TaskStage.IDLE || taskState.stage == TaskStage.DONE) return

        if (taskState.status == TaskStatus.PAUSED) {
            askWhilePaused(text)
            return
        }

        continueStage(userAdjustment = text)
    }

    fun resetTask() {
        resetInternal(keepInput = false)
        taskState = TaskState()
    }

    private fun continueExecution(userAdjustment: String? = null) {
        val stepNumber = executionStepIndex + 1
        val total = plannedSteps.size.coerceAtLeast(1)

        if (executionStepIndex >= total) {
            taskState = taskState.copy(
                stage = TaskStage.VALIDATION,
                currentStep = total,
                totalSteps = total,
                expectedAction = "Продолжить: запустить валидацию результата"
            )
            return
        }

        val stepText = plannedSteps.getOrNull(executionStepIndex) ?: "Выполнить следующий логический шаг задачи"
        val prompt = buildExecutionPrompt(stepNumber, total, stepText, userAdjustment)
        requestAgent(
            stage = TaskStage.EXECUTION,
            systemPrompt = prompt,
            userVisibleText = userAdjustment
        ) { answer ->
            stageContext = answer.take(600)
            executionStepIndex += 1

            if (executionStepIndex >= total) {
                taskState = taskState.copy(
                    stage = TaskStage.VALIDATION,
                    currentStep = total,
                    totalSteps = total,
                    expectedAction = "Продолжить: проверить результат (validation)",
                    stageLog = taskState.stageLog + StageEntry(
                        stage = TaskStage.EXECUTION,
                        summary = "Шаг $stepNumber/$total выполнен: ${answer.take(240)}",
                        messages = messages.toList()
                    )
                )
            } else {
                val next = plannedSteps.getOrNull(executionStepIndex).orEmpty()
                taskState = taskState.copy(
                    stage = TaskStage.EXECUTION,
                    currentStep = executionStepIndex,
                    totalSteps = total,
                    expectedAction = "Продолжить: шаг ${executionStepIndex + 1} — $next",
                    stageLog = taskState.stageLog + StageEntry(
                        stage = TaskStage.EXECUTION,
                        summary = "Шаг $stepNumber/$total выполнен: ${answer.take(240)}",
                        messages = messages.toList()
                    )
                )
            }
        }
    }

    private fun continueValidation(userAdjustment: String? = null) {
        val prompt = buildValidationPrompt(userAdjustment)
        requestAgent(
            stage = TaskStage.VALIDATION,
            systemPrompt = prompt,
            userVisibleText = userAdjustment
        ) { answer ->
            stageContext = answer.take(600)
            taskState = taskState.copy(
                stage = TaskStage.DONE,
                currentStep = taskState.totalSteps,
                expectedAction = "Задача завершена. Можно сбросить и начать новую",
                stageLog = taskState.stageLog + StageEntry(
                    stage = TaskStage.VALIDATION,
                    summary = answer.take(300),
                    messages = messages.toList()
                )
            )
        }
    }

    private fun requestAgent(
        stage: TaskStage,
        systemPrompt: String,
        userVisibleText: String? = null,
        includeTaskContext: Boolean = true,
        onSuccess: (String) -> Unit
    ) {
        loading = true
        error = null

        userVisibleText?.let {
            messages += UiMessage(role = "user", text = it)
        }
        messages += UiMessage(role = "user", text = systemPrompt, isHidden = true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val activeProfile = profileRepository.getActive()
                withContext(Dispatchers.Main) {
                    applyActiveProfile(activeProfile)
                }

                val promptForModel = if (includeTaskContext) {
                    buildResumeAwarePrompt(stage, systemPrompt)
                } else {
                    systemPrompt
                }
                val targetAgent = if (includeTaskContext) agent else pausedChatAgent
                val turn = targetAgent.send(userText = promptForModel, temperature = 0.7f)

                withContext(Dispatchers.Main) {
                    messages += UiMessage(
                        role = "assistant",
                        text = turn.text,
                        latencyMs = turn.latencyMs,
                        usage = turn.usage
                    )
                    loading = false
                    onSuccess(turn.text)
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                    loading = false
                }
            }
        }
    }

    private fun askWhilePaused(text: String) {
        requestAgent(
            stage = taskState.stage,
            systemPrompt = text,
            userVisibleText = text,
            includeTaskContext = false,
            onSuccess = { }
        )
    }

    private fun buildResumeAwarePrompt(stage: TaskStage, userPrompt: String): String {
        val task = taskState.taskDescription
        val base = buildString {
            appendLine("Работаем по конечному автомату задачи.")
            appendLine("Этап: $stage")
            appendLine("Задача: $task")
            if (stageContext.isNotBlank()) {
                appendLine("Контекст предыдущего этапа/шага:")
                appendLine(stageContext)
            }
            appendLine()
            append(userPrompt)
        }
        return base
    }

    private fun buildPlanningPrompt(description: String): String = """
        Этап planning.
        Сформируй план выполнения задачи в 3-5 шагов.
        Задача: $description

        Формат:
        1. ...
        2. ...
        3. ...
    """.trimIndent()

    private fun buildExecutionPrompt(
        stepNumber: Int,
        totalSteps: Int,
        stepText: String,
        userAdjustment: String?
    ): String = """
        Этап execution.
        Выполни шаг $stepNumber из $totalSteps.
        Шаг: $stepText

        ${if (userAdjustment.isNullOrBlank()) "" else "Корректировка пользователя для текущего этапа: $userAdjustment"}

        Дай результат шага и коротко, что ожидать дальше.
    """.trimIndent()

    private fun buildValidationPrompt(userAdjustment: String?): String = """
        Этап validation.
        Проверь результат выполнения задачи.

        ${if (userAdjustment.isNullOrBlank()) "" else "Доп. критерий от пользователя: $userAdjustment"}

        Верни:
        - что выполнено,
        - что требует доработки,
        - итоговый статус.
    """.trimIndent()

    private fun parseSteps(text: String): List<String> {
        val lines = text.lines()
            .map { it.trim() }
            .filter { it.matches(Regex("^(\\d+)[.)]\\s+.+")) }
            .map { it.replace(Regex("^(\\d+)[.)]\\s+"), "") }

        if (lines.isNotEmpty()) return lines.take(5)

        return text.lines()
            .map { it.trim().removePrefix("- ").removePrefix("• ") }
            .filter { it.isNotBlank() }
            .take(5)
    }

    private fun applyActiveProfile(profile: UserProfile?) {
        agent.setProfile(profile)
        pausedChatAgent.setProfile(profile)
        activeProfileName = profile?.name
    }

    private fun resetInternal(keepInput: Boolean) {
        agent.clear()
        loading = false
        error = null
        messages.clear()
        plannedSteps = emptyList()
        executionStepIndex = 0
        stageContext = ""
        userInput = ""
        pausedChatAgent.clear()
        if (!keepInput) inputTask = ""
    }
}
