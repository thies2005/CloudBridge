package de.schuelken.cloudbridge.search

import ca.pkay.rcloneexplorer.Items.FileItem
import ca.pkay.rcloneexplorer.Items.RemoteItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchEngineTest {

    private val remote = RemoteItem("drive1", "drive")

    private fun file(name: String, size: Long = 1024L, modTime: Long = System.currentTimeMillis(), mime: String = "application/octet-stream", isDir: Boolean = false): FileItem {
        return FileItem(remote, name, name, size, modTime, mime, isDir, false)
    }

    @Test
    fun normalizesDiacriticsAndCase() {
        val terms = SearchEngine.parseTerms("cafe")
        assertTrue(SearchEngine.matchesQuery("Café Menu.pdf", terms))
    }

    @Test
    fun supportsMultiTermAndQuotedPhrases() {
        val terms = SearchEngine.parseTerms("\"annual report\" 2025")
        assertEquals(listOf("annual report", "2025"), terms)
        assertTrue(SearchEngine.matchesQuery("2025 Annual Report Final.pdf", terms))
        assertFalse(SearchEngine.matchesQuery("Report Annual 2025.pdf", terms))
    }

    @Test
    fun supportsGlobPatterns() {
        val terms = SearchEngine.parseTerms("*.apk")
        assertTrue(SearchEngine.matchesQuery("CloudBridge-release.apk", terms))
        assertFalse(SearchEngine.matchesQuery("CloudBridge-release.apks", terms))
    }

    @Test
    fun filterItemsAppliesTypeSizeAndDateFilters() {
        val now = System.currentTimeMillis()
        val day = 24L * 60L * 60L * 1000L
        val items = listOf(
            file("app.apk", size = 5_000_000L, modTime = now - day),
            file("photo.jpg", size = 2_000_000L, modTime = now - day, mime = "image/jpeg"),
            file("old.apk", size = 5_000_000L, modTime = now - 60 * day),
            file("tiny.apk", size = 100L, modTime = now - day)
        )
        val filter = SearchFilter(
            type = SearchFilter.TypeFilter.APK,
            minSizeBytes = 1024L * 1024L,
            maxAgeMs = 7 * day
        )
        val result = SearchEngine.filterItems(items, "", filter)
        assertEquals(listOf("app.apk"), result.map { it.name })
    }

    @Test
    fun handlesPathologicalGlobsInLinearTime() {
        val evilPattern = "*a".repeat(30) + "*b"
        val candidate = "a".repeat(200) + "c"
        val startNs = System.nanoTime()
        assertFalse(SearchEngine.matchesQuery(candidate, listOf(evilPattern)))
        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000L
        assertTrue("Glob matcher took too long: ${elapsedMs}ms", elapsedMs < 500L)
        assertTrue(SearchEngine.matchesQuery("a".repeat(200) + "b", listOf(evilPattern)))
        assertTrue(SearchEngine.matchesQuery("abc", listOf("a?c")))
        assertFalse(SearchEngine.matchesQuery("ac", listOf("a?c")))
    }
}
