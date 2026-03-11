package ru.zis.prompting.weather

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import ru.zis.prompting.App
import ru.zis.prompting.BuildConfig

class WeatherWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val location = inputData.getString(KEY_LOCATION)?.trim().orEmpty().ifBlank { DEFAULT_LOCATION }
        val api = WeatherApi(apiKey = BuildConfig.VISUAL_CROSSING_API_KEY)

        return api.fetchCurrent(location = location)
            .fold(
                onSuccess = { snapshot ->
                    val app = applicationContext as App
                    app.weatherRepository.addRecord(
                        location = snapshot.location,
                        timestampEpochMs = snapshot.timestampEpochMs,
                        temperatureC = snapshot.temperatureC,
                        conditions = snapshot.conditions,
                        summary = snapshot.summary
                    )

                    WeatherNotifications.showWeather(
                        context = applicationContext,
                        title = "Погода: ${snapshot.location}",
                        text = snapshot.summary,
                        id = (snapshot.timestampEpochMs % Int.MAX_VALUE).toInt()
                    )
                    Result.success()
                },
                onFailure = {
                    Result.retry()
                }
            )
    }

    companion object {
        const val UNIQUE_WORK_NAME = "periodic_weather_updates"
        const val KEY_LOCATION = "location"
        const val DEFAULT_LOCATION = "Moscow"
    }
}
