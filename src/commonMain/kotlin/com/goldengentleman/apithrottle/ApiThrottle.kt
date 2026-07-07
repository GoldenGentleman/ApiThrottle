package com.goldengentleman.apithrottle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Rate-limited, priority-aware dispatcher for API calls.
 *
 * Create one instance per API (or per rate-limited endpoint group) and route all
 * calls to that API through [execute]. Callers simply suspend and receive their
 * result — the throttle is invisible except for the optional [Priority] argument.
 *
 * ### How it works
 * - A **token bucket** limits how many requests can START per second. The bucket
 *   starts full, so the first burst is immediate.
 * - A **semaphore** caps the number of requests actually in-flight at the same time.
 * - A **dispatch loop** runs in the background, draining the HIGH queue before the LOW
 *   queue. At worst a HIGH call waits one token interval (1000 / requestsPerSecond ms)
 *   before being dispatched — it does NOT have to wait for the LOW queue to drain.
 *
 * ### Usage
 * ```kotlin
 * val myApiThrottle = ApiThrottle("MyApi", requestsPerSecond = 5, maxConcurrent = 10, scope)
 *
 * // UI call — mark HIGH so it jumps ahead of background work
 * val result = myApiThrottle.execute(ApiThrottle.Priority.HIGH) { myApi.fetchSomething() }
 *
 * // Background batch — LOW is the default
 * val result = myApiThrottle.execute { myApi.fetchSomething() }
 * ```
 *
 * @param name         Human-readable label used in log output (e.g. "IGDB").
 * @param requestsPerSecond  Maximum new requests started per second.
 * @param maxConcurrent      Maximum simultaneous in-flight requests. Defaults to 2× the
 *                           rate limit.
 * @param scope        Coroutine scope that owns the dispatch loop. Typically an
 *                     application-wide scope.
 * @param verbose      When true, logs queue depth, token waits, and priority events.
 *                     Flip to true when debugging rate limit issues.
 */
@OptIn(ExperimentalTime::class)
class ApiThrottle(
    private val name: String,
    val requestsPerSecond: Int,
    val maxConcurrent: Int = requestsPerSecond * 2,
    scope: CoroutineScope,
    private val verbose: Boolean = false
) {
    enum class Priority { HIGH, LOW }

    /**
     * A queued work item. By capturing the caller's [CompletableDeferred] inside the
     * lambda we avoid any generic type issues when storing heterogeneous requests in a
     * plain (non-generic) queue.
     */
    private class PendingRequest(val run: suspend () -> Unit)

    private val highQueue = ArrayDeque<PendingRequest>()
    private val lowQueue = ArrayDeque<PendingRequest>()
    private val queueMutex = Mutex()

    // Limits how many HTTP calls can be in-flight at the same time.
    private val semaphore = Semaphore(maxConcurrent)

    // Token bucket state — only touched by the dispatch loop, so no locking needed.
    private var tokens = requestsPerSecond.toDouble() // start full for immediate responsiveness
    private var lastRefillMs = nowMs()
    private val msPerToken = 1000.0 / requestsPerSecond

    // CONFLATED: we only need to know "work exists", not a count.
    private val signal = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                val next = queueMutex.withLock {
                    // HIGH always wins when both queues have work.
                    highQueue.removeFirstOrNull() ?: lowQueue.removeFirstOrNull()
                }

                if (next == null) {
                    signal.receive() // sleep until a new request is queued
                    continue
                }

                waitForToken()

                // Launch as a sibling coroutine so the dispatch loop immediately
                // moves on to the next queued item rather than waiting for this
                // HTTP call to finish.
                launch {
                    semaphore.withPermit { next.run() }
                }
            }
        }
    }

    /**
     * Submits [block] to the throttle queue and suspends until the call completes.
     *
     * Requests are executed in priority order (HIGH before LOW) at the configured
     * rate. If this coroutine is cancelled while waiting, the request is removed
     * from the queue so it doesn't consume a slot.
     */
    suspend fun <T> execute(priority: Priority = Priority.LOW, block: suspend () -> T): T {
        val deferred = CompletableDeferred<T>()
        val request = PendingRequest {
            try {
                deferred.complete(block())
            } catch (e: Exception) {
                deferred.completeExceptionally(e)
            }
        }

        queueMutex.withLock {
            if (priority == Priority.HIGH) {
                highQueue.addLast(request)
                if (verbose) println("[$name throttle] +HIGH  (high=${highQueue.size} low=${lowQueue.size})")
            } else {
                lowQueue.addLast(request)
                if (verbose) println("[$name throttle] +LOW   (high=${highQueue.size} low=${lowQueue.size})")
            }
        }
        signal.trySend(Unit)

        return try {
            deferred.await()
        } catch (e: CancellationException) {
            // Best-effort removal — the request may already be executing.
            queueMutex.withLock {
                highQueue.remove(request)
                lowQueue.remove(request)
            }
            throw e
        }
    }

    /**
     * Suspends the dispatch loop until a rate-limit token is available.
     *
     * Only ever called from the single dispatch coroutine, so [tokens] and
     * [lastRefillMs] need no synchronisation.
     */
    private suspend fun waitForToken() {
        while (true) {
            val now = nowMs()
            tokens = minOf(
                requestsPerSecond.toDouble(),
                tokens + (now - lastRefillMs) / msPerToken
            )
            lastRefillMs = now

            if (tokens >= 1.0) {
                tokens -= 1.0
                return
            }

            val waitMs = ((1.0 - tokens) * msPerToken).toLong().coerceAtLeast(1L)
            if (verbose) println("[$name throttle] waiting ${waitMs}ms for next token")
            delay(waitMs)
        }
    }

    // Multiplatform wall-clock in epoch millis — replaces the JVM-only
    // System.currentTimeMillis() so this class works on Android, JVM, and iOS.
    private fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()
}
