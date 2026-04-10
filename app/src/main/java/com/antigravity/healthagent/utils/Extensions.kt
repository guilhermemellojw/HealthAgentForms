package com.antigravity.healthagent.utils

import android.content.Context
import android.widget.Toast

fun Context.toast(message: String, duration: Int = Toast.LENGTH_SHORT) {
    Toast.makeText(this, message, duration).show()
}

fun String.toDashDate(): String = this.replace("/", "-")
fun String.toSlashDate(): String = this.replace("-", "/")
