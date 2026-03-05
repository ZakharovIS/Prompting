package ru.zis.prompting.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface InvariantDao {

    @Query("SELECT * FROM invariants ORDER BY createdAt ASC")
    suspend fun getAll(): List<InvariantEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: InvariantEntity)

    @Query("DELETE FROM invariants WHERE id = :id")
    suspend fun deleteById(id: String)
}
