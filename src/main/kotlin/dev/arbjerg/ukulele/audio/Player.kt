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
        val shardManager: net.dv8tion.jda.api.sharding.ShardManager,
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

    // Virtual volume (0-100)
    var volume: Int = guildProperties.volume
        set(value) {
            field = value
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
                queueSize = queue.tracks.size,
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
        val result = if (v == 0) 0 else min + (v / 1000.0 * (max - min)).roundToInt()
        log.info("DEBUG VIRTUAL VOLUME: Input=$v, Min=$min, Max=$max -> Real=$result")
        return result
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

    var repeatTrack: Boolean = beans.botProps.repeatTrack
    var queueLooping: Boolean = beans.botProps.queueLooping
    var showQueueOnSkip: Boolean = beans.botProps.showQueueOnSkip

    var lastChannel: TextChannel? = null
    var isFadeInArmed: Boolean = false

    private var queueLabelVolume: Pattern = Pattern.compile("^\\s*\\[.*[vV]:(\\d{1,3}).*]?.*$")

    /**
     * @return true if playing started, false if not.
     */
    fun add(vararg tracks: AudioTrack): Boolean {
        queue.add(*tracks)
        if (player.playingTrack == null) {
            player.playTrack(queue.take()!!)
            publishState()
            return true
        }
        publishState()
        return false
    }

    fun skip(range: IntRange): List<AudioTrack> {
        val rangeFirst = range.first.coerceAtMost(queue.tracks.size)
        val rangeLast = range.last.coerceAtMost(queue.tracks.size)
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

    fun seek(position: Long) {
        player.playingTrack.position = position
    }

    override fun onTrackStart(
        player: AudioPlayer,
        track: AudioTrack,
    ) {
        if (beans.botProps.announceTracks) {
            lastChannel?.sendMessageEmbeds(beans.nowPlayingCommand.buildEmbed(track))?.queue()
        }

        // Reset the volume to the current guild volume config
        player.volume = scaleVolume(this.volume)

        // Only apply queue label volume if a fade-in is NOT armed.
        // This allows the client to perform a seamless fade-in by arming it beforehand.
        if (!isFadeInArmed && (!beans.botProps.normalization || !track.isReplayGainApplied())) {
            adjustVolumeFromQueueLabelVolumeMatcher(player, track)
        }

        // Workaround for ReplayGain not being applied correctly at the start of a track
        if (beans.botProps.normalization) {
            java.util.Timer().schedule(
                object : java.util.TimerTask() {
                    override fun run() {
                        player.volume = scaleVolume(volume)
                    }
                },
                500,
            )
        }

        isFadeInArmed = false
        publishState()
    }

    /**
     * With the option to specify a queue label on the track added, there is also the ability to specify a volume for
     * the track.  It will be in the form "[Some Queue Label, v:42] TrackIdentifierUrlOrPath".  If found, the volume
     * will be set to that when the track starts.
     */
    private fun adjustVolumeFromQueueLabelVolumeMatcher(
        player: AudioPlayer,
        track: AudioTrack,
    ) {
        val matcher: Matcher = queueLabelVolume.matcher(track.info.title)
        if (matcher.find() && matcher.group(1) != null) {
            val volume =
                matcher
                    .group(1)
                    .toInt()
                    .coerceAtLeast(1)
                    .coerceAtMost(150)
            player.volume = volume
        }
    }

    /**
     * The queue label volume feature was changing the volume when the track was repeated.
     * This is a way to set the label volume to the current volume, if present, to
     * keep the volume the same in the repeat track scenario.
     */
    private fun adjustQueueLabelVolumeToCurrentPlayerVolume(
        player: AudioPlayer,
        track: AudioTrack,
    ) {
        val matcher: Matcher = queueLabelVolume.matcher(track.info.title)
        if (matcher.find() && matcher.group(1) != null) {
            track.info.title = matcher.replaceAll { player.volume.toString() }
        }
    }

    override fun onTrackEnd(
        player: AudioPlayer,
        track: AudioTrack,
        endReason: AudioTrackEndReason,
    ) {
        if (endReason.mayStartNext) {
            if (repeatTrack) {
                val clonedTrack: AudioTrack = track.makeClone()
                adjustQueueLabelVolumeToCurrentPlayerVolume(player, clonedTrack)
                queue.addFirst(clonedTrack)
            } else if (queueLooping) {
                queue.add(track.makeClone())
            }
        }

        val new =
            queue.take() ?: run {
                publishState()
                return
            }
        player.playTrack(new)
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

    override fun canProvide(): Boolean = player.provide(frame)

    override fun provide20MsAudio(): ByteBuffer {
        // flip to make it a read buffer
        (buffer as Buffer).flip()
        return buffer
    }

    override fun isOpus(): Boolean = frame.format is OpusAudioDataFormat
}
