package ru.zis.prompting.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ru.zis.prompting.db.WeatherRepository

class WeatherSaveReportMcpTool(
    private val weatherRepository: WeatherRepository
) : McpTool {

    override val definition: McpToolDefinition = McpToolDefinition(
        name = "weather_save_report",
        description = "Сохраняет агрегированный weather-отчёт в БД",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("reportText", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Текст отчёта для сохранения"))
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("reportText")) })
        }
    )

    override suspend fun call(arguments: kotlinx.serialization.json.JsonObject): McpToolResult {
        val reportText = arguments["reportText"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

        if (reportText.isBlank()) {
            return McpToolResult(
                isError = true,
                content = "Параметр 'reportText' обязателен.",
                payload = buildJsonObject { put("error", JsonPrimitive("missing_report_text")) }
            )
        }

        val now = System.currentTimeMillis()
        weatherRepository.addRecord(
            location = "MULTI_CITY_REPORT",
            timestampEpochMs = now,
            temperatureC = null,
            conditions = "aggregated",
            summary = reportText
        )

        return McpToolResult(
            isError = false,
            content = "Отчёт сохранён в БД.",
            payload = buildJsonObject {
                put("saved", JsonPrimitive(true))
                put("location", JsonPrimitive("MULTI_CITY_REPORT"))
                put("timestampEpochMs", JsonPrimitive(now))
            }
        )
    }
}
