package dev.arbjerg.ukulele.api

import dev.arbjerg.ukulele.audio.Player
import dev.arbjerg.ukulele.audio.PlayerRegistry
import dev.arbjerg.ukulele.audio.local.LocalFileMemoryCache
import dev.arbjerg.ukulele.command.NowPlayingCommand
import dev.arbjerg.ukulele.command.QueueCommand
import dev.arbjerg.ukulele.config.BotProps
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.AutowiredAnnotationBeanPostProcessor
import org.springframework.beans.factory.support.DefaultListableBeanFactory

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

    @Test
    fun `Spring AutowiredAnnotationBeanPostProcessor resolves unambiguous single constructor for all cache-aware beans`() {
        val processor =
            AutowiredAnnotationBeanPostProcessor().apply {
                setBeanFactory(DefaultListableBeanFactory())
            }
        val beanClasses =
            listOf(
                LocalFileMemoryCache::class.java,
                NowPlayingCommand::class.java,
                QueueCommand::class.java,
                PlayerRegistry::class.java,
                Player.Beans::class.java,
                PlayerController::class.java,
                CacheController::class.java,
            )
        for (clazz in beanClasses) {
            val candidates = processor.determineCandidateConstructors(clazz, clazz.simpleName)
            if (clazz != CacheController::class.java) {
                assertNotNull(candidates, "Expected explicit @Autowired candidate constructor for ${clazz.name}")
                assertEquals(1, candidates!!.size, "Expected exactly 1 Spring autowired constructor for ${clazz.name}")
            }
        }
    }
}
