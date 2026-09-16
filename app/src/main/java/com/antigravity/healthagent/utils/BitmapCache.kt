package com.antigravity.healthagent.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory

object BitmapCache {
    private val cache = mutableMapOf<Int, Bitmap>()

    fun getLogo(context: Context, resId: Int): Bitmap? {
        return cache.getOrPut(resId) {
            BitmapFactory.decodeResource(context.resources, resId) ?: return null
        }
    }

    fun clear() {
        cache.values.forEach { it.recycle() }
        cache.clear()
    }
}
