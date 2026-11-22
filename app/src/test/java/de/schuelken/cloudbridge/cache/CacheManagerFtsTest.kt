package de.schuelken.cloudbridge.cache

import org.junit.Assert.assertEquals
import org.junit.Test

class CacheManagerFtsTest {

    @Test
    fun returnsEmptyStringForPunctuationOnlyFtsQueries() {
        assertEquals("", CacheManager.buildFtsMatchQuery("*\"!@#%^&()"))
        assertEquals("", CacheManager.buildFtsMatchQuery("   ---   "))
        assertEquals("report* 2026*", CacheManager.buildFtsMatchQuery("report \"2026!\""))
    }
}
