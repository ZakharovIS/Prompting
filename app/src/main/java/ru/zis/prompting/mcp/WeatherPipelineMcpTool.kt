package ru.zis.prompting.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class WeatherPipelineMcpTool(
    private val geocodingTool: GeocodingMcpTool,
    private val sunriseSunsetTool: SunriseSunsetMcpTool,
    private val multiFetchTool: WeatherMultiFetchMcpTool,
    private val saveReportTool: WeatherSaveReportMcpTool
) : McpTool {

    override val definition: McpToolDefinition = McpToolDefinition(
        name = "weather_pipeline",
        description = "Автоматический пайплайн: geocode -> sunrise/sunset -> weather -> save",
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

        WeatherPipelineTracker.startRun(
            runId = runId,
            steps = listOf(
                PipelineStepState(order = 1, name = "geocode_address", status = PipelineStepStatus.PENDING),
                PipelineStepState(order = 2, name = "sunrise_sunset", status = PipelineStepStatus.PENDING),
                PipelineStepState(order = 3, name = "weather_multi_fetch", status = PipelineStepStatus.PENDING),
                PipelineStepState(order = 4, name = "weather_save_report", status = PipelineStepStatus.PENDING)
            )
        )

        // Step 1: geocoding
        WeatherPipelineTracker.updateStep(runId, 1, PipelineStepStatus.RUNNING, "Получение координат для ${cities.size} городов...")

        val geocoded = mutableMapOf<String, JsonObject>()
        val geocodeFailures = mutableListOf<JsonObject>()

        cities.forEachIndexed { index, city ->
            WeatherPipelineTracker.updateStep(
                runId,
                1,
                PipelineStepStatus.RUNNING,
                "[${index + 1}/${cities.size}] Геокодирование: $city"
            )

            val geocodeResult = geocodingTool.call(
                buildJsonObject {
                    put("address", JsonPrimitive(city))
                    put("lang", JsonPrimitive("ru"))
                    put("limit", JsonPrimitive(1))
                }
            )

            val found = geocodeResult.payload["found"]?.jsonPrimitive?.contentOrNull == "true"
            val lat = geocodeResult.payload["lat"]?.jsonPrimitive?.doubleOrNull
            val lon = geocodeResult.payload["lon"]?.jsonPrimitive?.doubleOrNull

            if (!geocodeResult.isError && found && lat != null && lon != null) {
                geocoded[city] = buildJsonObject {
                    put("city", JsonPrimitive(city))
                    put("lat", JsonPrimitive(lat))
                    put("lon", JsonPrimitive(lon))
                    put(
                        "formatted",
                        JsonPrimitive(
                            geocodeResult.payload["formatted"]?.jsonPrimitive?.contentOrNull ?: city
                        )
                    )
                }
            } else {
                geocodeFailures += buildJsonObject {
                    put("city", JsonPrimitive(city))
                    put("error", JsonPrimitive(geocodeResult.content))
                }
            }
        }

        if (geocoded.isEmpty()) {
            val message = "Не удалось получить координаты ни для одного города."
            WeatherPipelineTracker.updateStep(runId, 1, PipelineStepStatus.ERROR, message)
            WeatherPipelineTracker.finishRun(runId, message, isError = true)
            return McpToolResult(
                isError = true,
                content = message,
                payload = buildJsonObject {
                    put("geocodeFailures", JsonArray(geocodeFailures))
                }
            )
        }
        WeatherPipelineTracker.updateStep(
            runId,
            1,
            PipelineStepStatus.DONE,
            "Координаты получены: ${geocoded.size}/${cities.size}"
        )

        // Step 2: sunrise/sunset
        WeatherPipelineTracker.updateStep(runId, 2, PipelineStepStatus.RUNNING, "Получение данных о рассвете/закате...")

        val sunByCity = mutableMapOf<String, JsonObject>()
        val sunFailures = mutableListOf<JsonObject>()

        geocoded.forEach { (city, geo) ->
            val lat = geo["lat"]?.jsonPrimitive?.doubleOrNull
            val lon = geo["lon"]?.jsonPrimitive?.doubleOrNull

            if (lat == null || lon == null) {
                sunFailures += buildJsonObject {
                    put("city", JsonPrimitive(city))
                    put("error", JsonPrimitive("missing_coordinates"))
                }
                return@forEach
            }

            val sunResult = sunriseSunsetTool.call(
                buildJsonObject {
                    put("lat", JsonPrimitive(lat))
                    put("lng", JsonPrimitive(lon))
                    put("date", JsonPrimitive("today"))
                }
            )

            if (sunResult.isError) {
                sunFailures += buildJsonObject {
                    put("city", JsonPrimitive(city))
                    put("error", JsonPrimitive(sunResult.content))
                }
            } else {
                sunByCity[city] = sunResult.payload
            }
        }

        WeatherPipelineTracker.updateStep(
            runId,
            2,
            if (sunByCity.isNotEmpty()) PipelineStepStatus.DONE else PipelineStepStatus.ERROR,
            "Sunrise/sunset: успешно ${sunByCity.size} из ${geocoded.size} (ошибок: ${sunFailures.size})"
        )

        if (sunByCity.isEmpty()) {
            val message = "Не удалось получить sunrise/sunset ни для одного города."
            WeatherPipelineTracker.finishRun(runId, message, isError = true)
            return McpToolResult(
                isError = true,
                content = message,
                payload = buildJsonObject {
                    put("geocoded", JsonObject(geocoded))
                    put("sunFailures", JsonArray(sunFailures))
                }
            )
        }

        // Step 3: weather fetch
        WeatherPipelineTracker.updateStep(runId, 3, PipelineStepStatus.RUNNING, "Сбор данных о погоде...")
        val fetch = multiFetchTool.call(
            buildJsonObject {
                put("cities", buildJsonArray {
                    geocoded.keys.forEach { add(JsonPrimitive(it)) }
                })
            }
        )

        if (fetch.isError) {
            WeatherPipelineTracker.updateStep(runId, 3, PipelineStepStatus.ERROR, fetch.content)
            WeatherPipelineTracker.finishRun(runId, fetch.content, isError = true)
            return fetch
        }
        val weatherSuccessCount = fetch.payload["successCount"]?.jsonPrimitive?.contentOrNull ?: "0"
        val weatherRequestedCount = fetch.payload["requestedCount"]?.jsonPrimitive?.contentOrNull ?: geocoded.size.toString()
        val weatherFailedCount = fetch.payload["failedCount"]?.jsonPrimitive?.contentOrNull ?: "0"
        WeatherPipelineTracker.updateStep(
            runId,
            3,
            PipelineStepStatus.DONE,
            "Погода: успешно $weatherSuccessCount из $weatherRequestedCount (ошибок: $weatherFailedCount)"
        )

        val weatherRows = fetch.payload["results"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject }
        val weatherFailures = fetch.payload["failures"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject }
        val weatherByCity = weatherRows.associateBy {
            it["requestedCity"]?.jsonPrimitive?.contentOrNull
                ?: it["location"]?.jsonPrimitive?.contentOrNull
                ?: ""
        }

        fun failureMessageByCity(rows: List<JsonObject>, city: String): String? {
            val row = rows.firstOrNull {
                it["city"]?.jsonPrimitive?.contentOrNull?.equals(city, ignoreCase = true) == true
            } ?: return null
            return row["error"]?.jsonPrimitive?.contentOrNull
        }

        val reportText = buildString {
            appendLine("Отчёт по погоде, рассвету/закату и золотому часу (${cities.size} городов):")
            cities.forEach { city ->
                val geo = geocoded[city]
                val lat = geo?.get("lat")?.jsonPrimitive?.doubleOrNull
                val lon = geo?.get("lon")?.jsonPrimitive?.doubleOrNull
                val sun = sunByCity[city]
                val weather = weatherByCity[city]
                val geocodeError = failureMessageByCity(geocodeFailures, city)
                val sunError = failureMessageByCity(sunFailures, city)
                val weatherError = failureMessageByCity(weatherFailures, city)

                appendLine()
                appendLine("• $city")
                appendLine("  - Координаты: ${lat ?: "?"}, ${lon ?: "?"}")
                appendLine("  - Рассвет: ${sun?.get("sunrise")?.jsonPrimitive?.contentOrNull ?: "н/д"}")
                appendLine("  - Закат: ${sun?.get("sunset")?.jsonPrimitive?.contentOrNull ?: "н/д"}")
                appendLine("  - Золотой час: ${sun?.get("goldenHour")?.jsonPrimitive?.contentOrNull ?: "н/д"}")
                appendLine("  - Погода: ${weather?.get("summary")?.jsonPrimitive?.contentOrNull ?: "н/д"}")
                geocodeError?.let { appendLine("  - Ошибка geocoding: $it") }
                sunError?.let { appendLine("  - Ошибка sunrise/sunset: $it") }
                weatherError?.let { appendLine("  - Ошибка weather: $it") }
            }
        }.trim()

        // Step 4: save
        WeatherPipelineTracker.updateStep(runId, 4, PipelineStepStatus.RUNNING, "Сохранение отчёта в БД...")
        val save = saveReportTool.call(
            buildJsonObject { put("reportText", JsonPrimitive(reportText)) }
        )

        if (save.isError) {
            WeatherPipelineTracker.updateStep(runId, 4, PipelineStepStatus.ERROR, save.content)
            WeatherPipelineTracker.finishRun(runId, save.content, isError = true)
            return save
        }

        WeatherPipelineTracker.updateStep(runId, 4, PipelineStepStatus.DONE, save.content)

        val finalText = buildString {
            appendLine("Пайплайн выполнен успешно.")
            appendLine("Координаты: успешно ${geocoded.size} из ${cities.size} (ошибок: ${geocodeFailures.size})")
            appendLine("Sunrise/sunset: успешно ${sunByCity.size} из ${geocoded.size} (ошибок: ${sunFailures.size})")
            appendLine("Погода: успешно $weatherSuccessCount из $weatherRequestedCount (ошибок: $weatherFailedCount)")
            appendLine()
            appendLine(reportText)
            append(save.content)
        }

        WeatherPipelineTracker.finishRun(runId, finalText, isError = false)

        return McpToolResult(
            isError = false,
            content = finalText,
            payload = buildJsonObject {
                put("runId", JsonPrimitive(runId))
                put("geocoded", JsonObject(geocoded))
                put("sunByCity", JsonObject(sunByCity))
                put("fetch", fetch.payload)
                put("save", save.payload)
                put("geocodeFailures", JsonArray(geocodeFailures))
                put("sunFailures", JsonArray(sunFailures))
            }
        )
    }
}
