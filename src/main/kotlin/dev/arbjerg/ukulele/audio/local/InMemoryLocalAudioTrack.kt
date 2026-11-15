package dev.arbjerg.ukulele.audio.local

import com.sedmelluq.discord.lavaplayer.container.MediaContainerDescriptor
import com.sedmelluq.discord.lavaplayer.source.local.LocalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor
import java.io.File

/**
 * [LocalAudioTrack] subclass that plays from [LocalFileMemoryCache] when cached in RAM (or within the
 * startup prefetch grace window), falling back transparently to disk streaming when oversized/uncached.
 *
 * Subclassing [LocalAudioTrack] preserves `is LocalAudioTrack` compatibility in `NowPlayingCommand`
 * and Lavaplayer track serialization.
 */
class InMemoryLocalAudioTrack(
    trackInfo: AudioTrackInfo,
    containerTrackFactory: MediaContainerDescriptor,
    private val inMemorySourceManager: InMemoryLocalAudioSourceManager,
    private val cache: LocalFileMemoryCache,
) : LocalAudioTrack(trackInfo, containerTrackFactory, inMemorySourceManager) {
    /**
     * Records whether the current/latest playback pass of this track instance is reading from RAM or disk.
     * Reset to null on [makeShallowClone] so repeated/looped clones resolve fresh per pass.
     */
    @Volatile
    var activePlaybackSource: PlaybackSource? = null
        internal set

    override fun process(localExecutor: LocalAudioTrackExecutor) {
        val file = File(info.identifier)
        val (stream, resolvedSource) = cache.openPlaybackStream(file)
        activePlaybackSource = resolvedSource
        cache.notifyPlaybackSourceResolved()
        stream.use { inputStream ->
            val delegate = containerTrackFactory.createTrack(info, inputStream) as InternalAudioTrack
            processDelegate(delegate, localExecutor)
        }
    }

    override fun makeShallowClone(): AudioTrack =
        InMemoryLocalAudioTrack(
            trackInfo = info,
            containerTrackFactory = containerTrackFactory,
            inMemorySourceManager = inMemorySourceManager,
            cache = cache,
        )
}
