package dev.arbjerg.ukulele.audio.local

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor
import com.sedmelluq.discord.lavaplayer.container.MediaContainerProbe
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.ukulele.config.BotProps
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

class LocalFileMemoryCacheTest {
    private var cache: LocalFileMemoryCache? = null

    @AfterEach
    fun tearDown() {
        cache?.destroy()
    }

    private fun dummyLocalTrack(
        file: File,
        sourceManager: InMemoryLocalAudioSourceManager,
        c: LocalFileMemoryCache,
    ): InMemoryLocalAudioTrack {
        val info = AudioTrackInfo(file.nameWithoutExtension, "Test", 1000L, file.path, false, file.path)
        val descriptor = MediaContainerDescriptor(mock(MediaContainerProbe::class.java), null)
        return InMemoryLocalAudioTrack(info, descriptor, sourceManager, c)
    }

    private fun awaitCondition(
        timeoutMs: Long = 5000L,
        predicate: () -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (predicate()) return
            Thread.sleep(25L)
        }
        assertTrue(predicate(), "Timed out waiting for cache condition")
    }

    @Test
    fun `prefetches active queue into pinned tier, demotes to LRU on stop, and evicts oldest LRU when budget exceeded`(
        @TempDir tempDir: Path,
    ) {
        val props =
            BotProps().apply {
                cacheLocalFilesInMemory = true
                maxCachedFileMb = 5
                maxTotalCacheMb = 2 // 2 MB total budget
                prefetchConcurrency = 1
            }
        val c = LocalFileMemoryCache(props)
        cache = c
        val sm = InMemoryLocalAudioSourceManager(c, props)

        val f1 = tempDir.resolve("track1.bin").toFile().apply { writeBytes(ByteArray(900 * 1024) { 1 }) }
        val f2 = tempDir.resolve("track2.bin").toFile().apply { writeBytes(ByteArray(900 * 1024) { 2 }) }
        val f3 = tempDir.resolve("track3.bin").toFile().apply { writeBytes(ByteArray(900 * 1024) { 3 }) }

        val activeQueue = AtomicReference<List<AudioTrack>>(emptyList())
        val notifiedGuilds = CopyOnWriteArrayList<Long>()
        c.bindPlayerCallbacks(
            snapshotProvider = { mapOf(100L to activeQueue.get()) },
            stateNotifier = { notifiedGuilds.addAll(it) },
        )

        val t1 = dummyLocalTrack(f1, sm, c)
        val t2 = dummyLocalTrack(f2, sm, c)
        activeQueue.set(listOf(t1, t2))
        c.reconcileAndPrefetch()

        awaitCondition {
            val s = c.getStats(activeQueue.get())
            s.pinnedCount == 2 && s.queuedInMemoryCount == 2
        }

        // Touch f1 via openPlaybackStream so f1 becomes NEWER than f2, or vice versa:
        Thread.sleep(10L)
        c.openPlaybackStream(f1).first.use { stream ->
            assertEquals(1, stream.read())
        }

        // Clear queue -> both demote to unpinned LRU; refresh f1 access time after demotion so f2 is oldest LRU
        activeQueue.set(emptyList())
        c.reconcileAndPrefetch()
        val afterStopStats = c.getStats(emptyList())
        assertEquals(0, afterStopStats.pinnedCount)
        assertEquals(2, afterStopStats.lruCount)

        Thread.sleep(10L)
        assertNotNull(c.getValidCachedBytesForProbe(f1)) // Refreshes f1 lastAccessedNanos; f2 is now oldest LRU

        // Queue f3 (900 KB): 900 + 900 + 900 = 2.7 MB > 2.0 MB -> must evict oldest LRU (f2) BEFORE allocating f3!
        val t3 = dummyLocalTrack(f3, sm, c)
        activeQueue.set(listOf(t3))
        c.reconcileAndPrefetch()

        awaitCondition { c.trackCacheState(t3) == "MEMORY" }

        assertNotNull(c.getValidCachedBytesForProbe(f1), "Newer LRU entry f1 should remain cached")
        assertNull(c.getValidCachedBytesForProbe(f2), "Oldest LRU entry f2 should have been evicted")
        assertTrue(c.evictions.get() >= 1L)
        assertTrue(notifiedGuilds.contains(100L))

        // clearLru() evicts remaining unpinned f1 while keeping pinned f3 intact
        val afterClear = c.clearLru()
        assertEquals(1, afterClear.pinnedCount)
        assertEquals(0, afterClear.lruCount)
        assertNull(c.getValidCachedBytesForProbe(f1))
        assertNotNull(c.getValidCachedBytesForProbe(f3))
        sm.shutdown()
    }

    @Test
    fun `sliding window promotes tail tracks once earlier pinned tracks finish, and invalidates modified files`(
        @TempDir tempDir: Path,
    ) {
        val props =
            BotProps().apply {
                cacheLocalFilesInMemory = true
                maxCachedFileMb = 2
                maxTotalCacheMb = 1 // 1 MB budget
            }
        val c = LocalFileMemoryCache(props)
        cache = c
        val sm = InMemoryLocalAudioSourceManager(c, props)

        val f1 = tempDir.resolve("a.bin").toFile().apply { writeBytes(ByteArray(700 * 1024) { 0x11 }) }
        val f2 = tempDir.resolve("b.bin").toFile().apply { writeBytes(ByteArray(700 * 1024) { 0x22 }) }
        val fOversized = tempDir.resolve("huge.bin").toFile().apply { writeBytes(ByteArray(2500 * 1024) { 0x33 }) }

        val t1 = dummyLocalTrack(f1, sm, c)
        val t2 = dummyLocalTrack(f2, sm, c)
        val tHuge = dummyLocalTrack(fOversized, sm, c)

        val activeQueue = AtomicReference<List<AudioTrack>>(listOf(t1, t2, tHuge))
        c.bindPlayerCallbacks(snapshotProvider = { mapOf(1L to activeQueue.get()) }, stateNotifier = {})
        c.reconcileAndPrefetch()

        // Only t1 (700 KB) fits in 1 MB pinned budget; t2 waits; tHuge is marked DISK immediately
        awaitCondition { c.trackCacheState(t1) == "MEMORY" && c.trackCacheState(tHuge) == "DISK" }
        assertEquals("DISK", c.trackCacheState(t2))

        // Now t1 finishes playing -> active queue is [t2]; t1 demotes to LRU and is evicted so t2 promotes into RAM!
        activeQueue.set(listOf(t2))
        c.reconcileAndPrefetch()
        awaitCondition { c.trackCacheState(t2) == "MEMORY" }

        // Modify f2 on disk after unpinning -> getValidCachedBytesForProbe detects size/mtime change and invalidates
        activeQueue.set(emptyList())
        c.reconcileAndPrefetch()
        val updatedPayload = ByteArray(512 * 1024) { 0x55 }
        Files.write(f2.toPath(), updatedPayload)
        f2.setLastModified(System.currentTimeMillis() + 5000L)

        assertNull(c.getValidCachedBytesForProbe(f2), "Modified file on disk must invalidate stale LRU entry")

        // Re-queueing loads fresh bytes
        activeQueue.set(listOf(t2))
        c.reconcileAndPrefetch()
        awaitCondition { c.trackCacheState(t2) == "MEMORY" }
        assertArrayEquals(updatedPayload, c.getValidCachedBytesForProbe(f2))
        sm.shutdown()
    }
}
