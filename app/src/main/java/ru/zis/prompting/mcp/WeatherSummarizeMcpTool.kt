package ru.zis.prompting.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WeatherSummarizeMcpTool : McpTool {

    override val definition: McpToolDefinition = McpToolDefinition(
        name = "weather_summarize",
        description = "Строит сводку по массиву погодных измерений (min/max/avg)",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("results", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("Массив результатов weather_multi_fetch"))
                    put("items", buildJsonObject {
                        put("type", JsonPrimitive("object"))
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("results")) })
        }
    )

    override suspend fun call(arguments: kotlinx.serialization.json.JsonObject): McpToolResult {
        val items = arguments["results"]?.jsonArray.orEmpty()

        if (items.isEmpty()) {
            return McpToolResult(
                isError = true,
                content = "Параметр 'results' обязателен и не должен быть пустым.",
                payload = buildJsonObject { put("error", JsonPrimitive("missing_results")) }
            )
        }

        data class Row(val location: String, val temperatureC: Double?, val summary: String)

        val rows = items.mapNotNull { el ->
            val obj = el.jsonObject
            val location = obj["location"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (location.isBlank()) return@mapNotNull null

            Row(
                location = location,
                temperatureC = obj["temperatureC"]?.jsonPrimitive?.doubleOrNull,
                summary = obj["summary"]?.jsonPrimitive?.contentOrNull ?: location
            )
        }

        if (rows.isEmpty()) {
            return McpToolResult(
                isError = true,
                content = "В 'results' нет валидных элементов.",
                payload = buildJsonObject { put("error", JsonPrimitive("invalid_results")) }
            )
        }

        val withTemp = rows.filter { it.temperatureC != null }
        val avg = withTemp.mapNotNull { it.temperatureC }.takeIf { it.isNotEmpty() }?.average()
        val min = withTemp.minByOrNull { it.temperatureC ?: Double.MAX_VALUE }
        val max = withTemp.maxByOrNull { it.temperatureC ?: -Double.MAX_VALUE }

        val reportText = buildString {
            appendLine("Сводка по погоде (${rows.size} городов):")
            if (avg != null) {
                appendLine("- Средняя температура: ${"%.1f".format(avg)}°C")
            }
            min?.temperatureC?.let { appendLine("- Минимум: ${"%.1f".format(it)}°C (${min.location})") }
            max?.temperatureC?.let { appendLine("- Максимум: ${"%.1f".format(it)}°C (${max.location})") }
            appendLine("- Детали:")
            rows.forEach { appendLine("  • ${it.summary}") }
        }.trim()

        return McpToolResult(
            isError = false,
            content = reportText,
            payload = buildJsonObject {
                put("count", JsonPrimitive(rows.size))
                avg?.let { put("averageTemperatureC", JsonPrimitive(it)) }
                min?.let {
                    put("min", buildJsonObject {
                        put("location", JsonPrimitive(it.location))
                        it.temperatureC?.let { temp -> put("temperatureC", JsonPrimitive(temp)) }
                    })
                }
                max?.let {
                    put("max", buildJsonObject {
                        put("location", JsonPrimitive(it.location))
                        it.temperatureC?.let { temp -> put("temperatureC", JsonPrimitive(temp)) }
                    })
                }
                put("rows", JsonArray(rows.map { row ->
                    buildJsonObject {
                        put("location", JsonPrimitive(row.location))
                        row.temperatureC?.let { put("temperatureC", JsonPrimitive(it)) }
                        put("summary", JsonPrimitive(row.summary))
                    }
                }))
                put("reportText", JsonPrimitive(reportText))
            }
        )
    }
}
