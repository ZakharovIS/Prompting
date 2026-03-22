package ru.zis.prompting.agent

import android.os.SystemClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import ru.zis.prompting.network.RouterAiApi
import ru.zis.prompting.data.InputMessage
import ru.zis.prompting.data.ResponsesRequest
import ru.zis.prompting.data.Usage
import ru.zis.prompting.mcp.McpRegistry
import ru.zis.prompting.profile.UserProfile

class ChatAgent(
    private val api: RouterAiApi,
    private val model: String = "openai/gpt-5.2",
    private val maxHistoryMessages: Int = 40,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
    private val mcpRegistry: McpRegistry = McpRegistry.default(),
    private val ragRepository: RagRepository? = null
) {
    private val fullHistory = mutableListOf<InputMessage>()
    private var workingMemory: WorkingMemory = WorkingMemory()
    private val longTermMemory = mutableListOf<LongTermMemoryItem>()

    private var summary: String? = null
    private var summarizedMessagesCount: Int = 0
    private var activeProfile: UserProfile? = null
    private val invariants = mutableListOf<InvariantItem>()

    private var cumulativeInputTokensSum: Int = 0
    private var cumulativeOutputTokensSum: Int = 0
    private var hasCumulativeTokens: Boolean = false
    private var ragEnabled: Boolean = false
    private val json = Json { ignoreUnknownKeys = true }

    private val clarificationMarkers = listOf(
        "уточняю", "то есть", "имею в виду", "я имею в виду", "конкретно", "речь идёт о", "речь идет о"
    )

    private val constraintMarkers = listOf(
        "только", "не использовать", "запрещено", "нельзя", "обязательно", "ограничение", "без "
    )

    companion object {
        const val RECENT_MESSAGES_COUNT = 5
        const val SUMMARY_BATCH_SIZE = 10
        private const val SUMMARY_ROLE = "system"
        private const val MCP_TOOL_CALL_MAX_LOOPS = 2
    }

    fun clear() {
        fullHistory.clear()
        workingMemory = WorkingMemory()
        summary = null
        summarizedMessagesCount = 0
        cumulativeInputTokensSum = 0
        cumulativeOutputTokensSum = 0
        hasCumulativeTokens = false
    }

    fun snapshotHistory(): List<InputMessage> = fullHistory.toList()

    fun snapshotWorkingMemory(): WorkingMemory = workingMemory

    fun snapshotLongTermMemory(): List<LongTermMemoryItem> = longTermMemory.toList()

    fun snapshotSummary(): String? = summary

    fun snapshotTaskLifecycle(): TaskLifecycleSnapshot {
        val profile = TaskProfiles.parseProfile(workingMemory.taskProfileType)
        val stage = TaskProfiles.parseStage(workingMemory.currentStage)
        return TaskLifecycleSnapshot(
            profileType = profile,
            currentStage = stage,
            profileLabel = profile?.let { TaskProfiles.profileLabel(it) },
            stageLabel = stage?.let { TaskProfiles.stageLabel(it) }
        )
    }

    fun resetTaskLifecycle() {
        workingMemory = workingMemory.copy(
            taskProfileType = null,
            currentStage = null
        )
    }

    fun setProfile(profile: UserProfile?) {
        activeProfile = profile
    }

    fun setInvariants(items: List<InvariantItem>) {
        invariants.clear()
        invariants.addAll(items.filter { it.rule.isNotBlank() })
    }

    fun setRagEnabled(enabled: Boolean) {
        ragEnabled = enabled
    }

    fun isRagEnabled(): Boolean = ragEnabled

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

    fun restoreMemoryLayers(savedWorkingMemory: WorkingMemory?, savedLongTermMemory: List<LongTermMemoryItem>) {
        workingMemory = savedWorkingMemory ?: WorkingMemory()
        longTermMemory.clear()
        longTermMemory.addAll(savedLongTermMemory)
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
        updateWorkingMemory()
        captureLongTermMemoryFromUser(userText)
        maybeRefreshSummary(temperature)

        ensureTaskLifecycle(userText = userText, temperature = temperature)

        val deterministicScheduler = tryHandleDeterministicWeatherSchedulerRequest(userText)
        if (deterministicScheduler != null) {
            fullHistory += InputMessage(role = "assistant", content = deterministicScheduler)
            updateWorkingMemory()
            maybeRefreshSummary(temperature)

            return AgentTurn(
                text = deterministicScheduler,
                latencyMs = 0,
                usage = Usage(
                    currentRequestTokens = null,
                    modelResponseTokens = null,
                    cumulativeInputTokens = if (hasCumulativeTokens) cumulativeInputTokensSum else null,
                    cumulativeOutputTokens = if (hasCumulativeTokens) cumulativeOutputTokensSum else null
                )
            )
        }

        val deterministicWeather = tryHandleDeterministicWeatherRequest(userText)
        if (deterministicWeather != null) {
            fullHistory += InputMessage(role = "assistant", content = deterministicWeather)
            updateWorkingMemory()
            maybeRefreshSummary(temperature)

            return AgentTurn(
                text = deterministicWeather,
                latencyMs = 0,
                usage = Usage(
                    currentRequestTokens = null,
                    modelResponseTokens = null,
                    cumulativeInputTokens = if (hasCumulativeTokens) cumulativeInputTokensSum else null,
                    cumulativeOutputTokens = if (hasCumulativeTokens) cumulativeOutputTokensSum else null
                )
            )
        }

        val blockedByState = buildLifecycleViolationMessageIfAny(userText)
        if (blockedByState != null) {
            fullHistory += InputMessage(role = "assistant", content = blockedByState)
            updateWorkingMemory()
            maybeRefreshSummary(temperature)

            return AgentTurn(
                text = blockedByState,
                latencyMs = 0,
                usage = Usage(
                    currentRequestTokens = null,
                    modelResponseTokens = null,
                    cumulativeInputTokens = if (hasCumulativeTokens) cumulativeInputTokensSum else null,
                    cumulativeOutputTokens = if (hasCumulativeTokens) cumulativeOutputTokensSum else null
                )
            )
        }

        val start = nowMs()
        val assistantText = runToolAwareGeneration(temperature)
        val latencyMs = nowMs() - start
        val validatedAssistantText = validateInvariants(assistantText, temperature)

        // 3) добавляем ответ ассистента в память
        fullHistory += InputMessage(role = "assistant", content = validatedAssistantText)
        updateWorkingMemory()
        maybeRefreshSummary(temperature)

        val mergedUsage = Usage(
            currentRequestTokens = null,
            modelResponseTokens = null,
            cumulativeInputTokens = if (hasCumulativeTokens) cumulativeInputTokensSum else null,
            cumulativeOutputTokens = if (hasCumulativeTokens) cumulativeOutputTokensSum else null
        )

        return AgentTurn(
            text = validatedAssistantText,
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

    private suspend fun runToolAwareGeneration(temperature: Float?): String {
        repeat(MCP_TOOL_CALL_MAX_LOOPS) {
            val response = requestModel(temperature)
            val text = response.extractText().ifBlank { "(пустой ответ)" }
            val call = parseToolCall(text)
            if (call == null) return text

            val toolResult = mcpRegistry.callTool(call.tool, call.arguments)
            val toolPayloadText = json.encodeToString(McpToolEnvelope.serializer(), McpToolEnvelope(
                tool = call.tool,
                isError = toolResult.isError,
                content = toolResult.content,
                payload = toolResult.payload
            ))

            fullHistory += InputMessage(role = "system", content = "TOOL_RESULT: $toolPayloadText")
        }

        val fallback = requestModel(temperature).extractText()
        return fallback.ifBlank { "(пустой ответ)" }
    }

    private suspend fun tryHandleDeterministicWeatherRequest(userText: String): String? {
        val text = userText.trim()
        if (text.isBlank()) return null

        val lower = text.lowercase()
        val weatherWords = listOf("погод", "weather", "температур", "дожд", "снег", "ветер")
        val schedulerWords = listOf("кажды", "период", "регуляр", "напомин", "уведом", "шторк", "старт", "останов")

        val hasWeatherIntent = weatherWords.any { lower.contains(it) }
        val hasSchedulerIntent = schedulerWords.any { lower.contains(it) }

        if (!hasWeatherIntent || hasSchedulerIntent) return null

        val location = extractLocationForWeather(text) ?: "Moscow"
        val toolResult = mcpRegistry.callTool(
            name = "get_weather_now",
            arguments = buildJsonObject {
                put("location", JsonPrimitive(location))
                put("save", JsonPrimitive(true))
            }
        )

        return if (toolResult.isError) {
            "Не удалось получить погоду через API: ${toolResult.content}"
        } else {
            toolResult.content
        }
    }

    private suspend fun tryHandleDeterministicWeatherSchedulerRequest(userText: String): String? {
        val text = userText.trim()
        if (text.isBlank()) return null

        val lower = text.lowercase()
        val weatherWords = listOf("погод", "weather", "температур", "дожд", "снег", "ветер")
        val schedulerWords = listOf("кажды", "период", "регуляр", "напомин", "уведом", "шторк", "старт", "останов", "сводк", "статус")

        val hasWeatherIntent = weatherWords.any { lower.contains(it) }
        val hasSchedulerIntent = schedulerWords.any { lower.contains(it) }
        if (!hasSchedulerIntent) return null
        if (!hasWeatherIntent) return null

        val action = when {
            lower.contains("останов") || lower.contains("выключ") || lower.contains("stop") -> "stop"
            lower.contains("статус") || lower.contains("работает") || lower.contains("status") -> "status"
            lower.contains("сводк") || lower.contains("истори") || lower.contains("summary") -> "summary"
            else -> "start"
        }

        val arguments = buildJsonObject {
            put("action", JsonPrimitive(action))

            if (action == "start") {
                val location = extractLocationForWeather(text) ?: "Moscow"
                val interval = extractIntervalMinutes(text) ?: 60
                put("location", JsonPrimitive(location))
                put("intervalMinutes", JsonPrimitive(interval))
                put("runNow", JsonPrimitive(true))
            }

            if (action == "summary") {
                put("limit", JsonPrimitive(10))
            }
        }

        val toolResult = mcpRegistry.callTool(name = "weather_scheduler", arguments = arguments)
        return if (toolResult.isError) {
            "Не удалось выполнить weather_scheduler: ${toolResult.content}"
        } else {
            toolResult.content
        }
    }

    private fun extractLocationForWeather(text: String): String? {
        val normalized = text.replace('\n', ' ').trim()
        if (normalized.isBlank()) return null

        val byV = Regex("\\bв\\s+([A-Za-zА-Яа-яЁё\\-\\s]{2,40})", RegexOption.IGNORE_CASE)
            .find(normalized)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('.', ',', '!', '?')

        if (!byV.isNullOrBlank()) {
            return byV
                .replace(Regex("\\b(сейчас|пожалуйста|каждые|каждый|минут|час|часа|часов)\\b", RegexOption.IGNORE_CASE), "")
                .trim()
                .takeIf { it.isNotBlank() }
        }

        return null
    }

    private fun extractIntervalMinutes(text: String): Int? {
        val normalized = text.lowercase()
        val minutesMatch = Regex("(\\d{1,4})\\s*(мин|мину|minutes?)").find(normalized)
        if (minutesMatch != null) {
            return minutesMatch.groupValues[1].toIntOrNull()?.coerceAtLeast(15)
        }

        val hoursMatch = Regex("(\\d{1,3})\\s*(час|часа|часов|hours?)").find(normalized)
        if (hoursMatch != null) {
            val hours = hoursMatch.groupValues[1].toIntOrNull() ?: return null
            return (hours * 60).coerceAtLeast(15)
        }

        return null
    }

    private suspend fun requestModel(temperature: Float?) = api.createResponse(
        ResponsesRequest(
            model = model,
            input = buildContextMessages(),
            stream = false,
            temperature = temperature
        )
    ).also { resp ->
        if (resp.error != null) {
            throw IllegalStateException(resp.error.message ?: "RouterAI error")
        }
        addToCumulative(resp.usage)
    }

    private fun parseToolCall(raw: String): McpToolCallPayload? {
        val trimmed = raw.trim()
        if (!(trimmed.startsWith("{") && trimmed.endsWith("}"))) return null

        val parsed = runCatching {
            json.decodeFromString<McpToolCallPayload>(trimmed)
        }.getOrNull() ?: return null

        if (parsed.tool.isBlank()) return null
        return parsed
    }

    private suspend fun buildContextMessages(): List<InputMessage> {
        val recentMessages = fullHistory.takeLast(maxHistoryMessages.coerceAtMost(RECENT_MESSAGES_COUNT))
        val lastUserQuestion = fullHistory.lastOrNull { it.role == "user" }?.content.orEmpty()
        val ragSystem = if (ragEnabled) {
            runCatching {
                ragRepository?.buildRagSystemMessage(question = lastUserQuestion)
            }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                InputMessage(role = SUMMARY_ROLE, content = it)
            }
        } else {
            null
        }

        val profileSystem = buildProfileSystemMessage()
        val taskLifecycleSystem = buildTaskLifecycleSystemMessage()
        val invariantsSystem = buildInvariantsSystemMessage()
        val longTermSystem = buildLongTermSystemMessage()
        val workingSystem = buildWorkingSystemMessage()
        val mcpSystem = buildMcpToolsSystemMessage()

        val summaryMessage = summary?.takeIf { it.isNotBlank() }?.let {
            InputMessage(
                role = SUMMARY_ROLE,
                content = "Краткое summary предыдущего диалога:\n$it"
            )
        }
        return listOfNotNull(
            profileSystem,
            taskLifecycleSystem,
            invariantsSystem,
            longTermSystem,
            workingSystem,
            mcpSystem,
            ragSystem,
            summaryMessage
        ) + recentMessages
    }

    private fun buildMcpToolsSystemMessage(): InputMessage {
        val tools = mcpRegistry.listTools()
        val toolsText = tools.joinToString(separator = "\n") { tool ->
            val schemaText = json.encodeToString(JsonObject.serializer(), tool.inputSchema)
            "- ${tool.name}: ${tool.description}\n  inputSchema=$schemaText"
        }

        val content = buildString {
            appendLine("=== MCP ИНСТРУМЕНТЫ ===")
            appendLine("Доступные инструменты:")
            appendLine(toolsText)
            appendLine()
            appendLine("ВАЖНО:")
            appendLine("- Для запросов о погоде и других внешних/актуальных данных ОБЯЗАТЕЛЬНО вызывай MCP-инструмент, не придумывай ответ из памяти.")
            appendLine("- Если речь о разовом запросе погоды — используй get_weather_now.")
            appendLine("- Если речь о периодическом сборе/напоминаниях — используй weather_scheduler.")
            appendLine("- Если локация неоднозначна, сначала уточни её у пользователя или используй geocode_address для уточнения адреса.")
            appendLine("- Если нужен длинный флоу по нескольким городам (координаты -> рассвет/закат -> погода -> сохранение), используй weather_pipeline.")
            appendLine("- Для времени рассвета/заката по координатам используй sunrise_sunset.")
            appendLine()
            appendLine("Если нужен инструмент — верни ТОЛЬКО JSON без markdown:")
            appendLine("{\"tool\":\"имя_инструмента\",\"arguments\":{...}}")
            appendLine("После получения TOOL_RESULT сформируй финальный ответ обычным текстом.")
            append("=======================")
        }

        return InputMessage(role = SUMMARY_ROLE, content = content)
    }

    private fun buildTaskLifecycleSystemMessage(): InputMessage? {
        val profileType = TaskProfiles.parseProfile(workingMemory.taskProfileType) ?: return null
        val currentStage = TaskProfiles.parseStage(workingMemory.currentStage) ?: return null
        if (profileType == TaskProfileType.FREE) {
            return InputMessage(
                role = SUMMARY_ROLE,
                content = "=== СОСТОЯНИЕ ЗАДАЧИ ===\nПрофиль: Свободный режим\nОграничения стадий не применяются."
            )
        }

        val allowedNext = TaskProfiles.allowedNext(profileType, currentStage)

        val allowedText = if (allowedNext.isEmpty()) {
            "Дальнейшие переходы не разрешены (задача завершена)."
        } else {
            allowedNext.joinToString { stage -> "${stage.name} (${TaskProfiles.stageLabel(stage)})" }
        }

        val content = buildString {
            appendLine("=== СОСТОЯНИЕ ЗАДАЧИ ===")
            appendLine("Профиль: ${TaskProfiles.profileLabel(profileType)} (${profileType.name})")
            appendLine("Текущая стадия: ${currentStage.name} (${TaskProfiles.stageLabel(currentStage)})")
            appendLine("Разрешённые следующие стадии: $allowedText")
            appendLine("Переход в следующую стадию допускается ТОЛЬКО по явной команде пользователя.")
            appendLine("Нельзя перепрыгивать стадии и нельзя выполнять действия из будущих стадий.")
            append("========================")
        }

        return InputMessage(role = SUMMARY_ROLE, content = content)
    }

    private fun buildInvariantsSystemMessage(): InputMessage? {
        val active = invariants.filter { it.rule.isNotBlank() }
        if (active.isEmpty()) return null

        val content = buildString {
            appendLine("=== ИНВАРИАНТЫ (НАРУШАТЬ ЗАПРЕЩЕНО) ===")
            active.forEachIndexed { index, item ->
                appendLine("${index + 1}. ${item.rule}")
            }
            appendLine()
            appendLine("Если запрос пользователя конфликтует с любым инвариантом:")
            appendLine("1) НЕ предлагай нарушающее решение,")
            appendLine("2) явно укажи конфликтующий инвариант,")
            appendLine("3) предложи безопасную альтернативу в рамках инвариантов.")
            append("========================================")
        }

        return InputMessage(role = SUMMARY_ROLE, content = content)
    }

    private fun buildProfileSystemMessage(): InputMessage? {
        val profile = activeProfile ?: return null
        if (profile.name.isBlank() && profile.style.isBlank() && profile.constraints.isBlank() && profile.context.isBlank()) {
            return null
        }

        val content = buildString {
            appendLine("=== ПРОФИЛЬ ПОЛЬЗОВАТЕЛЯ ===")
            appendLine("Имя профиля: ${profile.name}")
            appendLine()
            appendLine("СТИЛЬ ОТВЕТОВ:")
            appendLine(profile.style.ifBlank { "Не задан" })
            appendLine()
            appendLine("СТРОГИЕ ОГРАНИЧЕНИЯ (соблюдать обязательно):")
            appendLine(profile.constraints.ifBlank { "Не заданы" })
            appendLine()
            appendLine("КОНТЕКСТ:")
            appendLine(profile.context.ifBlank { "Не задан" })
            appendLine()
            appendLine("Соблюдай стиль и контекст. Ограничения выполняй строго в каждом ответе.")
            append("===========================")
        }

        return InputMessage(role = SUMMARY_ROLE, content = content)
    }

    private fun buildWorkingSystemMessage(): InputMessage? {
        if (workingMemory.goal.isNullOrBlank() &&
            workingMemory.clarifications.isEmpty() &&
            workingMemory.constraints.isEmpty() &&
            workingMemory.keyFacts.isEmpty() &&
            workingMemory.openQuestions.isEmpty()
        ) return null

        val content = buildString {
            appendLine("Рабочая память текущей задачи:")
            workingMemory.goal?.takeIf { it.isNotBlank() }?.let {
                appendLine("Цель: $it")
            }
            if (workingMemory.clarifications.isNotEmpty()) {
                appendLine("Уточнения пользователя:")
                workingMemory.clarifications.forEach { appendLine("- $it") }
            }
            if (workingMemory.constraints.isNotEmpty()) {
                appendLine("Ограничения и зафиксированные термины:")
                workingMemory.constraints.forEach { appendLine("- $it") }
            }
            if (workingMemory.keyFacts.isNotEmpty()) {
                appendLine("Ключевые данные:")
                workingMemory.keyFacts.forEach { appendLine("- $it") }
            }
            if (workingMemory.openQuestions.isNotEmpty()) {
                appendLine("Открытые вопросы:")
                workingMemory.openQuestions.forEach { appendLine("- $it") }
            }
        }.trim()

        return InputMessage(role = SUMMARY_ROLE, content = content)
    }

    private fun buildLongTermSystemMessage(): InputMessage? {
        if (longTermMemory.isEmpty()) return null

        val items = longTermMemory.takeLast(15)
        val content = buildString {
            appendLine("Долговременная память пользователя:")
            items.forEach { item ->
                appendLine("- [${item.category}] ${item.content}")
            }
            appendLine("Используй только если релевантно текущему запросу.")
        }.trim()

        return InputMessage(role = SUMMARY_ROLE, content = content)
    }

    private fun updateWorkingMemory() {
        val preservedProfile = workingMemory.taskProfileType
        val preservedStage = workingMemory.currentStage

        val recentUsers = fullHistory
            .asSequence()
            .filter { it.role == "user" }
            .map { it.content.trim() }
            .filter { it.isNotBlank() }
            .toList()

        if (recentUsers.isEmpty()) {
            workingMemory = WorkingMemory(
                taskProfileType = preservedProfile,
                currentStage = preservedStage
            )
            return
        }

        val goal = recentUsers.first().take(240)

        val clarifications = recentUsers
            .filter { text ->
                val lower = text.lowercase()
                clarificationMarkers.any { marker -> lower.contains(marker) }
            }
            .takeLast(6)
            .map { it.replace("\n", " ").trim() }
            .distinct()

        val constraints = recentUsers
            .filter { text ->
                val lower = text.lowercase()
                constraintMarkers.any { marker -> lower.contains(marker) }
            }
            .takeLast(6)
            .map { it.replace("\n", " ").trim() }
            .distinct()

        val keyFacts = recentUsers
            .takeLast(4)
            .map { it.replace("\n", " ").trim() }
            .distinct()

        val openQuestions = recentUsers
            .filter { it.contains("?") }
            .takeLast(3)
            .distinct()

        workingMemory = WorkingMemory(
            goal = goal,
            clarifications = clarifications,
            constraints = constraints,
            keyFacts = keyFacts,
            openQuestions = openQuestions,
            taskProfileType = preservedProfile,
            currentStage = preservedStage
        )
    }

    private suspend fun ensureTaskLifecycle(userText: String, temperature: Float?) {
        val currentProfile = TaskProfiles.parseProfile(workingMemory.taskProfileType)
        if (currentProfile == null) {
            val detected = classifyTaskProfile(userText, temperature)
            val initial = if (detected == TaskProfileType.FREE) TaskStage.FREE else TaskProfiles.initialStage(detected)
            workingMemory = workingMemory.copy(
                taskProfileType = detected.name,
                currentStage = initial.name
            )
            return
        }

        if (currentProfile == TaskProfileType.FREE) {
            if (TaskProfiles.parseStage(workingMemory.currentStage) == null) {
                workingMemory = workingMemory.copy(currentStage = TaskStage.FREE.name)
            }
            return
        }

        val currentStage = TaskProfiles.parseStage(workingMemory.currentStage)
            ?: TaskProfiles.initialStage(currentProfile)

        val transitionIntent = detectTransitionIntent(currentProfile, userText, temperature)
        if (!transitionIntent.transitionRequested) {
            if (TaskProfiles.parseStage(workingMemory.currentStage) == null) {
                workingMemory = workingMemory.copy(currentStage = currentStage.name)
            }
            return
        }

        val target = TaskProfiles.parseStage(transitionIntent.targetStage)
        if (target == null) return

        if (TaskProfiles.canTransition(currentProfile, currentStage, target)) {
            workingMemory = workingMemory.copy(currentStage = target.name)
        }
    }

    private fun buildLifecycleViolationMessageIfAny(userText: String): String? {
        val profileType = TaskProfiles.parseProfile(workingMemory.taskProfileType) ?: return null
        if (profileType == TaskProfileType.FREE) return null

        val currentStage = TaskProfiles.parseStage(workingMemory.currentStage)
            ?: TaskProfiles.initialStage(profileType)

        val requestedStage = TaskProfiles.detectRequestedWorkStage(profileType, userText) ?: return null
        if (requestedStage == currentStage) return null

        if (TaskProfiles.canTransition(profileType, currentStage, requestedStage)) {
            return null
        }

        val allowedNext = TaskProfiles.allowedNext(profileType, currentStage)
            .joinToString { "${it.name} (${TaskProfiles.stageLabel(it)})" }
            .ifBlank { "нет" }

        return buildString {
            appendLine("Сейчас нельзя перейти к стадии ${requestedStage.name} (${TaskProfiles.stageLabel(requestedStage)}).")
            appendLine("Текущая стадия: ${currentStage.name} (${TaskProfiles.stageLabel(currentStage)}).")
            appendLine("Разрешённые переходы: $allowedNext.")
            append("Сделайте явный переход только в разрешённую следующую стадию.")
        }
    }

    private suspend fun classifyTaskProfile(userText: String, temperature: Float?): TaskProfileType {
        val prompt = """
            Классифицируй запрос пользователя по типу задачи.
            Варианты:
            - DEV: задача на разработку/код/рефакторинг/исправления
            - ANALYTICS: задача на анализ/исследование/сравнение/выводы
            - FREE: прочее

            Сообщение пользователя:
            $userText

            Верни ТОЛЬКО JSON без markdown:
            {"profile": "DEV"|"ANALYTICS"|"FREE"}
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
            throw IllegalStateException(response.error.message ?: "RouterAI task profile classification error")
        }

        addToCumulative(response.usage)

        val parsed = runCatching {
            json.decodeFromString<TaskProfileClassificationResult>(response.extractText().trim())
        }.getOrNull()

        return TaskProfiles.parseProfile(parsed?.profile) ?: TaskProfileType.FREE
    }

    private suspend fun detectTransitionIntent(
        profileType: TaskProfileType,
        userText: String,
        temperature: Float?
    ): TransitionIntentResult {
        val profile = TaskProfiles.of(profileType)
        val stages = profile.orderedStages.joinToString { it.name }
        val prompt = """
            Определи, просит ли пользователь ЯВНО перейти в другую стадию задачи.
            Профиль: ${profileType.name}
            Допустимые стадии: $stages

            Сообщение пользователя:
            $userText

            Если явной команды перехода нет, верни transitionRequested=false.
            Если есть, верни целевую стадию из списка.

            Верни ТОЛЬКО JSON без markdown:
            {"transitionRequested": true|false, "targetStage": "STAGE_NAME"|null}
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
            throw IllegalStateException(response.error.message ?: "RouterAI transition intent detection error")
        }

        addToCumulative(response.usage)

        return runCatching {
            json.decodeFromString<TransitionIntentResult>(response.extractText().trim())
        }.getOrElse {
            TransitionIntentResult()
        }
    }

    private fun captureLongTermMemoryFromUser(userText: String) {
        val normalized = userText.trim()
        if (normalized.isBlank()) return

        val lower = normalized.lowercase()
        val category = when {
            lower.contains("предпочита") || lower.contains("люблю") || lower.contains("не люблю") -> "preference"
            lower.contains("меня зовут") || lower.startsWith("я ") || lower.contains("мой ") || lower.contains("моя ") -> "fact"
            lower.contains("договор") || lower.contains("решили") || lower.contains("пусть будет") -> "decision"
            else -> null
        } ?: return

        val exists = longTermMemory.any {
            it.category == category && it.content.equals(normalized, ignoreCase = true)
        }
        if (exists) return

        longTermMemory += LongTermMemoryItem(
            category = category,
            content = normalized
        )
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

    private suspend fun validateInvariants(candidate: String, temperature: Float?): String {
        val active = invariants.filter { it.rule.isNotBlank() }
        if (active.isEmpty()) return candidate

        val rulesText = active.mapIndexed { index, item -> "${index + 1}. ${item.rule}" }
            .joinToString(separator = "\n")

        val validatorPrompt = """
            Ты валидатор ответов ассистента.
            Проверь, нарушает ли ответ хотя бы один инвариант.

            Инварианты:
            $rulesText

            Ответ ассистента:
            $candidate

            Верни ТОЛЬКО JSON без markdown:
            {"violated": true|false, "rule": "текст инварианта или null", "explanation": "краткое объяснение"}
        """.trimIndent()

        val validationResponse = api.createResponse(
            ResponsesRequest(
                model = model,
                input = listOf(InputMessage(role = "user", content = validatorPrompt)),
                stream = false,
                temperature = temperature
            )
        )

        if (validationResponse.error != null) {
            throw IllegalStateException(validationResponse.error.message ?: "RouterAI invariant validation error")
        }

        addToCumulative(validationResponse.usage)

        val rawText = validationResponse.extractText().trim()
        val parsed = runCatching {
            json.decodeFromString<InvariantValidationResult>(rawText)
        }.getOrElse {
            return candidate
        }

        if (!parsed.violated) return candidate

        val rule = parsed.rule?.takeIf { it.isNotBlank() } ?: "(инвариант не распознан)"
        val explanation = parsed.explanation?.takeIf { it.isNotBlank() }
            ?: "Запрос конфликтует с обязательными инвариантами."

        return buildString {
            appendLine("Не могу предложить этот вариант: он нарушает инвариант.")
            appendLine("Нарушаемый инвариант: $rule")
            append(explanation)
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

data class AgentTurn(
    val text: String,
    val latencyMs: Long,
    val usage: Usage?
)

data class TaskLifecycleSnapshot(
    val profileType: TaskProfileType?,
    val currentStage: TaskStage?,
    val profileLabel: String?,
    val stageLabel: String?
)

@Serializable
private data class McpToolCallPayload(
    val tool: String = "",
    val arguments: JsonObject = JsonObject(emptyMap())
)

@Serializable
private data class McpToolEnvelope(
    val tool: String,
    val isError: Boolean,
    val content: String,
    val payload: JsonObject = JsonObject(emptyMap())
)