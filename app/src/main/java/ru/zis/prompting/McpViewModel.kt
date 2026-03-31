 package ru.zis.prompting

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.ExperimentalMcpApi
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

data class McpToolUi(
    val name: String,
    val description: String?
)

class McpViewModel : ViewModel() {

    var serverUrl by mutableStateOf("http://10.0.2.2:3000/mcp")
    var loading by mutableStateOf(false)
        private set

    var isConnected by mutableStateOf(false)
        private set

    var error by mutableStateOf<String?>(null)
        private set

    val tools = mutableStateListOf<McpToolUi>()

    @OptIn(ExperimentalMcpApi::class)
    fun fetchTools() {
        val url = serverUrl.trim()
        if (url.isBlank()) {
            error = "Введите URL MCP сервера"
            return
        }

        loading = true
        isConnected = false
        error = null

        viewModelScope.launch(Dispatchers.IO) {
            val httpClient = HttpClient(OkHttp) {
                engine {
                    config {
                        connectTimeout(20, TimeUnit.SECONDS)
                        writeTimeout(20, TimeUnit.SECONDS)
                        // Для SSE поток может жить долго, поэтому отключаем read timeout.
                        readTimeout(0, TimeUnit.MILLISECONDS)
                    }
                }
                install(SSE)
            }

            try {
                val client = Client(
                    clientInfo = Implementation(
                        name = "prompting-android",
                        version = "1.0.0"
                    )
                )

                val transport = StreamableHttpClientTransport(
                    client = httpClient,
                    url = url
                )

                client.connect(transport)
                val remoteTools = client.listTools().tools

                withContext(Dispatchers.Main) {
                    tools.clear()
                    tools.addAll(
                        remoteTools.map { tool ->
                            McpToolUi(name = tool.name, description = tool.description)
                        }
                    )
                    isConnected = true
                    loading = false
                }
            } catch (t: Throwable) {
                withContext(Dispatchers.Main) {
                    tools.clear()
                    error = mapMcpError(t, url)
                    loading = false
                }
            } finally {
                httpClient.close()
            }
        }
    }

    private fun mapMcpError(t: Throwable, url: String): String {
        val raw = t.message ?: t.toString()
        val lowered = raw.lowercase()

        if ("timeout" in lowered || "okhttpssesession" in lowered) {
            return buildString {
                append("Таймаут MCP/SSE при подключении к: $url\n")
                append("Проверьте, что это именно Streamable HTTP MCP endpoint (SSE), а не stdio/ws.\n")
                append("Если сервер локальный: для эмулятора Android используйте 10.0.2.2 вместо localhost.\n")
                append("Если стоит прокси (nginx/cloudflare), убедитесь что SSE не буферизуется и не режется по таймауту.\n\n")
                append("Детали: $raw")
            }
        }

        return raw
    }
}
