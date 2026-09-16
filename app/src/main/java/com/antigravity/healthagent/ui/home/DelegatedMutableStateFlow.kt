package com.antigravity.healthagent.ui.home

import kotlinx.coroutines.flow.*

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DelegatedMutableStateFlow<T, R>(
    private val source: MutableStateFlow<T>,
    private val getter: (T) -> R,
    private val setter: (T, R) -> T
) : MutableStateFlow<R> {

    override var value: R
        get() = getter(source.value)
        set(newValue) {
            source.update { setter(it, newValue) }
        }

    override val replayCache: List<R>
        get() = listOf(value)

    override val subscriptionCount: StateFlow<Int>
        get() = source.subscriptionCount

    override suspend fun emit(value: R) {
        this.value = value
    }

    override fun tryEmit(value: R): Boolean {
        this.value = value
        return true
    }

    override fun compareAndSet(expect: R, update: R): Boolean {
        while (true) {
            val current = source.value
            val currentVal = getter(current)
            if (currentVal != expect) return false
            val updated = setter(current, update)
            if (source.compareAndSet(current, updated)) return true
        }
    }

    override fun resetReplayCache() {
        // No-op
    }

    override suspend fun collect(collector: FlowCollector<R>): Nothing {
        source.map { getter(it) }.distinctUntilChanged().collect {
            collector.emit(it)
        }
        throw kotlinx.coroutines.CancellationException("StateFlow completed")
    }
}
