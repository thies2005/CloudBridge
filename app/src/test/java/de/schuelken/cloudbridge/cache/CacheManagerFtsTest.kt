package de.schuelken.cloudbridge.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheManagerFtsTest {

    @Test
    fun returnsEmptyStringForPunctuationOnlyFtsQueries() {
        assertEquals("", CacheManager.buildFtsMatchQuery("*\"!@#%^&()"))
        assertEquals("", CacheManager.buildFtsMatchQuery("   ---   "))
        assertEquals("report* 2026*", CacheManager.buildFtsMatchQuery("report \"2026!\""))
        assertEquals("report* pdf*", CacheManager.buildFtsMatchQuery("report.pdf"))
        assertEquals("2026* 11* photo* jpg*", CacheManager.buildFtsMatchQuery("2026-11/photo.jpg"))
    }
}
