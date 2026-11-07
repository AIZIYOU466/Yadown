package com.yadown.core.net

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionBudgetTest {

    @Test
    fun clientRaisesPerHostDispatcherLimit() {
        val client = OkHttpRangeSource.createClient()

        assertEquals(64, client.dispatcher.maxRequests)
        assertEquals(32, client.dispatcher.maxRequestsPerHost)
    }
}
