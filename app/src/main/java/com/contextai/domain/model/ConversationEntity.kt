package com.contextai.domain.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters

@Entity(tableName = "conversations")
@TypeConverters(ContextTypeConverter::class)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val appName: String,
    val appPackage: String,
    val contextType: ContextType,
    val userQuery: String,
    val aiResponse: String,
    val timestamp: Long = System.currentTimeMillis()
)

class ContextTypeConverter {
    @TypeConverter
    fun fromContextType(value: ContextType): String = value.name

    @TypeConverter
    fun toContextType(value: String): ContextType = runCatching {
        ContextType.valueOf(value)
    }.getOrDefault(ContextType.GENERIC)
}
