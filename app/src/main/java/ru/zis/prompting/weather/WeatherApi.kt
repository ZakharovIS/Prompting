package ru.zis.prompting.weather

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

data class WeatherSnapshot(
    val location: String,
    val timestampEpochMs: Long,
    val temperatureC: Double?,
    val conditions: String?,
    val summary: String
)

class WeatherApi(
    private val apiKey: String,
    private val httpClient: OkHttpClient = OkHttpClient()
) {

    private val json = Json { ignoreUnknownKeys = true }

    fun fetchCurrent(location: String, unitGroup: String = "metric", lang: String = "ru"): Result<WeatherSnapshot> {
        val normalizedLocation = location.trim()
        if (normalizedLocation.isBlank()) return Result.failure(IllegalArgumentException("location is blank"))
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("VISUAL_CROSSING_API_KEY is blank"))

        val url = "https://weather.visualcrossing.com/VisualCrossingWebServices/rest/services/timeline"
            .toHttpUrl()
            .newBuilder()
            .addPathSegment(normalizedLocation)
            .addPathSegment("today")
            .addQueryParameter("unitGroup", unitGroup)
            .addQueryParameter("include", "current")
            .addQueryParameter("lang", lang)
            .addQueryParameter("key", apiKey)
            .addQueryParameter("contentType", "json")
            .build()

        val request = Request.Builder().url(url).get().build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    error("VisualCrossing HTTP error: ${response.code}")
                }

                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                parseSnapshot(root, normalizedLocation)
            }
        }
    }

    private fun parseSnapshot(root: JsonObject, fallbackLocation: String): WeatherSnapshot {
        val resolvedAddress = root["resolvedAddress"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: fallbackLocation

        val current = root["currentConditions"]?.jsonObject ?: JsonObject(emptyMap())
        val temp = current["temp"]?.jsonPrimitive?.doubleOrNull
        val conditions = current["conditions"]?.jsonPrimitive?.contentOrNull
        val epochSec = current["datetimeEpoch"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
        val epochMs = (epochSec?.times(1000)) ?: System.currentTimeMillis()

        val summary = buildString {
            append(resolvedAddress)
            append(": ")
            append(conditions ?: "без описания")
            if (temp != null) append(", ${"%.1f".format(temp)}°C")
        }

        return WeatherSnapshot(
            location = resolvedAddress,
            timestampEpochMs = epochMs,
            temperatureC = temp,
            conditions = conditions,
            summary = summary
        )
    }
}
