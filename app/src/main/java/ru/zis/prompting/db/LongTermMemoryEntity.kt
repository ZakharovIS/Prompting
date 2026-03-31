package ru.zis.prompting.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "long_term_memory")
data class LongTermMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val category: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
)
