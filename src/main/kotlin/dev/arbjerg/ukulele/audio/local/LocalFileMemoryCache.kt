package dev.arbjerg.ukulele.audio.local

import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioTrack
import com.sedmelluq.discord.lavaplayer.source.local.LocalSeekableInputStream
import com.sedmelluq.discord.lavaplayer.tools.io.SeekableInputStream
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.api.CacheStatsDto
import dev.arbjerg.ukulele.config.BotProps
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

enum class PlaybackSource {
    MEMORY,
    DISK,
}

/**
 * Two-tier in-memory cache for local audio files:
 * - **Pinned tier:** Active playing + upcoming queued tracks across all guilds (protected from eviction).
 * - **Unpinned LRU tier:** Recently played/removed tracks retained for instant zero-disk re-queueing and
 *   evicted oldest-first before new allocations when approaching [effectiveMaxTotalBytes].
 */
@Component
class LocalFileMemoryCache(
    private val botProps: BotProps,
    meterRegistry: MeterRegistry?,
    maxJvmMemoryOverrideBytes: Long?,
) : DisposableBean {
    @Autowired
    constructor(
        botProps: BotProps,
        @Autowired(required = false) meterRegistry: MeterRegistry?,
    ) : this(botProps, meterRegistry, null)

    constructor(
        botProps: BotProps,
        maxJvmMemoryOverrideBytes: Long? = null,
    ) : this(botProps, null, maxJvmMemoryOverrideBytes)

    private val log = LoggerFactory.getLogger(LocalFileMemoryCache::class.java)

    val isEnabled: Boolean
        get() = botProps.cacheLocalFilesInMemory && effectiveMaxTotalBytes > 0L

    val maxCachedFileBytes: Long =
        minOf(
            botProps.maxCachedFileMb.coerceAtLeast(0).toLong() * BYTES_PER_MB,
            MAX_ARRAY_BYTES,
        )

    val effectiveMaxTotalBytes: Long = computeEffectiveMaxTotalBytes(maxJvmMemoryOverrideBytes)

    private class CacheEntry(
        val key: String,
        val rawPath: String,
        val bytes: ByteArray,
        val lastModifiedMillis: Long,
        val length: Long,
        var pinned: Boolean,
        var lastAccessedNanos: Long,
    )

    private data class PrefetchTarget(
        val key: String,
        val rawPath: String,
    )

    private data class UncacheableRecord(
        val lastModifiedMillis: Long,
        val length: Long,
    )

    private val cacheLock = Any()
    private val entries = HashMap<String, CacheEntry>()
    private val pinnedKeys = HashSet<String>()
    private val inFlight = HashMap<String, CompletableFuture<ByteArray?>>()
    private val pendingPrefetchQueue = ArrayDeque<PrefetchTarget>()
    private val pendingPrefetchKeySet = HashSet<String>()
    private val oversizedFiles = HashMap<String, UncacheableRecord>()

    private var pinnedBytes: Long = 0L
    private var lruBytes: Long = 0L
    private var reservedInFlightBytes: Long = 0L

    val hits = AtomicLong(0L)
    val misses = AtomicLong(0L)
    val evictions = AtomicLong(0L)

    private val shuttingDown = AtomicBoolean(false)

    @Volatile
    private var queueSnapshotProvider: (() -> Map<Long, List<AudioTrack>>)? = null

    @Volatile
    private var guildStateNotifier: ((Set<Long>) -> Unit)? = null

    private val prefetchSignal = Channel<Unit>(Channel.CONFLATED)

    private val workerThreadCounter = AtomicInteger(0)
    private val concurrency = botProps.prefetchConcurrency.coerceIn(1, 8)
    private val prefetchExecutor =
        Executors.newFixedThreadPool(concurrency) { runnable ->
            Thread(runnable, "ukulele-file-prefetch-${workerThreadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }
    private val prefetchDispatcher = prefetchExecutor.asCoroutineDispatcher()
    private val prefetchScope = CoroutineScope(prefetchDispatcher + SupervisorJob())

    private val notifyScheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ukulele-cache-notify").apply { isDaemon = true }
        }
    private val notifyLock = Any()
    private var scheduledNotify: ScheduledFuture<*>? = null

    init {
        if (isEnabled) {
            repeat(concurrency) {
                prefetchScope.launch {
                    runPrefetchWorkerLoop()
                }
            }
        }
        if (meterRegistry != null) {
            registerMetrics(meterRegistry)
        }
    }

    private fun computeEffectiveMaxTotalBytes(maxJvmMemoryOverrideBytes: Long?): Long {
        if (!botProps.cacheLocalFilesInMemory) return 0L
        val configuredBytes = botProps.maxTotalCacheMb.coerceAtLeast(0).toLong() * BYTES_PER_MB
        if (configuredBytes <= 0L) return 0L

        val jvmMax = maxJvmMemoryOverrideBytes ?: Runtime.getRuntime().maxMemory()
        if (jvmMax <= 0L || jvmMax == Long.MAX_VALUE) {
            return configuredBytes
        }

        val safeCeiling =
            if (jvmMax > 3L * 1024L * BYTES_PER_MB) {
                minOf((jvmMax * 0.85).toLong(), jvmMax - 1536L * BYTES_PER_MB)
            } else {
                (jvmMax * 0.75).toLong()
            }.coerceAtLeast(0L)

        if (configuredBytes > safeCeiling) {
            log.warn(
                "Configured maxTotalCacheMb ({} MB) exceeds safe JVM heap ceiling ({} MB for maxMemory={} MB); clamping cache to {} MB",
                configuredBytes / BYTES_PER_MB,
                safeCeiling / BYTES_PER_MB,
                jvmMax / BYTES_PER_MB,
                safeCeiling / BYTES_PER_MB,
            )
            return safeCeiling
        }
        return configuredBytes
    }

    private fun registerMetrics(registry: MeterRegistry) {
        Gauge
            .builder(
                "ukulele.cache.pinned.bytes",
                this,
            ) { synchronized(it.cacheLock) { it.pinnedBytes.toDouble() } }
            .register(registry)
        Gauge.builder("ukulele.cache.lru.bytes", this) { synchronized(it.cacheLock) { it.lruBytes.toDouble() } }.register(registry)
        Gauge
            .builder("ukulele.cache.total.bytes", this) {
                synchronized(it.cacheLock) { (it.pinnedBytes + it.lruBytes).toDouble() }
            }.register(registry)
        Gauge.builder("ukulele.cache.max.bytes", this) { it.effectiveMaxTotalBytes.toDouble() }.register(registry)
        Gauge
            .builder("ukulele.cache.entries.pinned", this) {
                synchronized(it.cacheLock) {
                    it.entries.values
                        .count { e ->
                            e.pinned
                        }.toDouble()
                }
            }.register(registry)
        Gauge
            .builder("ukulele.cache.entries.lru", this) {
                synchronized(it.cacheLock) {
                    it.entries.values
                        .count { e ->
                            !e.pinned
                        }.toDouble()
                }
            }.register(registry)
        Gauge.builder("ukulele.cache.hits", this) { it.hits.get().toDouble() }.register(registry)
        Gauge.builder("ukulele.cache.misses", this) { it.misses.get().toDouble() }.register(registry)
        Gauge.builder("ukulele.cache.evictions", this) { it.evictions.get().toDouble() }.register(registry)
    }

    /**
     * Registers callbacks from [dev.arbjerg.ukulele.audio.PlayerRegistry] without creating a Spring bean cycle.
     */
    fun bindPlayerCallbacks(
        snapshotProvider: () -> Map<Long, List<AudioTrack>>,
        stateNotifier: (Set<Long>) -> Unit,
    ) {
        this.queueSnapshotProvider = snapshotProvider
        this.guildStateNotifier = stateNotifier
    }

    fun notifyPlaybackSourceResolved() {
        scheduleGuildStateNotify(immediate = false)
    }

    /**
     * Used during `loadItem()` container probing: returns cached bytes if the file is already resident
     * in RAM and its `(lastModified, size)` on disk matches, invalidating stale unpinned entries if modified.
     */
    fun getValidCachedBytesForProbe(file: File): ByteArray? {
        if (!isEnabled || shuttingDown.get()) return null
        val key = LocalPathInputUtils.normalizeCacheKey(file.path) ?: return null
        val attrs =
            try {
                Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            } catch (_: Exception) {
                return null
            }
        val lastMod = attrs.lastModifiedTime().toMillis()
        val length = attrs.size()

        synchronized(cacheLock) {
            val existing = entries[key] ?: return null
            if (existing.lastModifiedMillis == lastMod && existing.length == length) {
                existing.lastAccessedNanos = System.nanoTime()
                hits.incrementAndGet()
                return existing.bytes
            }
            // File changed on disk since being cached
            oversizedFiles.remove(key)
            if (!existing.pinned) {
                entries.remove(key)
                lruBytes = (lruBytes - existing.length).coerceAtLeast(0L)
            }
            return null
        }
    }

    /**
     * Opens a [SeekableInputStream] for `InMemoryLocalAudioTrack.process()`.
     * Checks RAM first without querying disk attributes (so cached tracks play even if disk/SMB disconnects).
     * When `preloadActiveTrackIntoMemory` is enabled (default), loads an uncached track into RAM before starting
     * playback so Track #1 also plays from memory; otherwise awaits an in-flight prefetch up to
     * `prefetchStartupWaitMs` and falls back to disk streaming on pass #1.
     */
    fun openPlaybackStream(file: File): Pair<SeekableInputStream, PlaybackSource> {
        if (!isEnabled || shuttingDown.get()) {
            misses.incrementAndGet()
            return LocalSeekableInputStream(file) to PlaybackSource.DISK
        }

        val key = LocalPathInputUtils.normalizeCacheKey(file.path)
        if (key == null) {
            misses.incrementAndGet()
            return LocalSeekableInputStream(file) to PlaybackSource.DISK
        }

        var pendingFuture: CompletableFuture<ByteArray?>? = null
        synchronized(cacheLock) {
            val existing = entries[key]
            if (existing != null) {
                existing.lastAccessedNanos = System.nanoTime()
                hits.incrementAndGet()
                return ByteArraySeekableInputStream(existing.bytes) to PlaybackSource.MEMORY
            }
            pendingFuture = inFlight[key]
            if (botProps.preloadActiveTrackIntoMemory && pendingFuture != null) {
                pinnedKeys.add(key)
            }
        }

        val future = pendingFuture
        if (botProps.preloadActiveTrackIntoMemory) {
            val timeoutMs = botProps.activeTrackPreloadTimeoutMs.coerceAtLeast(0L)
            val preloadedBytes =
                if (future != null && timeoutMs > 0L) {
                    awaitInFlightBytes(future, timeoutMs)
                } else if (timeoutMs > 0L) {
                    loadSynchronouslyForActivePlayback(key = key, file = file, timeoutMs = timeoutMs)
                } else {
                    null
                }
            if (preloadedBytes != null) {
                hits.incrementAndGet()
                return ByteArraySeekableInputStream(preloadedBytes) to PlaybackSource.MEMORY
            }
        } else {
            val waitMs = botProps.prefetchStartupWaitMs
            if (future != null && waitMs > 0L) {
                val loadedBytes = awaitInFlightBytes(future, waitMs)
                if (loadedBytes != null) {
                    hits.incrementAndGet()
                    return ByteArraySeekableInputStream(loadedBytes) to PlaybackSource.MEMORY
                }
            }
        }

        misses.incrementAndGet()
        return LocalSeekableInputStream(file) to PlaybackSource.DISK
    }

    private fun awaitInFlightBytes(
        future: CompletableFuture<ByteArray?>,
        timeoutMs: Long,
    ): ByteArray? =
        try {
            future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: ExecutionException) {
            null
        }

    private fun loadSynchronouslyForActivePlayback(
        key: String,
        file: File,
        timeoutMs: Long,
    ): ByteArray? {
        if (shuttingDown.get()) return null
        val path: Path = LocalPathInputUtils.toNormalizedPathOrNull(file.path) ?: return null
        val attrs =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (e: Exception) {
                log.debug("Skipping active preload for unreadable local file {}: {}", file.path, e.message)
                return null
            }

        if (!attrs.isRegularFile) return null
        val size = attrs.size()
        val lastMod = attrs.lastModifiedTime().toMillis()

        if (size <= 0L || size > maxCachedFileBytes) {
            synchronized(cacheLock) {
                oversizedFiles[key] = UncacheableRecord(lastModifiedMillis = lastMod, length = size)
            }
            scheduleGuildStateNotify(immediate = false)
            return null
        }

        var existingBytes: ByteArray? = null
        var joinedFuture: CompletableFuture<ByteArray?>? = null
        val ownFuture = CompletableFuture<ByteArray?>()
        val reservedOwnLoad =
            synchronized(cacheLock) {
                if (shuttingDown.get()) {
                    false
                } else {
                    val existing = entries[key]
                    val activeFuture = inFlight[key]
                    when {
                        existing != null -> {
                            existing.lastAccessedNanos = System.nanoTime()
                            existingBytes = existing.bytes
                            false
                        }

                        activeFuture != null -> {
                            pinnedKeys.add(key)
                            joinedFuture = activeFuture
                            false
                        }

                        pinnedBytes + reservedInFlightBytes + size > effectiveMaxTotalBytes -> {
                            false
                        }

                        else -> {
                            evictUnpinnedLruUntilFitsLocked(size)
                            if (pinnedBytes + lruBytes + reservedInFlightBytes + size > effectiveMaxTotalBytes) {
                                false
                            } else {
                                pinnedKeys.add(key)
                                if (pendingPrefetchKeySet.remove(key)) {
                                    pendingPrefetchQueue.removeIf { it.key == key }
                                }
                                reservedInFlightBytes += size
                                inFlight[key] = ownFuture
                                true
                            }
                        }
                    }
                }
            }

        existingBytes?.let { return it }
        joinedFuture?.let { return awaitInFlightBytes(it, timeoutMs) }
        if (!reservedOwnLoad) return null

        scheduleGuildStateNotify(immediate = false)
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        return readAndCommitReservedFile(
            key = key,
            rawPath = file.path,
            path = path,
            size = size,
            lastMod = lastMod,
            future = ownFuture,
            deadlineNanos = deadlineNanos,
            checkCancel = {
                if (Thread.currentThread().isInterrupted) {
                    throw InterruptedException("Track preload interrupted")
                }
            },
        )
    }

    /**
     * Reconciles the pinned set and re-orders the background prefetch queue in playback priority order.
     * Snapshots guild queues OUTSIDE [cacheLock] to guarantee lock-order freedom with `Player` and `TrackQueue`.
     */
    fun reconcileAndPrefetch() {
        if (!isEnabled || shuttingDown.get()) return

        val snapshotByGuild = queueSnapshotProvider?.invoke() ?: emptyMap()
        val orderedTargets = ArrayList<PrefetchTarget>()
        val newPinnedKeys = HashSet<String>()

        for ((_, tracks) in snapshotByGuild) {
            for (track in tracks) {
                if (track !is LocalAudioTrack) continue
                val rawPath = track.info.identifier
                val key = LocalPathInputUtils.normalizeCacheKey(rawPath) ?: continue
                if (newPinnedKeys.add(key)) {
                    orderedTargets.add(PrefetchTarget(key = key, rawPath = rawPath))
                }
            }
        }

        val now = System.nanoTime()
        var hasWork = false

        synchronized(cacheLock) {
            pinnedKeys.clear()
            pinnedKeys.addAll(newPinnedKeys)

            var recomputedPinned = 0L
            var recomputedLru = 0L
            for (entry in entries.values) {
                val shouldPin = entry.key in pinnedKeys
                if (entry.pinned && !shouldPin) {
                    // Demoting from Pinned -> Unpinned LRU: refresh timestamp so just-played stays warm
                    entry.lastAccessedNanos = now
                }
                entry.pinned = shouldPin
                if (shouldPin) {
                    recomputedPinned += entry.length
                } else {
                    recomputedLru += entry.length
                }
            }
            pinnedBytes = recomputedPinned
            lruBytes = recomputedLru

            pendingPrefetchQueue.clear()
            pendingPrefetchKeySet.clear()
            for (target in orderedTargets) {
                if (entries.containsKey(target.key) || inFlight.containsKey(target.key)) continue
                if (oversizedFiles.containsKey(target.key)) continue
                if (pendingPrefetchKeySet.add(target.key)) {
                    pendingPrefetchQueue.addLast(target)
                }
            }
            hasWork = pendingPrefetchQueue.isNotEmpty()
        }

        if (hasWork) {
            prefetchSignal.trySend(Unit)
        }
    }

    private suspend fun runPrefetchWorkerLoop() {
        for (signal in prefetchSignal) {
            if (shuttingDown.get()) return
            while (!shuttingDown.get()) {
                coroutineContext.ensureActive()
                var blockingLoad: CompletableFuture<ByteArray?>? = null
                val target =
                    synchronized(cacheLock) {
                        if (inFlight.size >= concurrency) {
                            blockingLoad = inFlight.values.firstOrNull()
                            null
                        } else {
                            val next = pendingPrefetchQueue.pollFirst()
                            if (next != null) {
                                pendingPrefetchKeySet.remove(next.key)
                            }
                            next
                        }
                    }

                val waitFuture = blockingLoad
                if (waitFuture != null) {
                    kotlinx.coroutines.runInterruptible {
                        try {
                            waitFuture.get(5, TimeUnit.SECONDS)
                        } catch (_: Exception) {
                        }
                    }
                    continue
                }
                if (target == null) break

                prefetchSingleTarget(target)
            }
        }
    }

    private suspend fun prefetchSingleTarget(target: PrefetchTarget) {
        if (shuttingDown.get()) return
        val path: Path = LocalPathInputUtils.toNormalizedPathOrNull(target.rawPath) ?: return
        val attrs =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java)
            } catch (e: Exception) {
                log.debug("Skipping prefetch for unreadable local file {}: {}", target.rawPath, e.message)
                return
            }

        if (!attrs.isRegularFile) return
        val size = attrs.size()
        val lastMod = attrs.lastModifiedTime().toMillis()

        if (size <= 0L || size > maxCachedFileBytes) {
            synchronized(cacheLock) {
                oversizedFiles[target.key] = UncacheableRecord(lastModifiedMillis = lastMod, length = size)
            }
            scheduleGuildStateNotify(immediate = false)
            return
        }

        val future = CompletableFuture<ByteArray?>()
        val reserved =
            synchronized(cacheLock) {
                if (shuttingDown.get() || target.key !in pinnedKeys ||
                    entries.containsKey(target.key) || inFlight.containsKey(target.key)
                ) {
                    false
                } else if (pinnedBytes + reservedInFlightBytes + size > effectiveMaxTotalBytes) {
                    // Pinned + in-flight bytes already fill the budget; wait for earlier tracks to finish & unpin
                    false
                } else {
                    evictUnpinnedLruUntilFitsLocked(size)
                    if (pinnedBytes + lruBytes + reservedInFlightBytes + size > effectiveMaxTotalBytes) {
                        false
                    } else {
                        reservedInFlightBytes += size
                        inFlight[target.key] = future
                        true
                    }
                }
            }

        if (!reserved) {
            return
        }

        val cancelCtx = coroutineContext
        readAndCommitReservedFile(
            key = target.key,
            rawPath = target.rawPath,
            path = path,
            size = size,
            lastMod = lastMod,
            future = future,
            deadlineNanos = null,
            checkCancel = { cancelCtx.ensureActive() },
        )
    }

    private fun readAndCommitReservedFile(
        key: String,
        rawPath: String,
        path: Path,
        size: Long,
        lastMod: Long,
        future: CompletableFuture<ByteArray?>,
        deadlineNanos: Long?,
        checkCancel: () -> Unit,
    ): ByteArray? {
        var loadedBytes: ByteArray? = null
        var reservationReleased = false
        try {
            val buffer = ByteArray(size.toInt())
            var offset = 0
            Files.newInputStream(path).use { input ->
                while (offset < buffer.size) {
                    checkCancel()
                    if (shuttingDown.get()) {
                        future.complete(null)
                        return null
                    }
                    if (deadlineNanos != null && System.nanoTime() >= deadlineNanos) {
                        log.warn("Timed out preloading active track {} into memory; falling back to disk", rawPath)
                        future.complete(null)
                        return null
                    }
                    val chunkLen = minOf(PREFETCH_CHUNK_BYTES, buffer.size - offset)
                    val read = input.read(buffer, offset, chunkLen)
                    if (read < 0) break
                    offset += read
                }
            }

            if (offset != buffer.size) {
                log.warn(
                    "Truncated read while caching {} (expected {} bytes, got {}); discarding",
                    rawPath,
                    buffer.size,
                    offset,
                )
                future.complete(null)
                return null
            }

            var stored = false
            var drained = false
            synchronized(cacheLock) {
                reservedInFlightBytes = (reservedInFlightBytes - size).coerceAtLeast(0L)
                reservationReleased = true
                inFlight.remove(key, future)

                if (!shuttingDown.get()) {
                    evictUnpinnedLruUntilFitsLocked(size)
                    if (pinnedBytes + lruBytes + reservedInFlightBytes + size <= effectiveMaxTotalBytes) {
                        val isStillPinned = key in pinnedKeys
                        val existing = entries.remove(key)
                        if (existing != null) {
                            if (existing.pinned) {
                                pinnedBytes = (pinnedBytes - existing.length).coerceAtLeast(0L)
                            } else {
                                lruBytes = (lruBytes - existing.length).coerceAtLeast(0L)
                            }
                        }
                        entries[key] =
                            CacheEntry(
                                key = key,
                                rawPath = rawPath,
                                bytes = buffer,
                                lastModifiedMillis = lastMod,
                                length = size,
                                pinned = isStillPinned,
                                lastAccessedNanos = System.nanoTime(),
                            )
                        if (isStillPinned) {
                            pinnedBytes += size
                        } else {
                            lruBytes += size
                        }
                        loadedBytes = buffer
                        stored = true
                    }
                }
                drained = pendingPrefetchQueue.isEmpty() && inFlight.isEmpty()
            }
            future.complete(loadedBytes)
            if (stored) {
                scheduleGuildStateNotify(immediate = drained)
            }
            return loadedBytes
        } catch (t: kotlinx.coroutines.CancellationException) {
            future.complete(null)
            throw t
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            future.complete(null)
            return null
        } catch (e: Exception) {
            log.warn("Failed to cache local file {} into memory: {}", rawPath, e.message)
            future.complete(null)
            return null
        } finally {
            var wakeWorker = false
            synchronized(cacheLock) {
                if (!reservationReleased) {
                    reservedInFlightBytes = (reservedInFlightBytes - size).coerceAtLeast(0L)
                }
                inFlight.remove(key, future)
                wakeWorker = pendingPrefetchQueue.isNotEmpty()
            }
            if (wakeWorker && !shuttingDown.get()) {
                prefetchSignal.trySend(Unit)
            }
        }
    }

    /**
     * Must be called while holding [cacheLock]. Evicts oldest unpinned LRU entries until [neededBytes] fits.
     */
    private fun evictUnpinnedLruUntilFitsLocked(neededBytes: Long) {
        while (pinnedBytes + lruBytes + reservedInFlightBytes + neededBytes > effectiveMaxTotalBytes) {
            val oldestUnpinned =
                entries.values
                    .asSequence()
                    .filter { !it.pinned }
                    .minByOrNull { it.lastAccessedNanos }
                    ?: break
            entries.remove(oldestUnpinned.key)
            lruBytes = (lruBytes - oldestUnpinned.length).coerceAtLeast(0L)
            evictions.incrementAndGet()
        }
    }

    private fun scheduleGuildStateNotify(immediate: Boolean) {
        if (shuttingDown.get()) return
        synchronized(notifyLock) {
            if (immediate) {
                scheduledNotify?.cancel(false)
                scheduledNotify = null
            } else if (scheduledNotify != null) {
                return
            } else {
                scheduledNotify =
                    try {
                        notifyScheduler.schedule(
                            {
                                synchronized(notifyLock) {
                                    scheduledNotify = null
                                }
                                dispatchGuildStateNotify()
                            },
                            NOTIFY_DEBOUNCE_MS,
                            TimeUnit.MILLISECONDS,
                        )
                    } catch (_: Exception) {
                        null
                    }
                return
            }
        }
        if (immediate) {
            try {
                notifyScheduler.execute { dispatchGuildStateNotify() }
            } catch (_: Exception) {
                // Shutting down
            }
        }
    }

    private fun dispatchGuildStateNotify() {
        if (shuttingDown.get()) return
        val snapshot = queueSnapshotProvider?.invoke() ?: return
        val affectedGuilds =
            snapshot
                .filterValues { tracks -> tracks.any { it is LocalAudioTrack } }
                .keys
        if (affectedGuilds.isNotEmpty()) {
            guildStateNotifier?.invoke(affectedGuilds)
        }
    }

    fun trackCacheState(track: AudioTrack): String? {
        if (track !is LocalAudioTrack) return null
        if (!isEnabled) return "DISK"

        if (track is InMemoryLocalAudioTrack) {
            when (track.activePlaybackSource) {
                PlaybackSource.MEMORY -> {
                    return "MEMORY"
                }

                PlaybackSource.DISK -> {
                    return "DISK"
                }

                null -> {}
            }
        }

        val key = LocalPathInputUtils.normalizeCacheKey(track.info.identifier) ?: return "DISK"
        synchronized(cacheLock) {
            if (entries.containsKey(key)) return "MEMORY"
            if (oversizedFiles.containsKey(key)) return "DISK"
            if (inFlight.containsKey(key) || pendingPrefetchKeySet.contains(key)) return "PREFETCHING"
        }
        return "DISK"
    }

    /**
     * Builds a [CacheStatsDto]. When [guildTracks] is provided, `queued*` / `prefetchingCount` reflect
     * that guild's active queue; when null (for `GET /api/cache`), they aggregate across all guilds.
     */
    fun getStats(guildTracks: List<AudioTrack>? = null): CacheStatsDto {
        val relevantTracks: List<AudioTrack> =
            guildTracks
                ?: queueSnapshotProvider
                    ?.invoke()
                    ?.values
                    ?.flatten()
                ?: emptyList()

        val localTracks = relevantTracks.filterIsInstance<LocalAudioTrack>()
        var inMemoryCount = 0
        var prefetchingCount = 0

        val pBytes: Long
        val lBytes: Long
        val pCount: Int
        val lCount: Int

        synchronized(cacheLock) {
            pBytes = pinnedBytes
            lBytes = lruBytes
            pCount = entries.values.count { it.pinned }
            lCount = entries.size - pCount

            if (isEnabled) {
                for (t in localTracks) {
                    if (t is InMemoryLocalAudioTrack && t.activePlaybackSource != null) {
                        if (t.activePlaybackSource == PlaybackSource.MEMORY) inMemoryCount++
                        continue
                    }
                    val key = LocalPathInputUtils.normalizeCacheKey(t.info.identifier) ?: continue
                    when {
                        entries.containsKey(key) -> inMemoryCount++

                        !oversizedFiles.containsKey(
                            key,
                        ) && (inFlight.containsKey(key) || pendingPrefetchKeySet.contains(key)) -> prefetchingCount++
                    }
                }
            }
        }

        return CacheStatsDto(
            enabled = isEnabled,
            pinnedBytes = pBytes,
            lruBytes = lBytes,
            totalBytes = pBytes + lBytes,
            maxBytes = effectiveMaxTotalBytes,
            pinnedCount = pCount,
            lruCount = lCount,
            queuedLocalCount = localTracks.size,
            queuedInMemoryCount = inMemoryCount,
            prefetchingCount = prefetchingCount,
        )
    }

    /**
     * Evicts all unpinned LRU entries immediately without interrupting active playing/queued tracks.
     */
    fun clearLru(): CacheStatsDto {
        var clearedAny = false
        synchronized(cacheLock) {
            val iter = entries.entries.iterator()
            while (iter.hasNext()) {
                val entry = iter.next().value
                if (!entry.pinned) {
                    iter.remove()
                    evictions.incrementAndGet()
                    clearedAny = true
                }
            }
            lruBytes = 0L
            oversizedFiles.clear()
        }

        val allGuildIds = queueSnapshotProvider?.invoke()?.keys ?: emptySet()
        if (clearedAny && allGuildIds.isNotEmpty() && !shuttingDown.get()) {
            guildStateNotifier?.invoke(allGuildIds)
        }
        return getStats(null)
    }

    override fun destroy() {
        if (!shuttingDown.compareAndSet(false, true)) return
        synchronized(notifyLock) {
            scheduledNotify?.cancel(false)
            scheduledNotify = null
        }
        notifyScheduler.shutdownNow()
        prefetchSignal.close()
        prefetchScope.cancel()
        prefetchExecutor.shutdownNow()
        synchronized(cacheLock) {
            inFlight.values.forEach { it.complete(null) }
            inFlight.clear()
            pendingPrefetchQueue.clear()
            pendingPrefetchKeySet.clear()
            entries.clear()
            pinnedKeys.clear()
            oversizedFiles.clear()
            pinnedBytes = 0L
            lruBytes = 0L
            reservedInFlightBytes = 0L
        }
    }

    companion object {
        const val BYTES_PER_MB: Long = 1024L * 1024L
        const val MAX_ARRAY_BYTES: Long = Int.MAX_VALUE - 8L
        const val PREFETCH_CHUNK_BYTES: Int = 256 * 1024
        const val NOTIFY_DEBOUNCE_MS: Long = 500L
    }
}
