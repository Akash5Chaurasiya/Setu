package com.contextai.data.local

import androidx.room.*
import com.contextai.domain.model.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY timestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE appName LIKE '%' || :query || '%' OR userQuery LIKE '%' || :query || '%' OR aiResponse LIKE '%' || :query || '%' ORDER BY timestamp DESC")
    fun searchConversations(query: String): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getConversationById(id: Long): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity): Long

    @Delete
    suspend fun delete(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM conversations")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int

    @Query("SELECT * FROM conversations WHERE timestamp >= :since ORDER BY timestamp DESC")
    suspend fun getConversationsSince(since: Long): List<ConversationEntity>
}
