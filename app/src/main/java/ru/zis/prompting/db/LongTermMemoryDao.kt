package ru.zis.prompting.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface LongTermMemoryDao {

    @Query("SELECT * FROM long_term_memory ORDER BY createdAt ASC")
    suspend fun getAll(): List<LongTermMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<LongTermMemoryEntity>)

    @Query("DELETE FROM long_term_memory")
    suspend fun clearAll()
}
