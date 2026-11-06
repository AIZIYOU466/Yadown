package com.yadown.core.transfer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RangeCoverageTest {

    @Test
    fun empty_doesNotCover() {
        assertFalse(RangeCoverage().covers(0, 10))
    }

    @Test
    fun singleRange_coversInside() {
        val coverage = RangeCoverage()
        coverage.add(0L..99L)
        assertTrue(coverage.covers(10, 20))
        assertTrue(coverage.covers(0, 99))
    }

    @Test
    fun singleRange_doesNotCoverOutside() {
        val coverage = RangeCoverage()
        coverage.add(0L..99L)
        assertFalse(coverage.covers(0, 100))
        assertFalse(coverage.covers(100, 200))
    }

    @Test
    fun adjacentRanges_joinCoverage() {
        val coverage = RangeCoverage()
        coverage.add(0L..49L)
        coverage.add(50L..99L)
        assertTrue(coverage.covers(0, 99))
        assertTrue(coverage.covers(40, 60))
    }

    @Test
    fun gap_breaksCoverage() {
        val coverage = RangeCoverage()
        coverage.add(0L..49L)
        coverage.add(60L..99L)
        assertFalse(coverage.covers(0, 99))
        assertFalse(coverage.covers(45, 65))
        assertTrue(coverage.covers(60, 99))
    }

    @Test
    fun emptyInputRange_isCovered() {
        assertTrue(RangeCoverage().covers(10, 9))
    }

    @Test
    fun unsortedRanges_stillCover() {
        val coverage = RangeCoverage()
        coverage.add(50L..99L)
        coverage.add(0L..49L)
        assertTrue(coverage.covers(25, 75))
    }
}