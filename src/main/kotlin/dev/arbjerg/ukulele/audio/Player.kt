package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.event.AudioEventAdapter
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.playback.MutableAudioFrame
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

class Player(private val beans: Beans, guildProperties: GuildProperties) : AudioEventAdapter(), AudioSendHandler {
    @Component
    class Beans(
            val apm: AudioPlayerManager,
            val guildProperties: GuildPropertiesService,
            val nowPlayingCommand: NowPlayingCommand,
            val botProps: BotProps
    )

    private val guildId = guildProperties.guildId
    private val queue = TrackQueue()
    private val player = beans.apm.createPlayer().apply {
        addListener(this@Player)
        volume = guildProperties.volume
    }
    private val buffer = ByteBuffer.allocate(1024)
    private val frame: MutableAudioFrame = MutableAudioFrame().apply { setBuffer(buffer) }
    private val log: Logger = LoggerFactory.getLogger(Player::class.java)
    var volume: Int
        get() = player.volume
        set(value) {
            player.volume = value
            beans.guildProperties.transform(guildId) {
                it.volume = player.volume
            }.subscribe()
        }

    val tracks: List<AudioTrack> get() {
        val tracks = queue.tracks.toMutableList()
        player.playingTrack?.let { tracks.add(0, it) }
        return tracks
    }

    val remainingDuration: Long get() {
        var duration = 0L
        if (player.playingTrack != null && !player.playingTrack.info.isStream)
            player.playingTrack?.let { duration = it.info.length - it.position }
        return duration + queue.duration
    }

    val isPaused: Boolean
        get() = player.isPaused

    var repeatTrack: Boolean = beans.botProps.repeatTrack
    var queueLooping: Boolean = beans.botProps.queueLooping
    var showQueueOnSkip: Boolean = beans.botProps.showQueueOnSkip

    var lastChannel: TextChannel? = null

    var queueLabelVolume: Pattern = Pattern.compile("^\\s*\\[.*v:(\\d{1,3})]?.*\$")

    /**
     * @return true if playing started, false if not.
     */
    fun add(vararg tracks: AudioTrack): Boolean {
        queue.add(*tracks)
        if (player.playingTrack == null) {
            player.playTrack(queue.take()!!)
            return true
        }
        return false
    }

    fun skip(range: IntRange): List<AudioTrack> {
        val rangeFirst = range.first.coerceAtMost(queue.tracks.size)
        val rangeLast = range.last.coerceAtMost(queue.tracks.size)
        val skipped = mutableListOf<AudioTrack>()
        var newRange = rangeFirst .. rangeLast 
        // Skip the first track if it is stored here
        newRange = if (newRange.contains(0) && player.playingTrack != null) {
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
    }

    fun resume() {
        player.isPaused = false
    }

    fun shuffle() {
        queue.shuffle()
    }

    fun stop() {
        queue.clear()
        player.stopTrack()
    }

    fun toggleRepeatTrack() {
        repeatTrack = !repeatTrack
	}

    fun seek(position: Long) {
        player.playingTrack.position = position
    }

    override fun onTrackStart(player: AudioPlayer, track: AudioTrack) {
        if (beans.botProps.announceTracks) {
            lastChannel?.sendMessageEmbeds(beans.nowPlayingCommand.buildEmbed(track))?.queue()
        }

        adjustVolumeFromQueueLabelVolumeMatcher(player, track)
    }

    /**
     * With the option to specify a queue label on track add, there is also the capability to specify a volume for
     * the track.  It will be in the form "[Some Queue Label, v:42] TrackIdentifierUrlOrPath".  If found, the volume
     * will be set to that when the track starts.
     */
    private fun adjustVolumeFromQueueLabelVolumeMatcher(player: AudioPlayer, track: AudioTrack) {
        val matcher: Matcher = queueLabelVolume.matcher(track.info.title)
        if (matcher.find() && matcher.group(1) != null) {
            player.volume = matcher.group(1).toInt().coerceAtLeast(1)
        }
    }

    override fun onTrackEnd(player: AudioPlayer, track: AudioTrack, endReason: AudioTrackEndReason) {
        if (endReason.mayStartNext) {
            if (repeatTrack) {
                queue.addFirst(track.makeClone())
            } else if (queueLooping) {
                queue.add(track.makeClone())
            }
        }
        
        val new = queue.take() ?: return
        player.playTrack(new)
    }

    override fun onTrackException(player: AudioPlayer, track: AudioTrack, exception: FriendlyException) {
        log.error("Track exception", exception)
    }

    override fun onTrackStuck(player: AudioPlayer, track: AudioTrack, thresholdMs: Long) {
        log.error("Track $track got stuck!")
    }

    override fun canProvide(): Boolean {
        return player.provide(frame)
    }

    override fun provide20MsAudio(): ByteBuffer {
        // flip to make it a read buffer
        (buffer as Buffer).flip()
        return buffer
    }

    override fun isOpus() = true
}
