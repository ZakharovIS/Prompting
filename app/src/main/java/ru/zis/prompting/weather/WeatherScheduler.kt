package ru.zis.prompting.weather

import android.content.Context
import android.content.SharedPreferences
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zis.prompting.App
import ru.zis.prompting.BuildConfig
import java.util.concurrent.TimeUnit

class WeatherScheduler(context: Context) {

    private val appContext = context.applicationContext
    private val workManager = WorkManager.getInstance(appContext)
    private val prefs: SharedPreferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    suspend fun start(location: String, intervalMinutes: Long, runNow: Boolean): WeatherStartResult {
        val normalizedLocation = location.trim().ifBlank { WeatherWorker.DEFAULT_LOCATION }
        val normalizedInterval = intervalMinutes.coerceAtLeast(15)

        prefs.edit()
            .putString(KEY_PREF_LOCATION, normalizedLocation)
            .putLong(KEY_PREF_INTERVAL_MINUTES, normalizedInterval)
            .apply()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val periodic = PeriodicWorkRequestBuilder<WeatherWorker>(
            normalizedInterval,
            TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    WeatherWorker.KEY_LOCATION to normalizedLocation
                )
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            WeatherWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            periodic
        )

        if (!runNow) {
            return WeatherStartResult(
                runNow = false,
                immediateSummary = null,
                immediateError = null,
                notificationPosted = null
            )
        }

        return runImmediateNow(normalizedLocation)
    }

    fun stop() {
        workManager.cancelUniqueWork(WeatherWorker.UNIQUE_WORK_NAME)
        workManager.cancelUniqueWork("${WeatherWorker.UNIQUE_WORK_NAME}_now")

        prefs.edit()
            .remove(KEY_PREF_LOCATION)
            .remove(KEY_PREF_INTERVAL_MINUTES)
            .apply()
    }

    suspend fun status(): WeatherScheduleStatus = withContext(Dispatchers.IO) {
        val infos = runCatching {
            workManager.getWorkInfosForUniqueWork(WeatherWorker.UNIQUE_WORK_NAME).get()
        }.getOrDefault(emptyList())

        val active = infos.any { it.state == WorkInfo.State.ENQUEUED || it.state == WorkInfo.State.RUNNING }
        val location = prefs.getString(KEY_PREF_LOCATION, null)
        val intervalMinutes = prefs.getLong(KEY_PREF_INTERVAL_MINUTES, 0L).takeIf { it > 0 }

        WeatherScheduleStatus(
            active = active,
            location = location,
            intervalMinutes = intervalMinutes
        )
    }

    companion object {
        private const val PREFS_NAME = "weather_scheduler"
        private const val KEY_PREF_LOCATION = "location"
        private const val KEY_PREF_INTERVAL_MINUTES = "interval_minutes"
    }

    private suspend fun runImmediateNow(location: String): WeatherStartResult = withContext(Dispatchers.IO) {
        val api = WeatherApi(apiKey = BuildConfig.VISUAL_CROSSING_API_KEY)
        val app = appContext as? App
            ?: return@withContext WeatherStartResult(
                runNow = true,
                immediateSummary = null,
                immediateError = "App context недоступен",
                notificationPosted = false
            )

        api.fetchCurrent(location = location)
            .fold(
                onSuccess = { snapshot ->
                    val canNotify = WeatherNotifications.canPostNotifications(appContext)

                    app.weatherRepository.addRecord(
                        location = snapshot.location,
                        timestampEpochMs = snapshot.timestampEpochMs,
                        temperatureC = snapshot.temperatureC,
                        conditions = snapshot.conditions,
                        summary = snapshot.summary
                    )

                    if (canNotify) {
                        WeatherNotifications.showWeather(
                            context = appContext,
                            title = "Погода: ${snapshot.location}",
                            text = snapshot.summary,
                            id = (snapshot.timestampEpochMs % Int.MAX_VALUE).toInt()
                        )
                    }

                    WeatherStartResult(
                        runNow = true,
                        immediateSummary = snapshot.summary,
                        immediateError = if (canNotify) null else "Уведомления заблокированы системой или не выдано разрешение POST_NOTIFICATIONS.",
                        notificationPosted = canNotify
                    )
                },
                onFailure = { err ->
                    WeatherStartResult(
                        runNow = true,
                        immediateSummary = null,
                        immediateError = err.message ?: err.toString(),
                        notificationPosted = false
                    )
                }
            )
    }
}

data class WeatherScheduleStatus(
    val active: Boolean,
    val location: String?,
    val intervalMinutes: Long?
)

data class WeatherStartResult(
    val runNow: Boolean,
    val immediateSummary: String?,
    val immediateError: String?,
    val notificationPosted: Boolean?
)
