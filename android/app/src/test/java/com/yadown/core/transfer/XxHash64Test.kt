package com.yadown.core.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class XxHash64Test {

    private fun hex(value: Long): String = java.lang.Long.toHexString(value)

    @Test
    fun matchesKnownVectors() {
        assertEquals("ef46db3751d8e999", hex(XxHash64.hash(ByteArray(0))))
        assertEquals("d24ec4f1a98c6e5b", hex(XxHash64.hash("a".toByteArray())))
        assertEquals("44bc2cf5ad770999", hex(XxHash64.hash("abc".toByteArray())))
    }

    @Test
    fun offsetAndLengthAreRespected() {
        val bytes = "xxabcxx".toByteArray()
        assertEquals(
            XxHash64.hash("abc".toByteArray()),
            XxHash64.hash(bytes, 2, 3)
        )
    }

    @Test
    fun differentContentProducesDifferentHash() {
        assertNotEquals(
            XxHash64.hash("abc".toByteArray()),
            XxHash64.hash("abd".toByteArray())
        )
    }
}
