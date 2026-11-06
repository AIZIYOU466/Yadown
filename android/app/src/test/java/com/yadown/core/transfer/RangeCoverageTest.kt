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
        coverage.add(0..99)
        assertTrue(coverage.covers(10, 20))
        assertTrue(coverage.covers(0, 99))
    }

    @Test
    fun singleRange_doesNotCoverOutside() {
        val coverage = RangeCoverage()
        coverage.add(0..99)
        assertFalse(coverage.covers(0, 100))
        assertFalse(coverage.covers(100, 200))
    }

    @Test
    fun adjacentRanges_joinCoverage() {
        val coverage = RangeCoverage()
        coverage.add(0..49)
        coverage.add(50..99)
        assertTrue(coverage.covers(0, 99))
        assertTrue(coverage.covers(40, 60))
    }

    @Test
    fun gap_breaksCoverage() {
        val coverage = RangeCoverage()
        coverage.add(0..49)
        coverage.add(60..99)
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
        coverage.add(50..99)
        coverage.add(0..49)
        assertTrue(coverage.covers(25, 75))
    }
}