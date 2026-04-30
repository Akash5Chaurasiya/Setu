package com.contextai.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "app_memory")
data class AppMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val packageName: String,
    val appName: String,
    val lastAction: String,
    val lastContextSummary: String,
    val timestamp: Long,
    val useCount: Int = 1
)
