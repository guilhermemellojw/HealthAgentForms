package com.antigravity.healthagent.domain.logger

interface AppLogger {
    fun d(tag: String, message: String)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String, throwable: Throwable? = null)
    fun e(tag: String, message: String, throwable: Throwable? = null)

    companion object {
        @Volatile
        private var defaultLogger: AppLogger = object : AppLogger {
            override fun d(tag: String, message: String) { android.util.Log.d(tag, message) }
            override fun i(tag: String, message: String) { android.util.Log.i(tag, message) }
            override fun w(tag: String, message: String, throwable: Throwable?) { android.util.Log.w(tag, message, throwable) }
            override fun e(tag: String, message: String, throwable: Throwable?) { android.util.Log.e(tag, message, throwable) }
        }

        fun set(logger: AppLogger) {
            defaultLogger = logger
        }

        fun d(tag: String, message: String) = defaultLogger.d(tag, message)
        fun i(tag: String, message: String) = defaultLogger.i(tag, message)
        fun w(tag: String, message: String, throwable: Throwable? = null) = defaultLogger.w(tag, message, throwable)
        fun e(tag: String, message: String, throwable: Throwable? = null) = defaultLogger.e(tag, message, throwable)
    }
}
