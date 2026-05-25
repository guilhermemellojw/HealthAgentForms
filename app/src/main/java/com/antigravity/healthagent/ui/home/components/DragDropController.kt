package com.antigravity.healthagent.ui.home.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

fun checkForOverScroll(
    viewportY: Float,
    listState: LazyListState,
    scope: CoroutineScope,
    currentJob: Job?,
    onJobUpdated: (Job?) -> Unit
) {
    val distFromTop = viewportY
    val viewportHeight = listState.layoutInfo.viewportSize.height
    val distFromBottom = viewportHeight - viewportY

    if (distFromTop < 200f) {
        if (currentJob?.isActive != true) {
            val job = scope.launch {
                while (true) {
                    listState.scrollBy(-30f)
                    delay(16)
                }
            }
            onJobUpdated(job)
        }
    } else if (distFromBottom < 200f && distFromBottom > 0) {
        if (currentJob?.isActive != true) {
            val job = scope.launch {
                while (true) {
                    listState.scrollBy(30f)
                    delay(16)
                }
            }
            onJobUpdated(job)
        }
    } else {
        currentJob?.cancel()
        onJobUpdated(null)
    }
}
