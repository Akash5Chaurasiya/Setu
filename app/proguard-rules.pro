# ContextAI ProGuard Rules

-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Kotlin
-keep class kotlin.** { *; }
-keep class kotlinx.** { *; }
-dontwarn kotlin.**

# Coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# Hilt / Dagger
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep @dagger.hilt.android.HiltAndroidApp class * { *; }
-keep @dagger.hilt.android.AndroidEntryPoint class * { *; }
-keep @dagger.Module class * { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-dontwarn androidx.room.**

# Retrofit + OkHttp
-keepattributes Signature
-keepattributes Exceptions
-keep class retrofit2.** { *; }
-keep interface retrofit2.** { *; }
-dontwarn retrofit2.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# Gson
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ContextAI domain models (needed for Gson serialization)
-keep class com.contextai.domain.model.** { *; }

# Security Crypto
-keep class androidx.security.crypto.** { *; }

# Timber — strip debug logs in release
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
}

# Accessibility Service
-keep class com.contextai.core.accessibility.** { *; }

# Overlay Service
-keep class com.contextai.core.overlay.FloatingBubbleService { *; }

# Broadcast Receiver
-keep class com.contextai.core.BootCompletedReceiver { *; }

# WorkManager + HiltWorker
-keep class androidx.work.** { *; }
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep @androidx.hilt.work.HiltWorker class * { *; }
-keep class com.contextai.core.DigestWorker { *; }
-dontwarn androidx.work.**

# SQLCipher
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }
-dontwarn net.sqlcipher.**

# DataStore (Preferences)
-keep class androidx.datastore.** { *; }
-dontwarn androidx.datastore.**

# Navigation
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# Material components
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# Hilt ViewModel injection
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclasseswithmembers class * {
    @javax.inject.Inject <init>(...);
}

# Consent + Security classes
-keep class com.contextai.core.consent.** { *; }
-keep class com.contextai.core.security.** { *; }
-keep class com.contextai.presentation.consent.** { *; }
-keep class com.contextai.presentation.privacy.** { *; }

# ConversationEntity / AppMemoryEntity kept by Room rule above,
# but explicit keep for TypeConverters
-keep class com.contextai.domain.model.ContextTypeConverter { *; }

# Gson — keep all serialized fields
-keepclassmembers class com.contextai.domain.model.** {
    @com.google.gson.annotations.SerializedName <fields>;
    <fields>;
}

# Remove all debug/verbose Timber calls in release
-assumenosideeffects class timber.log.Timber {
    public static *** v(...);
    public static *** d(...);
}
