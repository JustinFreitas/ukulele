package dev.arbjerg.ukulele.api

import dev.arbjerg.ukulele.audio.local.LocalFileMemoryCache
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/cache")
class CacheController(
    private val localFileCache: LocalFileMemoryCache,
) {
    @GetMapping
    fun getCacheStats(): CacheStatsDto = localFileCache.getStats(null)

    @PostMapping("/clear")
    fun clearLruCache(): CacheStatsDto = localFileCache.clearLru()
}
