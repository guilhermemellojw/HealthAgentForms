package com.antigravity.healthagent.context

import android.content.Context
import java.lang.ref.WeakReference

object AppContextHolder {
    private var contextRef: WeakReference<Context>? = null

    fun setContext(context: Context) {
        contextRef = WeakReference(context.applicationContext)
    }

    fun getContext(): Context {
        return contextRef?.get() ?: throw IllegalStateException("Context not initialized")
    }
}

fun getContext(): Context = AppContextHolder.getContext()
