package ru.zis.prompting.network

import android.util.Log
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.POST
import ru.zis.prompting.BuildConfig
import ru.zis.prompting.data.ResponsesRequest
import ru.zis.prompting.data.ResponsesResponse
import java.util.concurrent.TimeUnit

interface RouterAiApi {
    @POST("responses")
    suspend fun createResponse(@Body body: ResponsesRequest): ResponsesResponse
}

object RouterAiApiFactory {
    fun create(): RouterAiApi {
        val json = Json {
            ignoreUnknownKeys = true
        }

        val logging = HttpLoggingInterceptor { msg -> Log.d("RouterAI_HTTP", msg) }.apply {
            level = HttpLoggingInterceptor.Level.BODY
            redactHeader("Authorization") // чтобы не утек API key в логи
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(120, TimeUnit.SECONDS) // установка соединения
            .writeTimeout(120, TimeUnit.SECONDS)   // отправка тела запроса
            .readTimeout(120, TimeUnit.SECONDS)    // ожидание ответа/чтение
            .callTimeout(120, TimeUnit.SECONDS)
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
















