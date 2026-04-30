package com.contextai.data.local

import androidx.room.*

@Dao
interface AppMemoryDao {

    @Query("SELECT * FROM app_memory WHERE packageName = :pkg ORDER BY timestamp DESC LIMIT 3")
    suspend fun getMemoriesForApp(pkg: String): List<AppMemoryEntity>

    @Query("SELECT * FROM app_memory ORDER BY timestamp DESC LIMIT 5")
    suspend fun getRecentMemories(): List<AppMemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMemory(memory: AppMemoryEntity)

    @Query("DELETE FROM app_memory WHERE timestamp < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long)
}
