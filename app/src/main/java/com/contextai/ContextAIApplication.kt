package com.contextai

import android.app.Application
import androidx.work.Configuration
import androidx.hilt.work.HiltWorkerFactory
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class ContextAIApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    private val logBuffer = LogBufferTree()

    override fun onCreate() {
        super.onCreate()
        instance = this
        Timber.plant(logBuffer)
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    fun getRecentLogs(): List<String> = logBuffer.getLogs()

    class LogBufferTree : Timber.Tree() {
        private val buffer = ArrayDeque<String>(BUFFER_SIZE)

        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            val entry = "${priorityChar(priority)}/$tag: $message"
            synchronized(buffer) {
                if (buffer.size >= BUFFER_SIZE) buffer.removeFirst()
                buffer.addLast(entry)
            }
        }

        fun getLogs(): List<String> = synchronized(buffer) { buffer.toList() }

        private fun priorityChar(priority: Int) = when (priority) {
            android.util.Log.VERBOSE -> "V"
            android.util.Log.DEBUG -> "D"
            android.util.Log.INFO -> "I"
            android.util.Log.WARN -> "W"
            android.util.Log.ERROR -> "E"
            else -> "?"
        }

        companion object {
            private const val BUFFER_SIZE = 50
        }
    }

    companion object {
        lateinit var instance: ContextAIApplication
            private set
    }
}
