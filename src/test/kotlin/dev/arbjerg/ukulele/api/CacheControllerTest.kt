package dev.arbjerg.ukulele.api

import dev.arbjerg.ukulele.audio.local.LocalFileMemoryCache
import dev.arbjerg.ukulele.config.BotProps
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CacheControllerTest {
    private var cache: LocalFileMemoryCache? = null

    @AfterEach
    fun tearDown() {
        cache?.destroy()
    }

    @Test
    fun `getCacheStats and clearLruCache return expected CacheStatsDto`() {
        val props =
            BotProps().apply {
                cacheLocalFilesInMemory = true
                maxTotalCacheMb = 64
            }
        val c = LocalFileMemoryCache(props)
        cache = c
        val controller = CacheController(c)

        val stats = controller.getCacheStats()
        assertTrue(stats.enabled)
        assertEquals(0L, stats.pinnedBytes)
        assertEquals(0L, stats.lruBytes)

        val cleared = controller.clearLruCache()
        assertEquals(0L, cleared.lruBytes)
    }
}
