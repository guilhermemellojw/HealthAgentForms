package com.antigravity.healthagent.domain.model

sealed class DomainResult<out T> {
    data class Success<out T>(val data: T) : DomainResult<T>()
    data class Error(val message: String) : DomainResult<Nothing>()

    fun getOrNull(): T? = (this as? Success)?.data
    fun exceptionOrNull(): Throwable? = (this as? Error)?.let { IllegalStateException(it.message) }
}
