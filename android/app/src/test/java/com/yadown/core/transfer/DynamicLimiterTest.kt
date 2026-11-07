package com.yadown.core.transfer

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicLimiterTest {

    @Test
    fun globalLimitBlocksUntilRelease() = runBlocking {
        val limiter = DynamicLimiter(initialGlobalLimit = 2, defaultPerKeyLimit = 1)
        limiter.acquire("a")
        limiter.acquire("b")

        var acquired = false
        val waiter = async {
            limiter.acquire("c")
            acquired = true
        }
        delay(50)
        assertFalse("全局配额满时不应获取", acquired)

        limiter.release("a")
        waiter.await()
        assertTrue(acquired)
        assertEquals(2, limiter.snapshot().globalInUse)
    }

    @Test
    fun perKeyLimitBlocksSameKey() = runBlocking {
        val limiter = DynamicLimiter(initialGlobalLimit = 4, defaultPerKeyLimit = 1)
        limiter.acquire("a")

        var acquired = false
        val waiter = async {
            limiter.acquire("a")
            acquired = true
        }
        delay(50)
        assertFalse("同一 key 达到上限时不应获取", acquired)

        limiter.release("a")
        waiter.await()
        assertTrue(acquired)
    }

    @Test
    fun raisingGlobalLimitWakesWaiters() = runBlocking {
        val limiter = DynamicLimiter(initialGlobalLimit = 1, defaultPerKeyLimit = 2)
        limiter.acquire("a")

        var acquired = false
        val waiter = async {
            limiter.acquire("b")
            acquired = true
        }
        delay(50)
        assertFalse(acquired)

        limiter.updateGlobalLimit(2)
        waiter.await()
        assertTrue(acquired)
        assertEquals(2, limiter.snapshot().globalLimit)
    }

    @Test
    fun releaseDecrementsPerKeyCounters() = runBlocking {
        val limiter = DynamicLimiter(initialGlobalLimit = 2, defaultPerKeyLimit = 2)
        limiter.acquire("a")
        assertEquals(1, limiter.snapshot().perKeyInUse["a"])

        limiter.release("a")
        assertEquals(0, limiter.snapshot().globalInUse)
        assertTrue(limiter.snapshot().perKeyInUse.isEmpty())
    }

    @Test
    fun snapshotExposesEffectivePerKeyLimit() = runBlocking {
        val limiter = DynamicLimiter(initialGlobalLimit = 16, defaultPerKeyLimit = 16)
        limiter.acquire("a", perKeyLimit = 8)

        assertEquals(8, limiter.snapshot().perKeyLimit["a"])
    }
}
