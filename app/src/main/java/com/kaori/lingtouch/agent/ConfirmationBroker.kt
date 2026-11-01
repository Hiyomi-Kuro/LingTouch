package com.kaori.lingtouch.agent

import com.kaori.lingtouch.tools.Risk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/** Suspends the agent until the user explicitly allows or denies an action. */
class ConfirmationBroker {
    data class Request(
        val id: Long,
        val reason: String,
        val risk: Risk,
        val confirmLabel: String? = null,
        val denyLabel: String? = null
    )

    private val _pending = MutableStateFlow<Request?>(null)
    val pending: StateFlow<Request?> = _pending.asStateFlow()

    private var deferred: CompletableDeferred<Boolean>? = null
    private val counter = AtomicLong(0)

    suspend fun request(
        reason: String,
        risk: Risk,
        timeoutMs: Long = 120_000,
        confirmLabel: String? = null,
        denyLabel: String? = null
    ): Boolean {
        val request = Request(counter.incrementAndGet(), reason, risk, confirmLabel, denyLabel)
        val waiter = CompletableDeferred<Boolean>()
        deferred = waiter
        _pending.value = request
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.await() } ?: false
        } finally {
            if (deferred === waiter) {
                deferred = null
                _pending.value = null
            }
        }
    }

    fun resolve(id: Long, allowed: Boolean): Boolean {
        val current = _pending.value ?: return false
        if (current.id != id) return false
        deferred?.complete(allowed)
        return true
    }

    fun cancelPending() {
        deferred?.complete(false)
    }
}
