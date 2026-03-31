package ru.zis.prompting.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_profiles")
data class UserProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val style: String,
    val constraints: String,
    val context: String,
    val isActive: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis()
)
