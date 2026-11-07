package com.yadown.core.transfer

import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeReadySignalTest {

    @Test
    fun signalWakesWaiterBeforeTimeout() {
        val waiting = CountDownLatch(1)
        var releasedAt = 0L

        val waiter = thread(start = true) {
            waiting.countDown()
            RangeReadySignal.await(2_000)
            releasedAt = System.currentTimeMillis()
        }
        waiting.await()
        Thread.sleep(100)

        val signalledAt = System.currentTimeMillis()
        RangeReadySignal.signal()
        waiter.join(1_000)

        assertTrue("信号应提前唤醒等待者", releasedAt - signalledAt < 1_000)
    }

    @Test
    fun awaitReturnsAfterTimeoutWithoutSignal() {
        val startedAt = System.currentTimeMillis()
        RangeReadySignal.await(100)
        val elapsed = System.currentTimeMillis() - startedAt

        assertTrue("超时后应返回而不是永久阻塞", elapsed >= 80)
    }
}
