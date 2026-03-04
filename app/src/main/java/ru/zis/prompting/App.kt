package ru.zis.prompting

import android.app.Application
import ru.zis.prompting.db.AppDatabase
import ru.zis.prompting.db.ChatRepository
import ru.zis.prompting.db.UserProfileRepository

class App : Application() {

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
}
