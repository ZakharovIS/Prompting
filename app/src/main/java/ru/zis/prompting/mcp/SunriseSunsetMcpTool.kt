package ru.zis.prompting.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class SunriseSunsetMcpTool(
    private val httpClient: OkHttpClient = OkHttpClient()
) : McpTool {

    private val json = Json { ignoreUnknownKeys = true }

    override val definition: McpToolDefinition = McpToolDefinition(
        name = "sunrise_sunset",
        description = "Возвращает данные о рассвете, закате и золотом часе по координатам (sunrisesunset.io, с fallback на sunrise-sunset.org)",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("lat", buildJsonObject {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("Широта"))
                })
                put("lng", buildJsonObject {
                    put("type", JsonPrimitive("number"))
                    put("description", JsonPrimitive("Долгота"))
                })
                put("date", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Дата запроса, например today или 2026-03-13"))
                })
            })
            put("required", buildJsonArray {
                add(JsonPrimitive("lat"))
                add(JsonPrimitive("lng"))
            })
        }
    )

    override suspend fun call(arguments: kotlinx.serialization.json.JsonObject): McpToolResult {
        val lat = arguments["lat"]?.jsonPrimitive?.doubleOrNull
        val lng = arguments["lng"]?.jsonPrimitive?.doubleOrNull
        val date = arguments["date"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "today" }

        if (lat == null || lng == null) {
            return McpToolResult(
                isError = true,
                content = "Параметры 'lat' и 'lng' обязательны.",
                payload = buildJsonObject { put("error", JsonPrimitive("missing_coordinates")) }
            )
        }

        val mainUrl = "https://api.sunrisesunset.io/json"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lng", lng.toString())
            .addQueryParameter("date", date)
            .build()

        val fallbackUrl = "https://api.sunrise-sunset.org/json"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("lat", lat.toString())
            .addQueryParameter("lng", lng.toString())
            .addQueryParameter("formatted", "0")
            .build()

        val primary = fetchFromSunriseSunsetIo(mainUrl)
        if (!primary.isError) return primary

        val fallback = fetchFromSunriseSunsetOrg(fallbackUrl, lat, lng, date)
        if (!fallback.isError) return fallback

        return McpToolResult(
            isError = true,
            content = "Не удалось получить sunrise/sunset ни из основного, ни из резервного источника.",
            payload = buildJsonObject {
                put("error", JsonPrimitive("all_providers_failed"))
                put("primaryError", JsonPrimitive(primary.content))
                put("fallbackError", JsonPrimitive(fallback.content))
                put("lat", JsonPrimitive(lat))
                put("lng", JsonPrimitive(lng))
                put("date", JsonPrimitive(date))
            }
        )
    }

    private fun fetchFromSunriseSunsetIo(url: okhttp3.HttpUrl): McpToolResult {
        val request = Request.Builder().url(url).get().build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use McpToolResult(
                        isError = true,
                        content = "sunrisesunset.io HTTP error: ${response.code}",
                        payload = buildJsonObject {
                            put("error", JsonPrimitive("http_error"))
                            put("status", JsonPrimitive(response.code))
                        }
                    )
                }

                val body = response.body?.string().orEmpty()
                if (body.trimStart().startsWith("<!DOCTYPE html", ignoreCase = true)) {
                    return@use McpToolResult(
                        isError = true,
                        content = "sunrisesunset.io вернул HTML (возможен Cloudflare challenge).",
                        payload = buildJsonObject { put("error", JsonPrimitive("html_instead_of_json")) }
                    )
                }

                val root = json.parseToJsonElement(body).jsonObject
                val status = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val results = root["results"]?.jsonObject

                if (status != "OK" || results == null) {
                    return@use McpToolResult(
                        isError = true,
                        content = "sunrisesunset.io вернул некорректный ответ.",
                        payload = buildJsonObject {
                            put("error", JsonPrimitive("bad_response"))
                            put("statusText", JsonPrimitive(status))
                        }
                    )
                }

                val sunrise = results["sunrise"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val sunset = results["sunset"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val goldenHour = results["golden_hour"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val solarNoon = results["solar_noon"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val dayLength = results["day_length"]?.jsonPrimitive?.contentOrNull.orEmpty()

                McpToolResult(
                    isError = false,
                    content = "Рассвет: $sunrise, закат: $sunset, золотой час: $goldenHour",
                    payload = buildJsonObject {
                        put("provider", JsonPrimitive("sunrisesunset.io"))
                        put("sunrise", JsonPrimitive(sunrise))
                        put("sunset", JsonPrimitive(sunset))
                        put("goldenHour", JsonPrimitive(goldenHour))
                        put("solarNoon", JsonPrimitive(solarNoon))
                        put("dayLength", JsonPrimitive(dayLength))
                    }
                )
            }
        }.getOrElse { t ->
            McpToolResult(
                isError = true,
                content = "Ошибка запроса к sunrisesunset.io: ${t.message ?: t}",
                payload = buildJsonObject { put("error", JsonPrimitive("exception")) }
            )
        }
    }

    private fun fetchFromSunriseSunsetOrg(
        url: okhttp3.HttpUrl,
        lat: Double,
        lng: Double,
        date: String
    ): McpToolResult {
        val request = Request.Builder().url(url).get().build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use McpToolResult(
                        isError = true,
                        content = "sunrise-sunset.org HTTP error: ${response.code}",
                        payload = buildJsonObject {
                            put("error", JsonPrimitive("http_error"))
                            put("status", JsonPrimitive(response.code))
                        }
                    )
                }

                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                val status = root["status"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val results = root["results"]?.jsonObject

                if (status != "OK" || results == null) {
                    return@use McpToolResult(
                        isError = true,
                        content = "sunrise-sunset.org вернул некорректный ответ.",
                        payload = buildJsonObject {
                            put("error", JsonPrimitive("bad_response"))
                            put("statusText", JsonPrimitive(status))
                        }
                    )
                }

                val sunrise = results["sunrise"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val sunset = results["sunset"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val civilBegin = results["civil_twilight_begin"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val civilEnd = results["civil_twilight_end"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val solarNoon = results["solar_noon"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val dayLengthRaw = results["day_length"]?.jsonPrimitive?.contentOrNull
                    ?: results["day_length"]?.jsonPrimitive?.toString().orEmpty()

                val goldenHourText = buildString {
                    append("утро: ")
                    append(if (civilBegin.isNotBlank()) civilBegin else "н/д")
                    append(", вечер: ")
                    append(if (civilEnd.isNotBlank()) civilEnd else "н/д")
                }

                McpToolResult(
                    isError = false,
                    content = "Рассвет: $sunrise, закат: $sunset, золотой час: $goldenHourText",
                    payload = buildJsonObject {
                        put("provider", JsonPrimitive("sunrise-sunset.org"))
                        put("lat", JsonPrimitive(lat))
                        put("lng", JsonPrimitive(lng))
                        put("date", JsonPrimitive(date))
                        put("sunrise", JsonPrimitive(sunrise))
                        put("sunset", JsonPrimitive(sunset))
                        put("goldenHour", JsonPrimitive(goldenHourText))
                        put("solarNoon", JsonPrimitive(solarNoon))
                        put("dayLength", JsonPrimitive(dayLengthRaw))
                        put("civilTwilightBegin", JsonPrimitive(civilBegin))
                        put("civilTwilightEnd", JsonPrimitive(civilEnd))
                    }
                )
            }
        }.getOrElse { t ->
            McpToolResult(
                isError = true,
                content = "Ошибка запроса к sunrise-sunset.org: ${t.message ?: t}",
                payload = buildJsonObject { put("error", JsonPrimitive("exception")) }
            )
        }
    }
}
