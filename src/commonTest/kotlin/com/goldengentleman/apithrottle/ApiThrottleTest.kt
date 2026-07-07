package com.goldengentleman.apithrottle

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiThrottleTest {

    @Test
    fun executeReturnsBlockResult() = runTest {
        val throttle = ApiThrottle("Test", requestsPerSecond = 100, scope = backgroundScope)
        val result = throttle.execute { 21 * 2 }
        assertEquals(42, result)
    }

    @Test
    fun executePropagatesExceptions() = runTest {
        val throttle = ApiThrottle("Test", requestsPerSecond = 100, scope = backgroundScope)
        var caught: String? = null
        try {
            throttle.execute<Unit> { throw IllegalStateException("boom") }
        } catch (e: IllegalStateException) {
            caught = e.message
        }
        assertEquals("boom", caught)
    }

    @Test
    fun allQueuedRequestsComplete() = runTest {
        val throttle = ApiThrottle("Test", requestsPerSecond = 50, scope = backgroundScope)
        val results = (1..20).map { n ->
            async { throttle.execute { n } }
        }.awaitAll()
        assertEquals((1..20).toList(), results.sorted())
    }
}
