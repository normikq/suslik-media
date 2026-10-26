package ru.suslikovanora.media.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class PaginationTest {

    @Test
    fun testSmallPageRange() {
        // Less than or equal to 7 pages: all pages listed directly
        assertEquals(listOf(1), PaginationHelper.computePageRange(1, 1))
        assertEquals(listOf(1, 2, 3), PaginationHelper.computePageRange(2, 3))
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), PaginationHelper.computePageRange(4, 7))
    }

    @Test
    fun testLargePageRangeNearStart() {
        // total = 10, current = 1
        assertEquals(listOf(1, 2, 3, 4, 5, -1, 10), PaginationHelper.computePageRange(1, 10))
        // total = 10, current = 4
        assertEquals(listOf(1, 2, 3, 4, 5, -1, 10), PaginationHelper.computePageRange(4, 10))
    }

    @Test
    fun testLargePageRangeNearMiddle() {
        // total = 10, current = 5
        assertEquals(listOf(1, -1, 4, 5, 6, -1, 10), PaginationHelper.computePageRange(5, 10))
        // total = 10, current = 6
        assertEquals(listOf(1, -1, 5, 6, 7, -1, 10), PaginationHelper.computePageRange(6, 10))
    }

    @Test
    fun testLargePageRangeNearEnd() {
        // total = 10, current = 7 (10 - 3 = 7)
        assertEquals(listOf(1, -1, 6, 7, 8, 9, 10), PaginationHelper.computePageRange(7, 10))
        // total = 10, current = 10
        assertEquals(listOf(1, -1, 6, 7, 8, 9, 10), PaginationHelper.computePageRange(10, 10))
    }
}
