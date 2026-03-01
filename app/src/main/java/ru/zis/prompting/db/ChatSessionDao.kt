package ru.zis.prompting.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface ChatSessionDao {

    /** Сохраняет или обновляет сессию (INSERT OR REPLACE). */
    @Upsert
    suspend fun upsert(session: ChatSessionEntity)

    /** Загружает сессию по ID (ID = ordinal стратегии). */
    @Query("SELECT * FROM chat_sessions WHERE sessionId = :sessionId LIMIT 1")
    suspend fun loadById(sessionId: Int): ChatSessionEntity?

    /** Удаляет сессию по ID стратегии. */
    @Query("DELETE FROM chat_sessions WHERE sessionId = :sessionId")
    suspend fun deleteById(sessionId: Int)

    /** Удаляет все стратегии (полный сброс). */
    @Query("DELETE FROM chat_sessions")
    suspend fun deleteAll()
}
