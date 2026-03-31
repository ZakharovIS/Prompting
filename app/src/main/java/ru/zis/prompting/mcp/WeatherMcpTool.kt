package ru.zis.prompting.mcp

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import ru.zis.prompting.BuildConfig
import ru.zis.prompting.db.WeatherRepository
import ru.zis.prompting.weather.WeatherApi

class WeatherMcpTool(
    private val weatherRepository: WeatherRepository
) : McpTool {

    override val definition: McpToolDefinition = McpToolDefinition(
        name = "get_weather_now",
        description = "Получает текущую погоду по локации через Visual Crossing и при необходимости сохраняет измерение в БД",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("location", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Город/регион, например Moscow или Saint Petersburg"))
                })
                put("save", buildJsonObject {
                    put("type", JsonPrimitive("boolean"))
                    put("description", JsonPrimitive("Сохранить результат в локальную БД (по умолчанию true)"))
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("location")) })
        }
    )

    override suspend fun call(arguments: kotlinx.serialization.json.JsonObject): McpToolResult {
        val location = arguments["location"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val save = arguments["save"]?.jsonPrimitive?.booleanOrNull ?: true

        if (location.isBlank()) {
            return McpToolResult(
                isError = true,
                content = "Параметр 'location' обязателен.",
                payload = buildJsonObject { put("error", JsonPrimitive("missing_location")) }
            )
        }

        val api = WeatherApi(apiKey = BuildConfig.VISUAL_CROSSING_API_KEY)
        return api.fetchCurrent(location)
            .fold(
                onSuccess = { snapshot ->
                    if (save) {
                        weatherRepository.addRecord(
                            location = snapshot.location,
                            timestampEpochMs = snapshot.timestampEpochMs,
                            temperatureC = snapshot.temperatureC,
                            conditions = snapshot.conditions,
                            summary = snapshot.summary
                        )
                    }

                    McpToolResult(
                        isError = false,
                        content = snapshot.summary,
                        payload = buildJsonObject {
                            put("location", JsonPrimitive(snapshot.location))
                            snapshot.temperatureC?.let { put("temperatureC", JsonPrimitive(it)) }
                            snapshot.conditions?.let { put("conditions", JsonPrimitive(it)) }
                            put("timestampEpochMs", JsonPrimitive(snapshot.timestampEpochMs))
                            put("summary", JsonPrimitive(snapshot.summary))
                            put("saved", JsonPrimitive(save))
                        }
                    )
                },
                onFailure = { t ->
                    McpToolResult(
                        isError = true,
                        content = "Ошибка получения погоды: ${t.message ?: t}",
                        payload = buildJsonObject { put("error", JsonPrimitive("weather_fetch_failed")) }
                    )
                }
            )
    }
}
