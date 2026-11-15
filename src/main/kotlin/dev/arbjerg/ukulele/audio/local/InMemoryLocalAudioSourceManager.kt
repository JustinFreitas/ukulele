package dev.arbjerg.ukulele.audio.local

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetection
import com.sedmelluq.discord.lavaplayer.container.MediaContainerDetectionResult
import com.sedmelluq.discord.lavaplayer.container.MediaContainerHints
import com.sedmelluq.discord.lavaplayer.container.MediaContainerRegistry
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioSourceManager
import com.sedmelluq.discord.lavaplayer.source.local.LocalSeekableInputStream
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException.Severity.SUSPICIOUS
import com.sedmelluq.discord.lavaplayer.track.AudioItem
import com.sedmelluq.discord.lavaplayer.track.AudioReference
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.BasicAudioPlaylist
import dev.arbjerg.ukulele.config.BotProps
import org.slf4j.LoggerFactory
import java.io.DataInput
import java.io.File
import java.io.IOException
import java.nio.file.FileVisitOption
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.util.EnumSet
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local audio source manager supporting:
 * 1. Zero-disk container probing on RAM cache hits via [LocalFileMemoryCache].
 * 2. Cycle-safe recursive folder scanning returning a natural-sorted [BasicAudioPlaylist].
 * 3. Path policy guardrails (blocking device namespaces, enforcing `allowUncPaths` and `allowedLocalPaths`).
 */
class InMemoryLocalAudioSourceManager(
    private val cache: LocalFileMemoryCache,
    private val botProps: BotProps,
    containerRegistry: MediaContainerRegistry = MediaContainerRegistry.DEFAULT_REGISTRY,
) : LocalAudioSourceManager(containerRegistry) {
    private val log = LoggerFactory.getLogger(InMemoryLocalAudioSourceManager::class.java)
    private val shuttingDown = AtomicBoolean(false)
    private val probeThreadCounter = AtomicInteger(0)
    private val folderProbeExecutor =
        Executors.newFixedThreadPool(FOLDER_PROBE_THREADS) { runnable ->
            Thread(runnable, "ukulele-folder-probe-${probeThreadCounter.incrementAndGet()}").apply {
                isDaemon = true
            }
        }

    override fun loadItem(
        manager: AudioPlayerManager,
        reference: AudioReference,
    ): AudioItem? {
        if (shuttingDown.get()) return null
        val rawIdentifier = reference.identifier ?: return null
        val cleaned = LocalPathInputUtils.stripWrappingQuotesAndAngles(rawIdentifier)
        if (cleaned.isEmpty() || LocalPathInputUtils.isRemoteUrlOrSearch(cleaned)) {
            return null
        }

        val denial =
            LocalPathInputUtils.checkPathAccess(
                source = cleaned,
                botProps = botProps,
                isDiscordInvoker = false,
                invokerId = null,
            )
        if (denial != null) {
            throw FriendlyException(denial, FriendlyException.Severity.COMMON, null)
        }

        val normalizedPath = LocalPathInputUtils.toNormalizedPathOrNull(cleaned) ?: return null
        val file = normalizedPath.toFile()
        if (!file.exists() || !file.canRead()) {
            return null
        }

        return when {
            file.isFile -> {
                val effectiveRef = AudioReference(file.path, reference.title)
                handleLoadResult(detectContainerForLocalFile(effectiveRef, file))
            }

            file.isDirectory -> {
                loadDirectoryPlaylist(normalizedPath, file)
            }

            else -> {
                null
            }
        }
    }

    private fun detectContainerForLocalFile(
        reference: AudioReference,
        file: File,
    ): MediaContainerDetectionResult? {
        val lastDotIndex = file.name.lastIndexOf('.')
        val fileExtension = if (lastDotIndex >= 0) file.name.substring(lastDotIndex + 1) else null
        val fallbackTitle =
            reference.title
                ?: if (lastDotIndex > 0) file.name.substring(0, lastDotIndex) else file.name
        val effectiveReference = AudioReference(file.path, fallbackTitle)
        val hints = MediaContainerHints.from(null, fileExtension)

        val cachedBytes = cache.getValidCachedBytesForProbe(file)
        if (cachedBytes != null) {
            try {
                ByteArraySeekableInputStream(cachedBytes).use { stream ->
                    return MediaContainerDetection(containerRegistry, effectiveReference, stream, hints).detectContainer()
                }
            } catch (e: IOException) {
                log.debug("In-memory container probe failed for {}, retrying from disk", file.path, e)
            }
        }

        return try {
            LocalSeekableInputStream(file).use { inputStream ->
                MediaContainerDetection(containerRegistry, effectiveReference, inputStream, hints).detectContainer()
            }
        } catch (e: IOException) {
            throw FriendlyException("Failed to open file for reading.", SUSPICIOUS, e)
        }
    }

    private fun loadDirectoryPlaylist(
        rootPath: Path,
        rootDir: File,
    ): AudioItem? {
        val maxDepth =
            if (botProps.recursiveFolderScan) {
                botProps.maxFolderDepth.coerceIn(1, 32)
            } else {
                1
            }
        val maxTracks = botProps.maxFolderTracks.coerceIn(1, 5000)

        val candidates = ArrayList<Path>()
        val visitedRealDirs = HashSet<Path>()

        Files.walkFileTree(
            rootPath,
            EnumSet.of(FileVisitOption.FOLLOW_LINKS),
            maxDepth,
            object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(
                    dir: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (shuttingDown.get()) return FileVisitResult.TERMINATE
                    if (dir != rootPath) {
                        val dirName = dir.fileName?.toString() ?: ""
                        if (dirName.startsWith(".") || dirName.lowercase(Locale.ROOT) in LocalPathInputUtils.ignoredDirectoryNames) {
                            return FileVisitResult.SKIP_SUBTREE
                        }
                    }
                    val realDir =
                        try {
                            dir.toRealPath()
                        } catch (_: Exception) {
                            dir.toAbsolutePath().normalize()
                        }
                    if (!visitedRealDirs.add(realDir)) {
                        return FileVisitResult.SKIP_SUBTREE
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(
                    file: Path,
                    attrs: BasicFileAttributes,
                ): FileVisitResult {
                    if (shuttingDown.get()) return FileVisitResult.TERMINATE
                    if (!attrs.isRegularFile || attrs.size() <= 0L) return FileVisitResult.CONTINUE
                    val name = file.fileName?.toString() ?: return FileVisitResult.CONTINUE
                    if (name.startsWith(".")) return FileVisitResult.CONTINUE
                    val dotIdx = name.lastIndexOf('.')
                    if (dotIdx <= 0 || dotIdx == name.lastIndex) return FileVisitResult.CONTINUE
                    val ext = name.substring(dotIdx + 1).lowercase(Locale.ROOT)
                    if (ext in LocalPathInputUtils.supportedAudioExtensions) {
                        candidates.add(file)
                    }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(
                    file: Path,
                    exc: IOException?,
                ): FileVisitResult {
                    log.debug("Skipping unreadable path during folder scan {}: {}", file, exc?.message)
                    return FileVisitResult.CONTINUE
                }
            },
        )

        if (candidates.isEmpty()) {
            return null
        }

        // Natural-sort ALL discovered candidate paths BEFORE applying maxFolderTracks cap
        candidates.sortWith(LocalPathInputUtils.naturalRelativePathComparator(rootPath))
        val selectedPaths = if (candidates.size > maxTracks) candidates.subList(0, maxTracks).toList() else candidates

        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(FOLDER_PROBE_DEADLINE_SECONDS)
        val tasks =
            selectedPaths.map { candidatePath ->
                Callable<AudioTrack?> {
                    if (shuttingDown.get() || System.nanoTime() > deadlineNanos) {
                        return@Callable null
                    }
                    val candidateFile = candidatePath.toFile()
                    try {
                        val ref = AudioReference(candidateFile.path, null)
                        val result = detectContainerForLocalFile(ref, candidateFile)
                        val item = handleLoadResult(result)
                        item as? AudioTrack
                    } catch (e: Exception) {
                        log.warn("Skipping unreadable or unsupported file in folder scan {}: {}", candidateFile.path, e.message)
                        null
                    }
                }
            }

        val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime()).coerceAtLeast(1000L)
        val futures = folderProbeExecutor.invokeAll(tasks, remainingMs, TimeUnit.MILLISECONDS)
        val loadedTracks = ArrayList<AudioTrack>(selectedPaths.size)
        for (future in futures) {
            if (!future.isCancelled) {
                try {
                    val track = future.get()
                    if (track != null) {
                        loadedTracks.add(track)
                    }
                } catch (_: Exception) {
                    // Already logged inside task
                }
            }
        }

        if (loadedTracks.isEmpty()) {
            return null
        }

        val playlistName = rootPath.fileName?.toString()?.takeIf { it.isNotEmpty() } ?: rootDir.name.ifEmpty { rootDir.path }
        return BasicAudioPlaylist(playlistName, loadedTracks, null, false)
    }

    override fun createTrack(
        trackInfo: AudioTrackInfo,
        containerTrackFactory: MediaContainerDescriptor,
    ): AudioTrack = InMemoryLocalAudioTrack(trackInfo, containerTrackFactory, this, cache)

    override fun decodeTrack(
        trackInfo: AudioTrackInfo,
        input: DataInput,
    ): AudioTrack? {
        val containerTrackFactory = decodeTrackFactory(input) ?: return null
        return InMemoryLocalAudioTrack(trackInfo, containerTrackFactory, this, cache)
    }

    override fun shutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            folderProbeExecutor.shutdownNow()
        }
    }

    companion object {
        private const val FOLDER_PROBE_THREADS = 4
        private const val FOLDER_PROBE_DEADLINE_SECONDS = 38L
    }
}
