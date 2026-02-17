package ru.zis.prompting

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { ChatScreen() } }
    }
}

/* -------------------- UI -------------------- */

@Composable
private fun ChatScreen(vm: ChatViewModel = viewModel()) {
    val maxPromptChars = 1500
    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("RouterAI LLM chat", style = MaterialTheme.typography.titleMedium)

            OutlinedTextField(
                value = vm.prompt,
                onValueChange = { if (it.text.length <= maxPromptChars) vm.prompt = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Промт") },
                supportingText = { Text("${vm.prompt.text.length} / $maxPromptChars") },
                minLines = 12,
                maxLines = 12
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
    var answer by mutableStateOf("")
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    private val api: RouterAiApi = RouterAiApiFactory.create()

    fun send() {
        val text = prompt.text.trim()
        if (text.isBlank()) return

        loading = true
        error = null

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val resp = api.chatCompletions(
                    ChatCompletionRequest(
                        model = "openai/gpt-5.2",
                        messages = listOf(Message(role = "user", content = text))
                    )
                )
                val content = resp.choices.firstOrNull()?.message?.content.orEmpty()

                launch(Dispatchers.Main) {
                    answer = content.ifBlank { "(пустой ответ)" }
                    loading = false
                }
            } catch (t: Throwable) {
                launch(Dispatchers.Main) {
                    error = t.message ?: t.toString()
                    loading = false
                }
            }
        }
    }
}

/* -------------------- Network -------------------- */

private interface RouterAiApi {
    @POST("chat/completions")
    suspend fun chatCompletions(@Body body: ChatCompletionRequest): ChatCompletionResponse
}

private object RouterAiApiFactory {
    fun create(): RouterAiApi {
        val json = Json {
            ignoreUnknownKeys = true
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
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl("https://routerai.ru/api/v1/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(RouterAiApi::class.java)
    }
}

/* -------------------- Models (OpenAI-like) -------------------- */

@Serializable
private data class ChatCompletionRequest(
    val model: String,
    val messages: List<Message>
)

@Serializable
private data class Message(
    val role: String,
    val content: String
)

@Serializable
private data class ChatCompletionResponse(
    val choices: List<Choice> = emptyList()
)

@Serializable
private data class Choice(
    val message: Message? = null,
    @SerialName("finish_reason") val finishReason: String? = null
)