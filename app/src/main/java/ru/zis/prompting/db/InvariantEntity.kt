package ru.zis.prompting.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "invariants")
data class InvariantEntity(
    @PrimaryKey val id: String,
    val rule: String,
    val createdAt: Long = System.currentTimeMillis()
)
