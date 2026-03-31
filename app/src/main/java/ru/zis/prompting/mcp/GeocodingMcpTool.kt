package ru.zis.prompting.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import ru.zis.prompting.BuildConfig

class GeocodingMcpTool(
    private val apiKey: String = BuildConfig.GEOAPIFY_API_KEY,
    private val httpClient: OkHttpClient = OkHttpClient()
) : McpTool {

    private val json = Json { ignoreUnknownKeys = true }

    override val definition: McpToolDefinition = McpToolDefinition(
        name = "geocode_address",
        description = "Ищет координаты адреса через Geoapify Forward Geocoding API",
        inputSchema = buildJsonObject {
            put("type", JsonPrimitive("object"))
            put("properties", buildJsonObject {
                put("address", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Адрес или место для геокодирования"))
                })
                put("lang", buildJsonObject {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Язык результата, например ru или en"))
                })
                put("limit", buildJsonObject {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Максимум результатов (по умолчанию 1)"))
                })
            })
            put("required", kotlinx.serialization.json.buildJsonArray {
                add(JsonPrimitive("address"))
            })
        }
    )

    override suspend fun call(arguments: JsonObject): McpToolResult {
        val address = arguments["address"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        val lang = arguments["lang"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty().ifBlank { "ru" }
        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull?.coerceIn(1, 10) ?: 1

        if (address.isBlank()) {
            return McpToolResult(
                isError = true,
                content = "Параметр 'address' обязателен.",
                payload = buildJsonObject { put("error", JsonPrimitive("missing_address")) }
            )
        }

        if (apiKey.isBlank()) {
            return McpToolResult(
                isError = true,
                content = "Geoapify API key не задан.",
                payload = buildJsonObject { put("error", JsonPrimitive("missing_api_key")) }
            )
        }

        val url = "https://api.geoapify.com/v1/geocode/search"
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("text", address)
            .addQueryParameter("format", "json")
            .addQueryParameter("lang", lang)
            .addQueryParameter("limit", limit.toString())
            .addQueryParameter("apiKey", apiKey)
            .build()

        val request = Request.Builder().url(url).get().build()

        return runCatching {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@use McpToolResult(
                        isError = true,
                        content = "Geoapify HTTP error: ${response.code}",
                        payload = buildJsonObject {
                            put("error", JsonPrimitive("http_error"))
                            put("status", JsonPrimitive(response.code))
                        }
                    )
                }

                val body = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(body).jsonObject
                val results = root["results"]?.jsonArray.orEmpty()
                if (results.isEmpty()) {
                    return@use McpToolResult(
                        isError = false,
                        content = "Ничего не найдено по адресу: $address",
                        payload = buildJsonObject {
                            put("found", JsonPrimitive(false))
                        }
                    )
                }

                val first = results.first().jsonObject
                val lat = first["lat"]?.jsonPrimitive?.doubleOrNull
                val lon = first["lon"]?.jsonPrimitive?.doubleOrNull
                val formatted = first["formatted"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val country = first["country"]?.jsonPrimitive?.contentOrNull
                val city = first["city"]?.jsonPrimitive?.contentOrNull

                val payload = buildJsonObject {
                    put("found", JsonPrimitive(true))
                    lat?.let { put("lat", JsonPrimitive(it)) }
                    lon?.let { put("lon", JsonPrimitive(it)) }
                    put("formatted", JsonPrimitive(formatted))
                    if (!country.isNullOrBlank()) put("country", JsonPrimitive(country))
                    if (!city.isNullOrBlank()) put("city", JsonPrimitive(city))
                }

                McpToolResult(
                    isError = false,
                    content = "Найдено: $formatted (lat=${lat ?: "?"}, lon=${lon ?: "?"})",
                    payload = payload
                )
            }
        }.getOrElse { t ->
            McpToolResult(
                isError = true,
                content = "Ошибка при вызове Geoapify: ${t.message ?: t}",
                payload = buildJsonObject {
                    put("error", JsonPrimitive("exception"))
                }
            )
        }
    }
}
