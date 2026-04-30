package com.contextai.di

import android.content.Context
import androidx.room.Room
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.contextai.data.local.AppDatabase
import com.contextai.data.local.AppMemoryDao
import com.contextai.data.local.ConversationDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.sqlcipher.database.SupportFactory
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        migratePlainDbIfNeeded(context)
        val passphrase = getOrCreateDbPassphrase(context)
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            AppDatabase.DATABASE_NAME
        )
            .openHelperFactory(SupportFactory(passphrase))
            .fallbackToDestructiveMigration()
            .build()
    }

    // SQLite files start with "SQLite format 3\0". If the existing DB is unencrypted,
    // delete it so SQLCipher can create a fresh encrypted one.
    private fun migratePlainDbIfNeeded(context: Context) {
        val dbFile = context.getDatabasePath(AppDatabase.DATABASE_NAME)
        if (!dbFile.exists()) return
        runCatching {
            val header = ByteArray(16)
            dbFile.inputStream().use { it.read(header) }
            val sqliteMagic = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
            if (header.contentEquals(sqliteMagic)) {
                dbFile.delete()
                context.getDatabasePath("${AppDatabase.DATABASE_NAME}-shm").delete()
                context.getDatabasePath("${AppDatabase.DATABASE_NAME}-wal").delete()
            }
        }
    }

    private fun getOrCreateDbPassphrase(context: Context): ByteArray {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context, "db_key_prefs", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        val existing = prefs.getString("db_passphrase", null)
        if (existing != null) return android.util.Base64.decode(existing, android.util.Base64.DEFAULT)
        val key = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        prefs.edit().putString("db_passphrase", android.util.Base64.encodeToString(key, android.util.Base64.DEFAULT)).apply()
        return key
    }

    @Provides
    fun provideConversationDao(database: AppDatabase): ConversationDao =
        database.conversationDao()

    @Provides
    fun provideAppMemoryDao(database: AppDatabase): AppMemoryDao =
        database.appMemoryDao()
}
