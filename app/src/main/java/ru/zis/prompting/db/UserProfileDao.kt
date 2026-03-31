package ru.zis.prompting.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

@Dao
interface UserProfileDao {

    @Query("SELECT * FROM user_profiles ORDER BY updatedAt DESC")
    suspend fun getAll(): List<UserProfileEntity>

    @Query("SELECT * FROM user_profiles WHERE isActive = 1 LIMIT 1")
    suspend fun getActive(): UserProfileEntity?

    @Upsert
    suspend fun upsert(profile: UserProfileEntity)

    @Query("DELETE FROM user_profiles WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("UPDATE user_profiles SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE user_profiles SET isActive = 1 WHERE id = :id")
    suspend fun markActive(id: String)

    @Transaction
    suspend fun setActive(id: String) {
        clearActive()
        markActive(id)
    }
}
