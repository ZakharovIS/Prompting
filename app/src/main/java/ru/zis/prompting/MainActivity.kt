package ru.zis.prompting

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ChatScreen() } }
    }
}

/* -------------------- UI -------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val maxPromptChars = 1500
    var modelMenuExpanded by remember { mutableStateOf(false) }
    val t = vm.lastLatencyMs
    val u = vm.usage
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("RouterAI LLM chat", style = MaterialTheme.typography.titleMedium)

            ExposedDropdownMenuBox(
                expanded = modelMenuExpanded,
                onExpandedChange = { modelMenuExpanded = !modelMenuExpanded }
            ) {
                OutlinedTextField(
                    value = vm.selectedModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Модель") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(),
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelMenuExpanded) },
                )

                ExposedDropdownMenu(
                    expanded = modelMenuExpanded,
                    onDismissRequest = { modelMenuExpanded = false }
                ) {
                    vm.models.forEach { m ->
                        DropdownMenuItem(
                            text = { Text(m) },
                            onClick = {
                                vm.selectedModel = m
                                modelMenuExpanded = false
                            }
                        )
                    }
                }
            }

            OutlinedTextField(
                value = vm.prompt,
                onValueChange = { if (it.text.length <= maxPromptChars) vm.prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Промт") },
                supportingText = { Text("${vm.prompt.text.length} / $maxPromptChars") },
                minLines = 6,
                maxLines = 6
            )

            Text(
                text = "Температура: ${String.format(Locale.US, "%.2f", vm.temperature)}",
                style = MaterialTheme.typography.bodyMedium
            )

            Slider(
                value = vm.temperature,
                onValueChange = { vm.temperature = it },
                valueRange = 0f..2f
            )

            Text(
                text = buildString {
                    append("Latency: ")
                    append(if (t != null) "${t} ms" else "—")
                    append("    Tokens: ")
                    append(
                        if (u?.totalTokens != null) {
                            "in=${u.inputTokens ?: "?"} out=${u.outputTokens ?: "?"} total=${u.totalTokens}"
                        } else "—"
                    )
                },
                style = MaterialTheme.typography.bodySmall
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { vm.send() },
                    enabled = !vm.loading && vm.prompt.text.isNotBlank(),
                ) { Text(if (vm.loading) "Отправка..." else "Отправить") }

                Text(
                    text = vm.error ?: "",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            OutlinedTextField(
                value = vm.answer,
                onValueChange = {},
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                label = { Text("Ответ") },
                readOnly = true,
                minLines = 1,
                maxLines = Int.MAX_VALUE
            )
        }
    }

}

/* -------------------- ViewModel -------------------- */

class ChatViewModel : ViewModel() {
    var prompt by mutableStateOf(TextFieldValue(""))
    var temperature by mutableFloatStateOf(0.7f)
    var answer by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var lastLatencyMs by mutableStateOf<Long?>(null)
    var usage by mutableStateOf<Usage?>(null)

    private val api: RouterAiApi = RouterAiApiFactory.create()

    val models = listOf(
        "openai/gpt-4o-2024-11-20",
        "deepseek/deepseek-r1",
        "anthropic/claude-opus-4.6"
    )

    var selectedModel by mutableStateOf(models.first())

    fun send() {
        val text = prompt.text.trim()
        if (text.isBlank()) return

        loading = true
        error = null
        lastLatencyMs = null
        usage = null

        viewModelScope.launch(Dispatchers.IO) {
            val start = SystemClock.elapsedRealtime()
            try {
                val resp = api.createResponse(
                    ResponsesRequest(
                        model = selectedModel,
                        input = listOf(InputMessage(role = "user", content = text)),
                        stream = false,
                        temperature = temperature,
                    )
                )
                val elapsed = SystemClock.elapsedRealtime() - start

                if (resp.error != null) throw IllegalStateException(
                    resp.error.message ?: "RouterAI error"
                )

                val content = resp.extractText()

                launch(Dispatchers.Main) {
                    lastLatencyMs = elapsed
                    usage = resp.usage
                    answer = content.ifBlank { "(пустой ответ)" }
                    loading = false
                }
            } catch (t: Throwable) {
                val elapsed = SystemClock.elapsedRealtime() - start
                launch(Dispatchers.Main) {
                    lastLatencyMs = elapsed
                    error = t.message ?: t.toString()
                    loading = false
                }
            }
        }
    }
}

/* -------------------- Network -------------------- */

private interface RouterAiApi {
    @POST("responses")
    suspend fun createResponse(@Body body: ResponsesRequest): ResponsesResponse
}

private object RouterAiApiFactory {
    fun create(): RouterAiApi {
        val json = Json {
            ignoreUnknownKeys = true
        }

        val logging = HttpLoggingInterceptor { msg -> Log.d("RouterAI_HTTP", msg) }.apply {
            level = HttpLoggingInterceptor.Level.BODY
            redactHeader("Authorization") // чтобы не утек API key в логи
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS) // установка соединения
            .writeTimeout(30, TimeUnit.SECONDS)   // отправка тела запроса
            .readTimeout(60, TimeUnit.SECONDS)    // ожидание ответа/чтение
            .callTimeout(90, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val key = BuildConfig.ROUTERAI_API_KEY
                val req: Request = chain.request().newBuilder()
                    .addHeader("Authorization", "Bearer $key")
                    .addHeader("Content-Type", "application/json")
                    .build()
                chain.proceed(req)
            }
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://routerai.ru/api/v1/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(RouterAiApi::class.java)
    }
}

/* -------------------- Models (Responses) -------------------- */

@Serializable
private data class ResponsesRequest(
    val model: String,
    val input: List<InputMessage>,
    val stream: Boolean = false,
    val temperature: Float? = null,
    @SerialName("top_p") val topP: Float? = null,
    val reasoning: Reasoning? = null,
    @SerialName("max_output_tokens") val maxOutputTokens: Int? = null
)

@Serializable
private data class Reasoning(
    val effort: String? = null // "none", "low", "medium", "high" — если поддерживается
)

@Serializable
private data class InputMessage(
    val role: String,
    val content: String
)

@Serializable
private data class ResponsesResponse(
    val output: List<ResponseOutputItem> = emptyList(),
    val usage: Usage? = null,
    val error: RouterAiError? = null
) {
    fun extractText(): String =
        output
            .asSequence()
            .filter { it.type == "message" }
            .flatMap { it.content.asSequence() }
            .filter { part ->
                val t = part.type
                t == "output_text" || t == "text"
            }
            .mapNotNull { it.text }
            .joinToString(separator = "")
}

@Serializable
data class Usage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null
)

@Serializable
private data class ResponseOutputItem(
    val id: String? = null,
    val type: String? = null,   // "message"
    val role: String? = null,   // "assistant"
    val status: String? = null,
    val content: List<ResponseContentPart> = emptyList()
)

@Serializable
private data class ResponseContentPart(
    val type: String? = null,   // "output_text"
    val text: String? = null
)

@Serializable
private data class RouterAiError(
    val message: String? = null,
    val type: String? = null
)