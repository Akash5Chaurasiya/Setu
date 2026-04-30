package com.contextai.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.contextai.domain.model.ConversationEntity
import com.contextai.domain.model.ContextTypeConverter

@Database(
    entities = [ConversationEntity::class, AppMemoryEntity::class],
    version = 2,
    exportSchema = false
)
@TypeConverters(ContextTypeConverter::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun appMemoryDao(): AppMemoryDao

    companion object {
        const val DATABASE_NAME = "contextai_db"
    }
}
