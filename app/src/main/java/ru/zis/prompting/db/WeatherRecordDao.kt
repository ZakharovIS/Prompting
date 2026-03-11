package ru.zis.prompting.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface WeatherRecordDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: WeatherRecordEntity)

    @Query("SELECT * FROM weather_records ORDER BY timestampEpochMs DESC")
    suspend fun getAllDesc(): List<WeatherRecordEntity>

    @Query("SELECT * FROM weather_records ORDER BY timestampEpochMs DESC LIMIT :limit")
    suspend fun getLatest(limit: Int): List<WeatherRecordEntity>

    @Query("DELETE FROM weather_records")
    suspend fun clearAll()
}
