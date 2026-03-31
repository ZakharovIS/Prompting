package ru.zis.prompting.db

data class WeatherRecord(
    val id: Long = 0,
    val location: String,
    val timestampEpochMs: Long,
    val temperatureC: Double?,
    val conditions: String?,
    val summary: String
)

class WeatherRepository(
    private val dao: WeatherRecordDao
) {

    suspend fun addRecord(
        location: String,
        timestampEpochMs: Long,
        temperatureC: Double?,
        conditions: String?,
        summary: String
    ) {
        dao.insert(
            WeatherRecordEntity(
                location = location,
                timestampEpochMs = timestampEpochMs,
                temperatureC = temperatureC,
                conditions = conditions,
                summary = summary
            )
        )
    }

    suspend fun getAll(): List<WeatherRecord> =
        dao.getAllDesc().map { it.toModel() }

    suspend fun getLatest(limit: Int): List<WeatherRecord> =
        dao.getLatest(limit.coerceIn(1, 200)).map { it.toModel() }

    suspend fun clearAll() {
        dao.clearAll()
    }
}

private fun WeatherRecordEntity.toModel() = WeatherRecord(
    id = id,
    location = location,
    timestampEpochMs = timestampEpochMs,
    temperatureC = temperatureC,
    conditions = conditions,
    summary = summary
)
