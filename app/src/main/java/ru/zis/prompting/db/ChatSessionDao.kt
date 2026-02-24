package ru.zis.prompting.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ChatSessionDao {

    /** Сохраняет или обновляет сессию (INSERT OR REPLACE). */
    @Upsert
    suspend fun upsert(session: ChatSessionEntity)

    /** Загружает текущую сессию (id = 1). */
    @Query("SELECT * FROM chat_sessions WHERE sessionId = 1 LIMIT 1")
    suspend fun loadCurrent(): ChatSessionEntity?

    /** Удаляет текущую сессию (используется при сбросе чата). */
    @Query("DELETE FROM chat_sessions WHERE sessionId = 1")
    suspend fun deleteCurrent()
}
