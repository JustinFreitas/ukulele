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
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.Buffer
import java.nio.ByteBuffer
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
    private val player =
        beans.apm.createPlayer().apply {
            addListener(this@Player)
            volume = scaleVolume(guildProperties.volume)
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

    private val playLock = Any()

    // Virtual volume (0-1000); scaled into the real min..max range by scaleVolume
    @Volatile
    var volume: Int = guildProperties.volume
        get() = trackVolumeOverride ?: field
        set(value) {
            field = value
            trackVolumeOverride = null
            player.volume = scaleVolume(value)
            beans.guildProperties
                .transform(guildId) {
                    it.volume = value
                }.subscribe()
            publishState()
        }

    private fun publishState() {
        val dto =
            PlayerStatusDto(
                guildId = guildId.toString(),
                isPaused = player.isPaused,
                volume = volume,
                repeatTrack = repeatTrack,
                queueLooping = queueLooping,
                currentTrack = currentTrack?.toDto(),
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
        player.playingTrack?.let { tracks.add(0, it) }
        return tracks
    }

    val remainingDuration: Long get() {
        var duration = 0L
        if (player.playingTrack != null && !player.playingTrack.info.isStream) {
            player.playingTrack?.let { duration = it.info.length - it.position }
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
    var showQueueOnSkip: Boolean = beans.botProps.showQueueOnSkip

    @Volatile
    var lastChannel: TextChannel? = null

    @Volatile
    var isFadeInArmed: Boolean = false

    // Known limitations, kept as-is because the current titles do not trip them: the leading .* is greedy, so the
    // LAST "v:" in the title wins, and the closing "]" is optional, so the match is not actually required to sit
    // inside the label brackets. A title like "[Label] Live v:12 Remix" therefore matches.
    private var queueLabelVolume: Pattern = Pattern.compile("^\\s*\\[.*[vV]:(\\d{1,3}).*]?.*$")

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
        if (skipped.isNotEmpty() && skipped.first() == player.playingTrack) {
            // Stopping the currently playing track will handle its removal from the queue.
            player.stopTrack()
        }
        if (queueLooping) {
            // With looping enabled, add a clone of each skipped AudioTrack to the end of the queue.
            skipped.forEach {
                queue.add(it.makeClone())
            }
        }

        return skipped
    }

    fun pause() {
        player.isPaused = true
        publishState()
    }

    fun resume() {
        player.isPaused = false
        publishState()
    }

    fun shuffle() {
        queue.shuffle()
    }

    fun stop() {
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
        player.playingTrack?.position = position
    }

    override fun onTrackStart(
        player: AudioPlayer,
        track: AudioTrack,
    ) {
        if (beans.botProps.announceTracks) {
            lastChannel?.sendMessageEmbeds(beans.nowPlayingCommand.buildEmbed(track))?.queue()
        }

        trackVolumeOverride = null

        // Reset the volume to the current guild volume config
        player.volume = scaleVolume(this.volume)

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
     * @return the label volume as a percentage (1-150), or null if the title carries none.
     */
    private fun parseQueueLabelVolume(track: AudioTrack): Int? {
        val matcher: Matcher = queueLabelVolume.matcher(track.info.title)
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
        if (endReason.mayStartNext) {
            if (repeatTrack) {
                val clone = track.makeClone()
                // trackVolumeOverride is set when the label volume is applied and cleared by the volume setter, so a
                // null here means the user changed the volume by hand during this pass. Keep their value on the next
                // loop instead of snapping back to the label.
                if (trackVolumeOverride == null) {
                    suppressLabelVolumeFor = clone
                }
                queue.addFirst(clone)
            } else if (queueLooping) {
                queue.add(track.makeClone())
            }
        }

        synchronized(playLock) {
            val new =
                queue.take() ?: run {
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
