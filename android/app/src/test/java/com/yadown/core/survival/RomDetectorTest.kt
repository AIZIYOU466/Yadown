package com.yadown.core.survival

import org.junit.Assert.assertEquals
import org.junit.Test

class RomDetectorTest {

    @Test
    fun huawei_isEmui() {
        assertEquals(RomType.EMUI, RomDetector.detect("HUAWEI"))
    }

    @Test
    fun honor_isEmui() {
        assertEquals(RomType.EMUI, RomDetector.detect("honor"))
    }

    @Test
    fun xiaomi_isHyperos() {
        assertEquals(RomType.HYPEROS, RomDetector.detect("xiaomi"))
    }

    @Test
    fun oppoAndOneplus_areColoros() {
        assertEquals(RomType.COLOROS, RomDetector.detect("OPPO"))
        assertEquals(RomType.COLOROS, RomDetector.detect("OnePlus"))
    }

    @Test
    fun vivo_isOriginos() {
        assertEquals(RomType.ORIGINOS, RomDetector.detect("vivo"))
    }

    @Test
    fun emptyAndUnknown_areUnknown() {
        assertEquals(RomType.UNKNOWN, RomDetector.detect(""))
        assertEquals(RomType.UNKNOWN, RomDetector.detect("unknown"))
    }

    @Test
    fun otherVendors_areAosp() {
        assertEquals(RomType.AOSP, RomDetector.detect("google"))
        assertEquals(RomType.AOSP, RomDetector.detect("samsung"))
    }

    @Test
    fun trimsAndLowercases() {
        assertEquals(RomType.AOSP, RomDetector.detect("  Google  "))
    }
}