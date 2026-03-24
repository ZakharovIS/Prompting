package ru.zis.prompting.network

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import ru.zis.prompting.data.OllamaChatRequest
import ru.zis.prompting.data.OllamaChatResponse
import java.util.concurrent.TimeUnit

interface OllamaApi {
    @POST("api/chat")
    suspend fun chat(@Body body: OllamaChatRequest): OllamaChatResponse
}

object OllamaApiFactory {
    fun create(baseUrl: String = "http://10.0.2.2:11434/"): OllamaApi {
        val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

        val logging = HttpLoggingInterceptor { msg -> Log.d("OLLAMA_HTTP", msg) }.apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

        return retrofit.create(OllamaApi::class.java)
    }
}
