package ru.zis.prompting.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

class WeatherPipelineMcpTool(
    private val multiFetchTool: WeatherMultiFetchMcpTool,
    private val summarizeTool: WeatherSummarizeMcpTool,
    private val saveReportTool: WeatherSaveReportMcpTool
) : McpTool {

    override val definition: McpToolDefinition = McpToolDefinition(
        name = "weather_pipeline",
        description = "Автоматический пайплайн: fetch -> summarize -> save",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("cities", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("Список городов для пайплайна"))
                    put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("cities")) })
        }
    )

    override suspend fun call(arguments: kotlinx.serialization.json.JsonObject): McpToolResult {
        val runId = System.currentTimeMillis()
        WeatherPipelineTracker.startRun(
            runId = runId,
            steps = listOf(
                PipelineStepState(order = 1, name = "weather_multi_fetch", status = PipelineStepStatus.PENDING),
                PipelineStepState(order = 2, name = "weather_summarize", status = PipelineStepStatus.PENDING),
                PipelineStepState(order = 3, name = "weather_save_report", status = PipelineStepStatus.PENDING)
            )
        )

        // Step 1: fetch
        WeatherPipelineTracker.updateStep(runId, 1, PipelineStepStatus.RUNNING, "Сбор данных...")
        val fetch = multiFetchTool.call(
            buildJsonObject {
                put("cities", arguments["cities"]?.jsonArray ?: buildJsonArray { })
            }
        )

        if (fetch.isError) {
            WeatherPipelineTracker.updateStep(runId, 1, PipelineStepStatus.ERROR, fetch.content)
            WeatherPipelineTracker.finishRun(runId, fetch.content, isError = true)
            return fetch
        }
        WeatherPipelineTracker.updateStep(runId, 1, PipelineStepStatus.DONE, fetch.content)

        // Step 2: summarize
        WeatherPipelineTracker.updateStep(runId, 2, PipelineStepStatus.RUNNING, "Агрегация...")
        val summarize = summarizeTool.call(
            buildJsonObject {
                put("results", fetch.payload["results"]?.jsonArray ?: buildJsonArray { })
            }
        )

        if (summarize.isError) {
            WeatherPipelineTracker.updateStep(runId, 2, PipelineStepStatus.ERROR, summarize.content)
            WeatherPipelineTracker.finishRun(runId, summarize.content, isError = true)
            return summarize
        }
        WeatherPipelineTracker.updateStep(runId, 2, PipelineStepStatus.DONE, "Сводка сформирована")

        // Step 3: save
        WeatherPipelineTracker.updateStep(runId, 3, PipelineStepStatus.RUNNING, "Сохранение в БД...")
        val reportText = summarize.payload["reportText"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val save = saveReportTool.call(
            buildJsonObject { put("reportText", JsonPrimitive(reportText)) }
        )

        if (save.isError) {
            WeatherPipelineTracker.updateStep(runId, 3, PipelineStepStatus.ERROR, save.content)
            WeatherPipelineTracker.finishRun(runId, save.content, isError = true)
            return save
        }

        WeatherPipelineTracker.updateStep(runId, 3, PipelineStepStatus.DONE, save.content)

        val finalText = buildString {
            appendLine("Пайплайн выполнен успешно.")
            appendLine(fetch.content)
            appendLine()
            appendLine(summarize.content)
            appendLine()
            append(save.content)
        }

        WeatherPipelineTracker.finishRun(runId, finalText, isError = false)

        return McpToolResult(
            isError = false,
            content = finalText,
            payload = buildJsonObject {
                put("runId", JsonPrimitive(runId))
                put("fetch", fetch.payload)
                put("summarize", summarize.payload)
                put("save", save.payload)
            }
        )
    }
}
