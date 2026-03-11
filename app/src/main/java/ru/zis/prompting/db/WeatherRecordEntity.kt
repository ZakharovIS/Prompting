package ru.zis.prompting.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "weather_records")
data class WeatherRecordEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val location: String,
    val timestampEpochMs: Long,
    val temperatureC: Double?,
    val conditions: String?,
    val summary: String
)
