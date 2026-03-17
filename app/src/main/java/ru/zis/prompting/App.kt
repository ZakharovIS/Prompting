package ru.zis.prompting

import android.app.Application
import ru.zis.prompting.agent.RagRepository
import ru.zis.prompting.mcp.McpRegistry
import ru.zis.prompting.weather.WeatherNotifications
import ru.zis.prompting.db.AppDatabase
import ru.zis.prompting.db.ChatRepository
import ru.zis.prompting.db.InvariantRepository
import ru.zis.prompting.db.UserProfileRepository
import ru.zis.prompting.db.WeatherRepository

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        WeatherNotifications.createChannel(this)
    }

    val database by lazy { AppDatabase.getInstance(this) }

    val chatRepository by lazy {
        ChatRepository(
            chatDao = database.chatSessionDao(),
            longTermDao = database.longTermMemoryDao()
        )
    }

    val userProfileRepository by lazy {
        UserProfileRepository(
            dao = database.userProfileDao()
        )
    }

    val invariantRepository by lazy {
        InvariantRepository(
            dao = database.invariantDao()
        )
    }

    val weatherRepository by lazy {
        WeatherRepository(
            dao = database.weatherRecordDao()
        )
    }

    val mcpRegistry by lazy {
        McpRegistry.default(
            context = this,
            weatherRepository = weatherRepository
        )
    }

    val ragRepository by lazy {
        RagRepository(
            context = this,
            api = ru.zis.prompting.network.RouterAiApiFactory.create()
        )
    }
}
