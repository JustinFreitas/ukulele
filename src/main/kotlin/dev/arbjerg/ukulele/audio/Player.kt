package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.format.OpusAudioDataFormat
import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
import dev.arbjerg.ukulele.api.PlayerEventPublisher
import dev.arbjerg.ukulele.api.PlayerStatusDto
import dev.arbjerg.ukulele.api.toDto
import dev.arbjerg.ukulele.command.NowPlayingCommand
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.data.GuildProperties
import dev.arbjerg.ukulele.data.GuildPropertiesService
import net.dv8tion.jda.api.audio.AudioSendHandler
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.Buffer
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Matcher
import java.util.regex.Pattern
import kotlin.math.roundToInt

class Player(
    private val beans: Beans,
    guildProperties: GuildProperties,
) : AudioEventAdapter(),
    AudioSendHandler {
    @Component
    class Beans(
        val apm: AudioPlayerManager,
        val guildProperties: GuildPropertiesService,
        val nowPlayingCommand: NowPlayingCommand,
        val botProps: BotProps,
        val publisher: PlayerEventPublisher,
        @org.springframework.context.annotation.Lazy val shardManager: net.dv8tion.jda.api.sharding.ShardManager,
    )

    private val log: Logger = LoggerFactory.getLogger(Player::class.java)

    private val guildId = guildProperties.guildId
    private val queue = TrackQueue()

    @Volatile
    private var activeFadeConfig: TrackFadeConfig? = null

    @Volatile
    internal var activeFadeFilter: TrackFadeFilter? = null

    @Volatile
    private var naturalRepeatTrack: AudioTrack? = null

    private val skipFadeScheduler =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "ukulele-fade-$guildId").apply { isDaemon = true }
        }

    private data class PendingSkip(
        val track: AudioTrack,
        val future: ScheduledFuture<*>,
    )

    private val pendingSkip = AtomicReference<PendingSkip?>(null)

    private data class PendingSeek(
        val track: AudioTrack,
        val position: Long,
        val future: ScheduledFuture<*>,
    )

    private val pendingSeek = AtomicReference<PendingSeek?>(null)

    internal val fadeFilterFactory =
        TrackFadeFilterFactory(
            configProvider = { activeFadeConfig },
            shouldAutoFadeOutAtEof = { trackFade && !repeatTrack },
            onFilterCreated = { filter ->
                activeFadeFilter = filter
                if (isFadedOut) {
                    filter.targetGain = 0.0f
                }
            },
        )

    private val player =
        beans.apm.createPlayer().apply {
            addListener(this@Player)
            volume = scaleVolume(guildProperties.volume)
            setFrameBufferDuration(beans.botProps.frameBufferDurationMs.takeIf { it > 0 } ?: 300)
            setFilterFactory(fadeFilterFactory)
        }
    private val buffer = ByteBuffer.allocate(4096)
    private val frame: MutableAudioFrame = MutableAudioFrame().apply { setBuffer(buffer) }

    @Volatile
    private var trackVolumeOverride: Int? = null

    /**
     * Queue-label volume parsed at track start, held until the track's ReplayGain state is known. Null once applied
     * or if the track carries no label volume.
     */
    @Volatile
    private var pendingLabelVolume: Int? = null

    /**
     * The clone re-queued by [repeatTrack] after the user changed the volume by hand, so the label volume does not
     * overwrite their choice on the next loop. Matched by identity in [onTrackStart] rather than being a bare flag:
     * a concurrent [stop] can drain the queue before the clone ever starts, and a flag would then leak onto whatever
     * the user played next.
     */
    @Volatile
    private var suppressLabelVolumeFor: AudioTrack? = null

    @Volatile
    private var activeTrackStartOffsetMs: Long = 0L

    @Volatile
    private var userSeekedBeforeStartOffset: Boolean = false

    @Volatile
    private var suppressStartOffsetFor: AudioTrack? = null

    private val playLock = Any()

    // Virtual volume (0-1000); scaled into the real min..max range by scaleVolume
    @Volatile
    var volume: Int = guildProperties.volume
        get() = trackVolumeOverride ?: field
        set(value) {
            field = value
            trackVolumeOverride = null
            if (value > 0 && isFadedOut) {
                isFadedOut = false
                activeFadeFilter?.targetGain = 1.0f
            }
            player.volume = scaleVolume(value)
            beans.guildProperties
                .transform(guildId) {
                    it.volume = value
                }.subscribe(null) { ex ->
                    log.error("Failed to persist volume for guild $guildId", ex)
                }
            publishState()
        }

    fun effectivePosition(track: AudioTrack): Long = pendingSeek.get()?.takeIf { it.track === track }?.position ?: track.position

    private fun publishState() {
        val current = currentTrack
        val dto =
            PlayerStatusDto(
                guildId = guildId.toString(),
                isPaused = player.isPaused,
                volume = volume,
                repeatTrack = repeatTrack,
                queueLooping = queueLooping,
                currentTrack = current?.toDto(effectivePosition(current)),
                remainingDuration = remainingDuration,
                minVolume = beans.botProps.minVolume,
                maxVolume = beans.botProps.maxVolume,
                isReplayGainEnabled = beans.botProps.normalization,
                queueSize = queue.size,
                channelId =
                    beans.shardManager
                        .getGuildById(guildId)
                        ?.audioManager
                        ?.connectedChannel
                        ?.id,
                trackFade = trackFade,
                isFadedOut = isFadedOut,
            )
        beans.publisher.publishUpdate(guildId.toString(), dto)
    }

    private fun scaleVolume(v: Int): Int {
        val min = beans.botProps.minVolume
        val max = beans.botProps.maxVolume
        return if (v == 0) 0 else min + (v / 1000.0 * (max - min)).roundToInt()
    }

    fun getOptimalVolumeStep(): Int {
        val min = beans.botProps.minVolume
        val max = beans.botProps.maxVolume
        val range = max - min
        if (range <= 0) return 10
        return kotlin.math.ceil(1000.0 / range).toInt()
    }

    val currentTrack: AudioTrack? get() = player.playingTrack
    val upcomingTracks: List<AudioTrack> get() = queue.tracks

    val tracks: List<AudioTrack> get() {
        val tracks = queue.tracks.toMutableList()
        val playing = player.playingTrack
        if (playing != null && pendingSkip.get()?.track !== playing) {
            tracks.add(0, playing)
        }
        return tracks
    }

    val remainingDuration: Long get() {
        val playing = player.playingTrack
        val duration =
            if (playing != null && pendingSkip.get()?.track !== playing && !playing.info.isStream) {
                (playing.info.length - effectivePosition(playing)).coerceAtLeast(0L)
            } else {
                0L
            }
        return duration + queue.duration
    }

    val isPaused: Boolean
        get() = player.isPaused

    @Volatile
    var repeatTrack: Boolean = beans.botProps.repeatTrack
        set(value) {
            field = value
            publishState()
        }

    @Volatile
    var queueLooping: Boolean = beans.botProps.queueLooping
        set(value) {
            field = value
            publishState()
        }

    @Volatile
    var trackFade: Boolean = beans.botProps.trackFade
        set(value) {
            field = value
            if (!value) {
                val pendingSkipEntry = cancelPendingSkip()
                if (pendingSkipEntry != null && player.playingTrack === pendingSkipEntry.track) {
                    player.stopTrack()
                }
                val pending = cancelPendingSeek()
                if (pending != null && player.playingTrack === pending.track) {
                    pending.track.position = pending.position
                    activeFadeFilter?.targetGain = if (isFadedOut) 0.0f else 1.0f
                }
            }
            publishState()
        }

    @Volatile
    var isFadedOut: Boolean = false
        private set

    @Volatile
    var showQueueOnSkip: Boolean = beans.botProps.showQueueOnSkip

    @Volatile
    var lastChannel: GuildMessageChannel? = null

    @Volatile
    var isFadeInArmed: Boolean = false

    private var queueLabelVolume: Pattern = Pattern.compile("^\\s*\\[[^\\]]*[vV]:(\\d{1,3})[^\\]]*\\]")

    private val fadeDurationMs: Long
        get() = beans.botProps.trackFadeDurationMs.takeIf { it > 0L } ?: 1500L

    private fun cancelPendingSkip(): PendingSkip? {
        val existing = pendingSkip.getAndSet(null) ?: return null
        existing.future.cancel(false)
        return existing
    }

    private fun cancelPendingSeek(): PendingSeek? {
        val existing = pendingSeek.getAndSet(null) ?: return null
        existing.future.cancel(false)
        return existing
    }

    private fun AudioTrack.cloneWithUserData(): AudioTrack =
        makeClone().also { clone ->
            val data = this.userData ?: this.getUserData(TrackStartOffset::class.java)
            if (data != null) {
                clone.userData = data
            }
        }

    fun fadeOut() {
        cancelPendingSkip()
        val pending = cancelPendingSeek()
        if (pending != null && player.playingTrack === pending.track) {
            pending.track.position = pending.position
        }
        isFadedOut = true
        activeFadeFilter?.targetGain = 0.0f
        publishState()
    }

    fun fadeIn() {
        cancelPendingSkip()
        val pending = cancelPendingSeek()
        if (pending != null && player.playingTrack === pending.track) {
            activeFadeFilter?.prepareFadeInOnSeek()
            pending.track.position = pending.position
        }
        isFadedOut = false
        activeFadeFilter?.targetGain = 1.0f
        publishState()
    }

    /**
     * @return true if playing started, false if not.
     */
    fun add(vararg tracks: AudioTrack): Boolean {
        queue.add(*tracks)
        synchronized(playLock) {
            if (player.playingTrack == null) {
                // take() can return null if a concurrent stop()/skip() drained the queue between the
                // playingTrack check and here, so guard rather than force-unwrap.
                val next = queue.take()
                if (next != null) {
                    player.isPaused = false
                    player.playTrack(next)
                    publishState()
                    return true
                }
            }
        }
        publishState()
        return false
    }

    fun skip(range: IntRange): List<AudioTrack> {
        val rangeFirst = range.first.coerceAtMost(queue.size)
        val rangeLast = range.last.coerceAtMost(queue.size)
        val skipped = mutableListOf<AudioTrack>()
        var newRange = rangeFirst..rangeLast
        // Skip the first track if it is stored here
        newRange =
            if (newRange.contains(0) && player.playingTrack != null) {
                skipped.add(player.playingTrack)
                // Reduce range if found
                0 until rangeLast
            } else {
                newRange.first - 1 until newRange.last
            }
        if (newRange.last >= 0) skipped.addAll(queue.removeRange(newRange))
        val cancelledSkip =
            if (skipped.isNotEmpty() && skipped.first() == player.playingTrack) {
                cancelPendingSkip()
            } else {
                null
            }
        if (queueLooping) {
            // With looping enabled, add a clone of each skipped AudioTrack to the end of the queue.
            skipped.forEach {
                if (it !== cancelledSkip?.track) {
                    queue.add(it.cloneWithUserData())
                }
            }
        }
        if (skipped.isNotEmpty() && skipped.first() == player.playingTrack) {
            val wasWaitingForSkipFade = cancelledSkip != null
            val wasWaitingForSeekFade = cancelPendingSeek() != null
            val filter = activeFadeFilter
            if (!wasWaitingForSkipFade && !wasWaitingForSeekFade && trackFade && !isFadedOut && !player.isPaused && filter != null) {
                val expectedTrack = player.playingTrack
                filter.targetGain = 0.0f
                val pendingRef = AtomicReference<PendingSkip?>(null)
                val scheduled =
                    skipFadeScheduler.schedule(
                        {
                            val currentPending = pendingRef.get()
                            if (currentPending != null) {
                                pendingSkip.compareAndSet(currentPending, null)
                            }
                            if (player.playingTrack === expectedTrack) {
                                isFadedOut = true
                                player.stopTrack()
                            }
                        },
                        fadeDurationMs,
                        TimeUnit.MILLISECONDS,
                    )
                val entry = PendingSkip(expectedTrack, scheduled)
                pendingRef.set(entry)
                pendingSkip.set(entry)
                publishState()
            } else {
                // Stopping the currently playing track will handle its removal from the queue.
                player.stopTrack()
            }
        }

        return skipped
    }

    fun pause() {
        cancelPendingSkip()
        val pending = cancelPendingSeek()
        if (pending != null && player.playingTrack === pending.track) {
            pending.track.position = pending.position
            activeFadeFilter?.targetGain = if (isFadedOut) 0.0f else 1.0f
        }
        player.isPaused = true
        publishState()
    }

    fun resume() {
        player.isPaused = false
        publishState()
    }

    fun shuffle() {
        queue.shuffle()
        publishState()
    }

    fun stop() {
        cancelPendingSkip()
        cancelPendingSeek()
        pendingLabelVolume = null
        isFadedOut = false
        activeFadeFilter = null
        queue.clear()
        player.stopTrack()
        publishState()
    }

    fun toggleRepeatTrack() {
        repeatTrack = !repeatTrack
        publishState()
    }

    fun removeTrackAt(index: Int): AudioTrack? {
        val removed = queue.removeAt(index)
        if (removed != null) {
            publishState()
        }
        return removed
    }

    fun reorderQueue(
        fromIndex: Int,
        toIndex: Int,
    ): Boolean {
        val success = queue.reorder(fromIndex, toIndex)
        if (success) {
            publishState()
        }
        return success
    }

    fun triggerStateUpdate() {
        publishState()
    }

    fun seek(position: Long) {
        val playing = player.playingTrack ?: return
        if (pendingSkip.get() != null) return

        val clampedPos = position.coerceAtLeast(0L)
        if (activeTrackStartOffsetMs > 0L) {
            userSeekedBeforeStartOffset = clampedPos < activeTrackStartOffsetMs
        }

        val wasWaitingForSeekFade = cancelPendingSeek() != null
        val filter = activeFadeFilter
        val remainingMs =
            if (!playing.info.isStream && playing.duration > 0L) {
                playing.duration - playing.position
            } else {
                Long.MAX_VALUE
            }

        if (!wasWaitingForSeekFade && trackFade && !isFadedOut && !player.isPaused && filter != null &&
            remainingMs > fadeDurationMs + 500L
        ) {
            filter.targetGain = 0.0f
            val pendingRef = AtomicReference<PendingSeek?>(null)
            val scheduled =
                skipFadeScheduler.schedule(
                    {
                        val currentPending = pendingRef.get()
                        if (currentPending != null) {
                            pendingSeek.compareAndSet(currentPending, null)
                        }
                        if (player.playingTrack === playing) {
                            activeFadeFilter?.prepareFadeInOnSeek()
                            if (trackFade && !beans.botProps.trackFadeSticky) {
                                trackFade = false
                            }
                            playing.position = clampedPos
                            publishState()
                        }
                    },
                    fadeDurationMs,
                    TimeUnit.MILLISECONDS,
                )
            val entry = PendingSeek(playing, clampedPos, scheduled)
            pendingRef.set(entry)
            pendingSeek.set(entry)
            publishState()
        } else {
            if (trackFade && !isFadedOut) {
                filter?.prepareFadeInOnSeek()
                if (!beans.botProps.trackFadeSticky) {
                    trackFade = false
                }
            }
            playing.position = clampedPos
            publishState()
        }
    }

    override fun onTrackStart(
        player: AudioPlayer,
        track: AudioTrack,
    ) {
        cancelPendingSkip()
        cancelPendingSeek()
        if (beans.botProps.announceTracks) {
            lastChannel?.sendMessageEmbeds(beans.nowPlayingCommand.buildEmbed(track))?.queue()
        }

        trackVolumeOverride = null

        // Reset the volume to the current guild volume config
        player.volume = scaleVolume(this.volume)

        val isNaturalRepeat = naturalRepeatTrack === track
        naturalRepeatTrack = null

        val suppressStartOffset = suppressStartOffsetFor === track
        suppressStartOffsetFor = null
        userSeekedBeforeStartOffset = suppressStartOffset

        val existingMeta = track.getUserData(TrackStartOffset::class.java)
        val resolvedStartOffsetMs =
            existingMeta?.offsetMs?.takeIf { it > 0L }
                ?: existingMeta?.queueLabel?.let { TrackTimeParser.parseLabelOffsetMs(it) }
                ?: TrackTimeParser.parseLabelOffsetMs(track.info.title)
                ?: 0L
        activeTrackStartOffsetMs = resolvedStartOffsetMs
        if (resolvedStartOffsetMs > 0L || existingMeta != null) {
            track.userData = TrackStartOffset(offsetMs = resolvedStartOffsetMs, queueLabel = existingMeta?.queueLabel)
        }
        if (resolvedStartOffsetMs > 0L && !suppressStartOffset && track.isSeekable &&
            (track.duration <= 0L || resolvedStartOffsetMs < track.duration)
        ) {
            track.position = resolvedStartOffsetMs
        }

        val stayFadedOutOnRepeat = isNaturalRepeat && isFadedOut
        val wasFadedOut = isFadedOut && !isNaturalRepeat
        if (!stayFadedOutOnRepeat) {
            isFadedOut = false
        }

        val shouldStreamFadeIn = (trackFade || isFadeInArmed || wasFadedOut) && !isNaturalRepeat
        activeFadeFilter = null
        activeFadeConfig =
            TrackFadeConfig(
                track = track,
                initialGain = if (shouldStreamFadeIn || stayFadedOutOnRepeat) 0.0f else 1.0f,
                initialTargetGain = if (stayFadedOutOnRepeat) 0.0f else 1.0f,
                fadeDurationMs = fadeDurationMs,
            )

        if (trackFade && !isNaturalRepeat && !beans.botProps.trackFadeSticky) {
            trackFade = false
        }

        // Whether the label volume applies depends on the track's ReplayGain, which is not known yet: lavaplayer
        // dispatches the start event before handing the track to its executor, so nothing has decoded. Park the
        // parsed value and let onTrackReplayGainResolved decide, which still runs before the first frame.
        //
        // A fade-in being armed means the client is driving the volume itself, and the suppress flag means the user
        // set the volume by hand during the previous pass of a repeating track; either way, leave the volume alone.
        val suppressed = suppressLabelVolumeFor === track
        suppressLabelVolumeFor = null

        pendingLabelVolume =
            if (isFadeInArmed || suppressed) {
                null
            } else {
                parseQueueLabelVolume(track)
            }

        isFadeInArmed = false
        publishState()
    }

    override fun onTrackReplayGainResolved(
        player: AudioPlayer,
        track: AudioTrack,
        gainDb: Float?,
    ) {
        if (player.playingTrack !== track) return
        val labelVolume = pendingLabelVolume ?: return
        pendingLabelVolume = null

        // ReplayGain already levels the track against everything else, so a hand-tuned label volume would be
        // fighting it. The label exists to do the same job for tracks that carry no ReplayGain data.
        if (beans.botProps.normalization && gainDb != null) {
            log.debug("Skipping queue label volume {} for {}: ReplayGain of {} dB applies", labelVolume, track.info.title, gainDb)
            return
        }

        applyLabelVolume(player, labelVolume)
        publishState()
    }

    /**
     * With the option to specify a queue label on the track added, there is also the ability to specify a volume for
     * the track.  It will be in the form "[Some Queue Label, v:42] TrackIdentifierUrlOrPath".  If found, the volume
     * will be set to that when the track starts.
     *
     * @return the label volume as a percentage (1-150), or null if neither stored metadata nor the title carries one.
     */
    private fun parseQueueLabelVolume(track: AudioTrack): Int? {
        val labelFromMetadata = track.getUserData(TrackStartOffset::class.java)?.queueLabel
        if (labelFromMetadata != null) {
            parseQueueLabelVolumeFromText(labelFromMetadata)?.let { return it }
        }
        return parseQueueLabelVolumeFromText(track.info.title)
    }

    private fun parseQueueLabelVolumeFromText(text: String): Int? {
        val matcher: Matcher = queueLabelVolume.matcher(text)
        if (!matcher.find() || matcher.group(1) == null) return null

        return matcher
            .group(1)
            .toInt()
            .coerceAtLeast(1)
            .coerceAtMost(150)
    }

    /**
     * The label volume is a percentage on the same scale as the volume command, so it goes through [scaleVolume] like
     * every other volume change. Writing it to the player raw would ignore the configured min/max range entirely.
     */
    private fun applyLabelVolume(
        player: AudioPlayer,
        labelVolume: Int,
    ) {
        val virtualVolume = labelVolume * 10
        player.volume = scaleVolume(virtualVolume)
        trackVolumeOverride = virtualVolume
    }

    override fun onTrackEnd(
        player: AudioPlayer,
        track: AudioTrack,
        endReason: AudioTrackEndReason,
    ) {
        cancelPendingSkip()
        cancelPendingSeek()
        if (endReason.mayStartNext) {
            if (repeatTrack) {
                val clone = track.cloneWithUserData()
                naturalRepeatTrack = clone
                // trackVolumeOverride is set when the label volume is applied and cleared by the volume setter, so a
                // null here means the user changed the volume by hand during this pass. Keep their value on the next
                // loop instead of snapping back to the label.
                if (trackVolumeOverride == null) {
                    suppressLabelVolumeFor = clone
                }
                if (userSeekedBeforeStartOffset) {
                    suppressStartOffsetFor = clone
                }
                queue.addFirst(clone)
            } else if (queueLooping) {
                queue.add(track.cloneWithUserData())
            }
        }

        if (!endReason.mayStartNext && endReason != AudioTrackEndReason.STOPPED) {
            return
        }

        synchronized(playLock) {
            val new =
                queue.take() ?: run {
                    isFadedOut = false
                    activeFadeFilter = null
                    publishState()
                    return
                }
            player.playTrack(new)
        }
    }

    override fun onTrackException(
        player: AudioPlayer,
        track: AudioTrack,
        exception: FriendlyException,
    ) {
        log.error("Track exception", exception)
    }

    override fun onTrackStuck(
        player: AudioPlayer,
        track: AudioTrack,
        thresholdMs: Long,
    ) {
        log.error("Track $track got stuck!")
    }

    fun destroy() {
        cancelPendingSkip()
        cancelPendingSeek()
        skipFadeScheduler.shutdownNow()
        isFadedOut = false
        activeFadeFilter = null
        queue.clear()
        player.destroy()
        beans.shardManager
            .getGuildById(guildId)
            ?.audioManager
            ?.closeAudioConnection()
    }

    override fun canProvide(): Boolean = player.provide(frame)

    override fun provide20MsAudio(): ByteBuffer {
        // flip to make it a read buffer
        (buffer as Buffer).flip()
        return buffer
    }

    override fun isOpus(): Boolean = frame.format is OpusAudioDataFormat
}
