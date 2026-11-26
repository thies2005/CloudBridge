package de.schuelken.cloudbridge.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchIndexProgressTest {

    @Test
    fun scanPercentUsesPreviousIndexAboutTopDirsAndFallbackCleanly() {
        assertEquals(0, SearchIndexWorker.estimateScanPercent(0, 0L, 100, 0L, 0, 0))

        // Previous index count: 500/1000 -> ~43% (capped inside 1..85 during scan phase)
        val fromPrev = SearchIndexWorker.estimateScanPercent(500, 0L, 1000, 0L, 0, 0)
        assertTrue("Expected ~43%, got $fromPrev", fromPrev in 40..46)

        // About bytes: 50% of used storage
        val fromAbout = SearchIndexWorker.estimateScanPercent(50, 500L, 0, 1000L, 0, 0)
        assertTrue("Expected ~43%, got $fromAbout", fromAbout in 40..46)

        // Top-level directory progress
        val fromDirsLow = SearchIndexWorker.estimateScanPercent(50, 0L, 0, 0L, 4, 1)
        val fromDirsHigh = SearchIndexWorker.estimateScanPercent(200, 0L, 0, 0L, 4, 3)
        assertTrue(fromDirsHigh > fromDirsLow)
        assertTrue(fromDirsHigh in 1..85)

        // Asymptotic fallback stays monotonic inside [1, 85]
        val low = SearchIndexWorker.estimateScanPercent(100, 0L, 0, 0L, 0, 0)
        val high = SearchIndexWorker.estimateScanPercent(2000, 0L, 0, 0L, 0, 0)
        assertTrue(low in 1..85)
        assertTrue(high in 1..85)
        assertTrue(high > low)
    }

    @Test
    fun etaCalculationRespectsElapsedAndPercent() {
        assertEquals(0, SearchIndexWorker.estimateEtaSeconds(10_000L, 100))
        assertEquals(10, SearchIndexWorker.estimateEtaSeconds(10_000L, 50))
        assertEquals(30, SearchIndexWorker.estimateEtaSeconds(10_000L, 25))
        assertTrue(SearchIndexWorker.estimateEtaSeconds(500L, 99) >= 1)
    }
}
