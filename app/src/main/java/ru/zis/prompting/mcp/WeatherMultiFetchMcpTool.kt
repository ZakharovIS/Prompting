package ru.zis.prompting.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import ru.zis.prompting.BuildConfig
import ru.zis.prompting.weather.WeatherApi

class WeatherMultiFetchMcpTool : McpTool {

    override val definition: McpToolDefinition = McpToolDefinition(
        name = "weather_multi_fetch",
        description = "Собирает текущую погоду по нескольким городам",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("cities", buildJsonObject {
                    put("type", JsonPrimitive("array"))
                    put("description", JsonPrimitive("Список городов, например [\"Moscow\", \"Kazan\"]"))
                    put("items", buildJsonObject {
                        put("type", JsonPrimitive("string"))
                    })
                })
            })
            put("required", buildJsonArray { add(JsonPrimitive("cities")) })
        }
    )

    override suspend fun call(arguments: kotlinx.serialization.json.JsonObject): McpToolResult {
        val cities = arguments["cities"]
            ?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.contentOrNull?.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()
            .orEmpty()

        if (cities.isEmpty()) {
            return McpToolResult(
                isError = true,
                content = "Параметр 'cities' обязателен и должен содержать минимум один город.",
                payload = buildJsonObject { put("error", JsonPrimitive("missing_cities")) }
            )
        }

        val api = WeatherApi(apiKey = BuildConfig.VISUAL_CROSSING_API_KEY)
        val results = mutableListOf<kotlinx.serialization.json.JsonObject>()
        val failures = mutableListOf<kotlinx.serialization.json.JsonObject>()

        cities.forEach { city ->
            api.fetchCurrent(city).fold(
                onSuccess = { snapshot ->
                    results += buildJsonObject {
                        put("requestedCity", JsonPrimitive(city))
                        put("location", JsonPrimitive(snapshot.location))
                        snapshot.temperatureC?.let { put("temperatureC", JsonPrimitive(it)) }
                        snapshot.conditions?.let { put("conditions", JsonPrimitive(it)) }
                        put("timestampEpochMs", JsonPrimitive(snapshot.timestampEpochMs))
                        put("summary", JsonPrimitive(snapshot.summary))
                    }
                },
                onFailure = { t ->
                    failures += buildJsonObject {
                        put("city", JsonPrimitive(city))
                        put("error", JsonPrimitive(t.message ?: t.toString()))
                    }
                }
            )
        }

        if (results.isEmpty()) {
            return McpToolResult(
                isError = true,
                content = "Не удалось получить погоду ни по одному городу.",
                payload = buildJsonObject {
                    put("requestedCount", JsonPrimitive(cities.size))
                    put("successCount", JsonPrimitive(0))
                    put("failedCount", JsonPrimitive(failures.size))
                    put("failures", JsonArray(failures))
                }
            )
        }

        return McpToolResult(
            isError = false,
            content = "Погода: успешно ${results.size} из ${cities.size} городов (ошибок: ${failures.size}).",
            payload = buildJsonObject {
                put("requestedCount", JsonPrimitive(cities.size))
                put("successCount", JsonPrimitive(results.size))
                put("failedCount", JsonPrimitive(failures.size))
                put("results", JsonArray(results))
                put("failures", JsonArray(failures))
            }
        )
    }
}
