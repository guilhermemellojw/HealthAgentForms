package com.antigravity.healthagent.context

import android.content.Context
import java.lang.ref.WeakReference

object AppContextHolder {
    private var contextRef: WeakReference<Context>? = null

    fun setContext(context: Context) {
        contextRef = WeakReference(context.applicationContext)
    }

    fun getContext(): Context? = contextRef?.get()
}

fun getContext(): Context? = AppContextHolder.getContext()
