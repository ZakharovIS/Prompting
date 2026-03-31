package ru.zis.prompting.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.zis.prompting.data.ResponseContentPart
import ru.zis.prompting.data.ResponseOutputItem
import ru.zis.prompting.data.ResponsesRequest
import ru.zis.prompting.data.ResponsesResponse
import ru.zis.prompting.network.RouterAiApi

class ChatAgentTaskLifecycleTest {

    @Test
    fun `dev - blocks jump to implementation before approved plan`() = runBlocking {
        val api = QueueRouterAiApi(
            listOf(
                jsonResponse("""{"profile":"DEV"}"""),
                textResponse("Начинаем с уточнения требований."),
                jsonResponse("""{"transitionRequested":true,"targetStage":"IMPLEMENTATION"}""")
            )
        )
        val agent = ChatAgent(api = api, model = "test-model", nowMs = { 0L })

        agent.send("Нужно доработать Android-экран", temperature = 0f)
        val blocked = agent.send("Сразу пиши реализацию и код", temperature = 0f)

        assertTrue(blocked.text.contains("Сейчас нельзя перейти к стадии IMPLEMENTATION"))

        val lifecycle = agent.snapshotTaskLifecycle()
        assertEquals(TaskProfileType.DEV, lifecycle.profileType)
        assertEquals(TaskStage.CLARIFICATION, lifecycle.currentStage)
    }

    @Test
    fun `analytics - cannot finalize without validation`() = runBlocking {
        val api = QueueRouterAiApi(
            listOf(
                jsonResponse("""{"profile":"ANALYTICS"}"""),
                textResponse("Соберём данные для анализа."),
                jsonResponse("""{"transitionRequested":true,"targetStage":"DONE"}""")
            )
        )
        val agent = ChatAgent(api = api, model = "test-model", nowMs = { 0L })

        agent.send("Нужно проанализировать рынок", temperature = 0f)
        val blocked = agent.send("Дай сразу финал", temperature = 0f)

        assertTrue(blocked.text.contains("Сейчас нельзя перейти к стадии DONE"))

        val lifecycle = agent.snapshotTaskLifecycle()
        assertEquals(TaskProfileType.ANALYTICS, lifecycle.profileType)
        assertEquals(TaskStage.CLARIFICATION, lifecycle.currentStage)
    }

    @Test
    fun `stage persists after pause and still enforces transitions`() = runBlocking {
        val firstApi = QueueRouterAiApi(
            listOf(
                jsonResponse("""{"profile":"DEV"}"""),
                textResponse("Уточняем требования."),
                jsonResponse("""{"transitionRequested":true,"targetStage":"PLANNING"}"""),
                textResponse("Переходим к плану.")
            )
        )
        val agent1 = ChatAgent(api = firstApi, model = "test-model", nowMs = { 0L })

        agent1.send("Нужно разработать фичу", temperature = 0f)
        agent1.send("Перейди к планированию", temperature = 0f)

        val savedHistory = agent1.snapshotHistory()
        val savedSummary = agent1.snapshotSummary()
        val savedWorking = agent1.snapshotWorkingMemory()

        val secondApi = QueueRouterAiApi(
            listOf(
                jsonResponse("""{"transitionRequested":true,"targetStage":"IMPLEMENTATION"}"""),
                textResponse("Summary after blocked transition")
            )
        )
        val agent2 = ChatAgent(api = secondApi, model = "test-model", nowMs = { 0L })
        agent2.restoreHistory(savedHistory, savedSummary)
        agent2.restoreMemoryLayers(savedWorking, emptyList())

        val blocked = agent2.send("Теперь сразу делай реализацию", temperature = 0f)

        assertTrue(blocked.text.contains("Сейчас нельзя перейти к стадии IMPLEMENTATION"))
        assertEquals(TaskStage.PLANNING, agent2.snapshotTaskLifecycle().currentStage)
    }
}

private class QueueRouterAiApi(
    responses: List<ResponsesResponse>
) : RouterAiApi {
    private val queue = ArrayDeque(responses)

    override suspend fun createResponse(body: ResponsesRequest): ResponsesResponse {
        return queue.removeFirstOrNull()
            ?: error("No fake response left for request: ${body.input.joinToString { it.content }}")
    }
}

private fun jsonResponse(json: String): ResponsesResponse =
    ResponsesResponse(
        output = listOf(
            ResponseOutputItem(
                type = "message",
                content = listOf(ResponseContentPart(type = "output_text", text = json))
            )
        )
    )

private fun textResponse(text: String): ResponsesResponse = jsonResponse(text)
