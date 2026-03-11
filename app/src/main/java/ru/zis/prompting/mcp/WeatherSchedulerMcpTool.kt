package ru.zis.prompting.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import ru.zis.prompting.db.WeatherRepository
import ru.zis.prompting.weather.WeatherScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WeatherSchedulerMcpTool(
    private val weatherRepository: WeatherRepository,
    private val scheduler: WeatherScheduler
) : McpTool {

    override val definition: McpToolDefinition = McpToolDefinition(
        name = "weather_scheduler",
        description = "Управляет периодическим сбором погоды (start/stop/status/summary)",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("action", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Действие: start | stop | status | summary"))
                })
                put("location", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Локация для start"))
                })
                put("intervalMinutes", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Интервал в минутах для start (минимум 15, по умолчанию 60)"))
                })
                put("runNow", buildJsonObject {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("Сделать немедленный запуск после старта (по умолчанию true)"))
                })
                put("limit", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Количество записей для summary (1..100, по умолчанию 10)"))
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("action")) })
        }
    )

    override suspend fun call(arguments: kotlinx.serialization.json.JsonObject): McpToolResult {
        val action = arguments["action"]?.jsonPrimitive?.contentOrNull?.trim()?.lowercase().orEmpty()
        return when (action) {
            "start" -> start(arguments)
            "stop" -> stop()
            "status" -> status()
            "summary" -> summary(arguments)
            else -> McpToolResult(
                isError = true,
                content = "Неизвестный action. Используйте: start | stop | status | summary",
                payload = buildJsonObject { put("error", JsonPrimitive("invalid_action")) }
            )
        }
    }

    private suspend fun start(arguments: kotlinx.serialization.json.JsonObject): McpToolResult {
        val location = arguments["location"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (location.isBlank()) {
            return McpToolResult(
                isError = true,
                content = "Для action=start параметр 'location' обязателен.",
                payload = buildJsonObject { put("error", JsonPrimitive("missing_location")) }
            )
        }

        val interval = arguments["intervalMinutes"]?.jsonPrimitive?.intOrNull?.toLong() ?: 60L
        val runNow = arguments["runNow"]?.jsonPrimitive?.booleanOrNull ?: true
        val normalized = interval.coerceAtLeast(15)

        val startResult = scheduler.start(location = location, intervalMinutes = normalized, runNow = runNow)

        return McpToolResult(
            isError = false,
            content = buildString {
                append("Расписание запущено: каждые $normalized мин, локация '$location'.")
                if (startResult.runNow) {
                    if (startResult.immediateError == null) {
                        append(" Немедленный показ в шторке выполнен.")
                    } else {
                        append(" Немедленный показ не удался: ${startResult.immediateError}")
                    }
                }
            },
            payload = buildJsonObject {
                put("active", JsonPrimitive(true))
                put("location", JsonPrimitive(location))
                put("intervalMinutes", JsonPrimitive(normalized))
                put("runNow", JsonPrimitive(runNow))
                startResult.notificationPosted?.let { put("notificationPosted", JsonPrimitive(it)) }
                startResult.immediateSummary?.let { put("immediateSummary", JsonPrimitive(it)) }
                startResult.immediateError?.let { put("immediateError", JsonPrimitive(it)) }
            }
        )
    }

    private fun stop(): McpToolResult {
        scheduler.stop()
        return McpToolResult(
            isError = false,
            content = "Расписание погоды остановлено.",
            payload = buildJsonObject { put("active", JsonPrimitive(false)) }
        )
    }

    private suspend fun status(): McpToolResult {
        val status = scheduler.status()
        val text = if (status.active) {
            "Расписание активно: ${status.location ?: "unknown"}, интервал ${status.intervalMinutes ?: "?"} мин."
        } else {
            "Расписание не активно."
        }

        return McpToolResult(
            isError = false,
            content = text,
            payload = buildJsonObject {
                put("active", JsonPrimitive(status.active))
                status.location?.let { put("location", JsonPrimitive(it)) }
                status.intervalMinutes?.let { put("intervalMinutes", JsonPrimitive(it)) }
            }
        )
    }

    private suspend fun summary(arguments: kotlinx.serialization.json.JsonObject): McpToolResult {
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 100) ?: 10
        val latest = weatherRepository.getLatest(limit)

        if (latest.isEmpty()) {
            return McpToolResult(
                isError = false,
                content = "Нет сохранённых измерений погоды.",
                payload = buildJsonObject {
                    put("count", JsonPrimitive(0))
                }
            )
        }

        val avgTemp = latest.mapNotNull { it.temperatureC }.takeIf { it.isNotEmpty() }?.average()
        val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        val rowsText = latest.joinToString("\n") { item ->
            val ts = formatter.format(Date(item.timestampEpochMs))
            "- [$ts] ${item.location}: ${item.summary}"
        }

        return McpToolResult(
            isError = false,
            content = buildString {
                appendLine("Сводка по погоде (${latest.size} записей):")
                if (avgTemp != null) appendLine("Средняя температура: ${"%.1f".format(avgTemp)}°C")
                append(rowsText)
            },
            payload = buildJsonObject {
                put("count", JsonPrimitive(latest.size))
                avgTemp?.let { put("averageTemperatureC", JsonPrimitive(it)) }
            }
        )
    }
}
