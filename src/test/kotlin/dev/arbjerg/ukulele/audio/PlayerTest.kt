package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackEndReason
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.ukulele.api.PlayerEventPublisher
import dev.arbjerg.ukulele.command.NowPlayingCommand
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.data.GuildProperties
import dev.arbjerg.ukulele.data.GuildPropertiesService
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.sharding.ShardManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono

class PlayerTest {
    private lateinit var beans: Player.Beans
    private lateinit var guildProperties: GuildProperties
    private lateinit var player: Player
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var apm: AudioPlayerManager
    private lateinit var botProps: BotProps
    private lateinit var publisher: PlayerEventPublisher
    private lateinit var guildPropertiesService: GuildPropertiesService
    private lateinit var nowPlayingCommand: NowPlayingCommand

    @BeforeEach
    fun setUp() {
        apm = mock(AudioPlayerManager::class.java)
        audioPlayer = mock(AudioPlayer::class.java)
        `when`(apm.createPlayer()).thenReturn(audioPlayer)

        botProps = mock(BotProps::class.java)
        `when`(botProps.minVolume).thenReturn(0)
        `when`(botProps.maxVolume).thenReturn(100)
        `when`(botProps.repeatTrack).thenReturn(false)
        `when`(botProps.queueLooping).thenReturn(false)
        `when`(botProps.showQueueOnSkip).thenReturn(false)
        `when`(botProps.announceTracks).thenReturn(false)
        `when`(botProps.normalization).thenReturn(false)

        publisher = mock(PlayerEventPublisher::class.java)
        guildPropertiesService = mock(GuildPropertiesService::class.java)
        `when`(guildPropertiesService.transform(anyLong(), anyMatcher())).thenReturn(Mono.just(GuildProperties(123L, 50)))
        nowPlayingCommand = mock(NowPlayingCommand::class.java)

        beans =
            Player.Beans(
                apm,
                guildPropertiesService,
                nowPlayingCommand,
                botProps,
                publisher,
                mock(ShardManager::class.java),
            )

        guildProperties = GuildProperties(123L, 50)
        player = Player(beans, guildProperties)
    }

    @Test
    fun `skip stops track when repeatTrack is enabled`() {
        // Arrange
        player.repeatTrack = true
        val track = mock(AudioTrack::class.java)
        val info = AudioTrackInfo("Title", "Author", 1000L, "id", true, "uri")
        `when`(track.info).thenReturn(info)
        `when`(audioPlayer.playingTrack).thenReturn(track)

        // Act
        // Skip range 0..0 means skip current track (as per logic: skip(0..0))
        player.skip(0..0)

        // Assert
        // Verify stopTrack is called.
        // This ensures the track is stopped (bypassing repeat) instead of just restarting.
        verify(audioPlayer, times(1)).stopTrack()
        verify(track, times(0)).position = 0 // Verify we didn't just seek to 0
    }

    @Test
    fun `removeTrackAt removes track from queue and publishes state`() {
        // Arrange
        val track1 = mock(AudioTrack::class.java)
        val track2 = mock(AudioTrack::class.java)
        val info1 = AudioTrackInfo("Title1", "Author1", 1000L, "id1", false, "uri1")
        val info2 = AudioTrackInfo("Title2", "Author2", 1000L, "id2", false, "uri2")
        `when`(track1.info).thenReturn(info1)
        `when`(track2.info).thenReturn(info2)
        `when`(audioPlayer.playingTrack).thenReturn(track1)
        player.add(track2)
        assert(player.upcomingTracks.size == 1)

        // Act
        val removed = player.removeTrackAt(0)

        // Assert
        assert(removed == track2)
        assert(player.upcomingTracks.isEmpty())
    }

    @Test
    fun `reorderQueue reorders tracks in queue and publishes state`() {
        // Arrange
        val track1 = mock(AudioTrack::class.java)
        val track2 = mock(AudioTrack::class.java)
        val track3 = mock(AudioTrack::class.java)
        val info1 = AudioTrackInfo("Title1", "Author1", 1000L, "id1", false, "uri1")
        val info2 = AudioTrackInfo("Title2", "Author2", 1000L, "id2", false, "uri2")
        val info3 = AudioTrackInfo("Title3", "Author3", 1000L, "id3", false, "uri3")
        `when`(track1.info).thenReturn(info1)
        `when`(track2.info).thenReturn(info2)
        `when`(track3.info).thenReturn(info3)
        `when`(audioPlayer.playingTrack).thenReturn(track1)
        player.add(track2)
        player.add(track3)
        assert(player.upcomingTracks[0] == track2)
        assert(player.upcomingTracks[1] == track3)

        // Act
        val success = player.reorderQueue(0, 1)

        // Assert
        assert(success)
        assert(player.upcomingTracks[0] == track3)
        assert(player.upcomingTracks[1] == track2)
    }

    private fun track(title: String): AudioTrack {
        val t = mock(AudioTrack::class.java)
        `when`(t.info).thenReturn(AudioTrackInfo(title, "Author", 1000L, "id-$title", false, "uri-$title"))
        return t
    }

    @Test
    fun `add starts playback when nothing is currently playing`() {
        `when`(audioPlayer.playingTrack).thenReturn(null)
        val newTrack = track("Title")

        val started = player.add(newTrack)

        assertTrue(started)
        verify(audioPlayer, times(1)).isPaused = false
        verify(audioPlayer, times(1)).playTrack(newTrack)
    }

    @Test
    fun `add just enqueues when something is already playing`() {
        val current = track("Current")
        `when`(audioPlayer.playingTrack).thenReturn(current)
        val newTrack = track("Title")

        val started = player.add(newTrack)

        assertFalse(started)
        verify(audioPlayer, never()).playTrack(newTrack)
        assertEquals(1, player.upcomingTracks.size)
    }

    @Test
    fun `volume setter scales into the configured min-max range and persists`() {
        player.volume = 500

        verify(audioPlayer, times(1)).volume = 50 // min=0, max=100 -> 0 + 500/1000 * 100 = 50
        verify(guildPropertiesService, times(1)).transform(anyLong(), anyMatcher())
        assertEquals(500, player.volume)
    }

    @Test
    fun `getOptimalVolumeStep computes a step from the configured range`() {
        assertEquals(10, player.getOptimalVolumeStep()) // ceil(1000 / 100) = 10
    }

    @Test
    fun `getOptimalVolumeStep falls back to 10 when the range is not positive`() {
        `when`(botProps.minVolume).thenReturn(50)
        `when`(botProps.maxVolume).thenReturn(50)

        assertEquals(10, player.getOptimalVolumeStep())
    }

    @Test
    fun `pause sets isPaused on the underlying player`() {
        player.pause()

        verify(audioPlayer, times(1)).isPaused = true
    }

    @Test
    fun `resume clears isPaused on the underlying player`() {
        player.resume()

        verify(audioPlayer, times(1)).isPaused = false
    }

    @Test
    fun `stop clears the queue and stops the underlying player`() {
        val current = track("Current")
        `when`(audioPlayer.playingTrack).thenReturn(current)
        player.add(track("Queued"))
        assertEquals(1, player.upcomingTracks.size)

        player.stop()

        verify(audioPlayer, times(1)).stopTrack()
        assertTrue(player.upcomingTracks.isEmpty())
    }

    @Test
    fun `toggleRepeatTrack flips repeatTrack both ways`() {
        assertFalse(player.repeatTrack)

        player.toggleRepeatTrack()
        assertTrue(player.repeatTrack)

        player.toggleRepeatTrack()
        assertFalse(player.repeatTrack)
    }

    @Test
    fun `onTrackStart announces the track when announceTracks is enabled`() {
        `when`(botProps.announceTracks).thenReturn(true)
        player.lastChannel = mock(TextChannel::class.java)
        val startingTrack = track("Song")

        player.onTrackStart(audioPlayer, startingTrack)

        verify(nowPlayingCommand, times(1)).buildEmbed(startingTrack)
    }

    @Test
    fun `onTrackStart does not announce when announceTracks is disabled`() {
        player.lastChannel = mock(TextChannel::class.java)

        player.onTrackStart(audioPlayer, track("Song"))

        verify(nowPlayingCommand, never()).buildEmbed(anyMatcher())
    }

    @Test
    fun `onTrackStart defers the queue-label volume until ReplayGain is known`() {
        val labeledTrack = track("[Some Label, v:42] Song")

        player.onTrackStart(audioPlayer, labeledTrack)

        // Nothing has decoded yet at this point, so the label volume must not have been applied.
        verify(audioPlayer, never()).volume = 42

        player.onTrackReplayGainResolved(audioPlayer, labeledTrack, null)

        verify(audioPlayer, times(1)).volume = 42
        assertEquals(420, player.volume)
    }

    @Test
    fun `queue-label volume is scaled into the configured range rather than written raw`() {
        // The bug this covers is invisible at maxVolume=100, where the scaled and raw values coincide.
        `when`(botProps.maxVolume).thenReturn(20)
        val labeledTrack = track("[Some Label, v:40] Song")

        player.onTrackStart(audioPlayer, labeledTrack)
        player.onTrackReplayGainResolved(audioPlayer, labeledTrack, null)

        // 40% of the 0..20 range is 8, not the raw 40 that would be double the slider's maximum.
        verify(audioPlayer, times(1)).volume = 8
        assertEquals(400, player.volume)
    }

    @Test
    fun `queue-label volume is skipped when the track carries ReplayGain`() {
        `when`(botProps.normalization).thenReturn(true)
        val labeledTrack = track("[Some Label, v:42] Song")

        player.onTrackStart(audioPlayer, labeledTrack)
        player.onTrackReplayGainResolved(audioPlayer, labeledTrack, -2.52f)

        verify(audioPlayer, never()).volume = 42
        assertEquals(50, player.volume)
    }

    @Test
    fun `queue-label volume still applies when normalization is on but the track has no ReplayGain`() {
        `when`(botProps.normalization).thenReturn(true)
        val labeledTrack = track("[Some Label, v:42] Song")

        player.onTrackStart(audioPlayer, labeledTrack)
        player.onTrackReplayGainResolved(audioPlayer, labeledTrack, null)

        verify(audioPlayer, times(1)).volume = 42
    }

    @Test
    fun `queue-label volume is applied on a track with no label only as a no-op`() {
        val plainTrack = track("Song with no label")

        player.onTrackStart(audioPlayer, plainTrack)
        player.onTrackReplayGainResolved(audioPlayer, plainTrack, null)

        assertEquals(50, player.volume)
    }

    @Test
    fun `onTrackStart skips label-volume parsing while a fade-in is armed`() {
        player.isFadeInArmed = true
        val labeledTrack = track("[Some Label, v:42] Song")

        player.onTrackStart(audioPlayer, labeledTrack)
        player.onTrackReplayGainResolved(audioPlayer, labeledTrack, null)

        verify(audioPlayer, never()).volume = 42
        assertFalse(player.isFadeInArmed)
    }

    @Test
    fun `onTrackEnd requeues a clone at the front when repeatTrack is enabled`() {
        player.repeatTrack = true
        val ending = track("Ending")
        val clone = track("Ending-clone")
        `when`(ending.makeClone()).thenReturn(clone)

        player.onTrackEnd(audioPlayer, ending, AudioTrackEndReason.FINISHED)

        verify(audioPlayer, times(1)).playTrack(clone)
    }

    @Test
    fun `repeating a labeled track leaves its title untouched`() {
        player.repeatTrack = true
        val labeled = track("[Some Label, v:42] Song")
        val clone = track("[Some Label, v:42] Song")
        `when`(labeled.makeClone()).thenReturn(clone)

        player.onTrackStart(audioPlayer, labeled)
        player.onTrackReplayGainResolved(audioPlayer, labeled, null)
        player.onTrackEnd(audioPlayer, labeled, AudioTrackEndReason.FINISHED)

        // The old implementation replaced the whole title with a bare number here.
        assertEquals("[Some Label, v:42] Song", labeled.info.title)
        assertEquals("[Some Label, v:42] Song", clone.info.title)
    }

    @Test
    fun `repeating a labeled track re-applies the label volume when untouched`() {
        player.repeatTrack = true
        val labeled = track("[Some Label, v:42] Song")
        val clone = track("[Some Label, v:42] Song")
        `when`(labeled.makeClone()).thenReturn(clone)

        player.onTrackStart(audioPlayer, labeled)
        player.onTrackReplayGainResolved(audioPlayer, labeled, null)
        player.onTrackEnd(audioPlayer, labeled, AudioTrackEndReason.FINISHED)

        player.onTrackStart(audioPlayer, clone)
        player.onTrackReplayGainResolved(audioPlayer, clone, null)

        verify(audioPlayer, times(2)).volume = 42
    }

    @Test
    fun `a manual volume change survives a repeat instead of snapping back to the label`() {
        player.repeatTrack = true
        val labeled = track("[Some Label, v:42] Song")
        val clone = track("[Some Label, v:42] Song")
        `when`(labeled.makeClone()).thenReturn(clone)

        player.onTrackStart(audioPlayer, labeled)
        player.onTrackReplayGainResolved(audioPlayer, labeled, null)

        // The user overrides the label mid-track.
        player.volume = 600
        player.onTrackEnd(audioPlayer, labeled, AudioTrackEndReason.FINISHED)

        player.onTrackStart(audioPlayer, clone)
        player.onTrackReplayGainResolved(audioPlayer, clone, null)

        // Only the first pass applied the label; the loop keeps the user's 60%.
        verify(audioPlayer, times(1)).volume = 42
        assertEquals(600, player.volume)
    }

    @Test
    fun `the label-volume suppression only lasts a single track`() {
        player.repeatTrack = true
        val labeled = track("[Some Label, v:42] Song")
        val clone = track("[Some Label, v:42] Song")
        val second = track("[Some Label, v:42] Song")
        `when`(labeled.makeClone()).thenReturn(clone)
        `when`(clone.makeClone()).thenReturn(second)

        player.onTrackStart(audioPlayer, labeled)
        player.onTrackReplayGainResolved(audioPlayer, labeled, null)
        player.volume = 600
        player.onTrackEnd(audioPlayer, labeled, AudioTrackEndReason.FINISHED)

        // First loop is suppressed...
        player.onTrackStart(audioPlayer, clone)
        player.onTrackReplayGainResolved(audioPlayer, clone, null)
        player.onTrackEnd(audioPlayer, clone, AudioTrackEndReason.FINISHED)

        // ...but the one after it is not, since the user did not touch the volume again.
        player.onTrackStart(audioPlayer, second)
        player.onTrackReplayGainResolved(audioPlayer, second, null)

        verify(audioPlayer, times(1)).volume = 42
    }

    @Test
    fun `onTrackEnd requeues a clone at the back when queueLooping is enabled`() {
        player.queueLooping = true
        val ending = track("Ending")
        val clone = track("Ending-clone")
        `when`(ending.makeClone()).thenReturn(clone)

        player.onTrackEnd(audioPlayer, ending, AudioTrackEndReason.FINISHED)

        verify(audioPlayer, times(1)).playTrack(clone)
    }

    @Test
    fun `onTrackEnd does not requeue when neither repeat nor loop is enabled`() {
        val ending = track("Ending")

        player.onTrackEnd(audioPlayer, ending, AudioTrackEndReason.FINISHED)

        verify(ending, never()).makeClone()
        verify(audioPlayer, never()).playTrack(any())
        verify(publisher, atLeastOnce()).publishUpdate(anyMatcher(), anyMatcher())
    }

    @Test
    fun `onTrackEnd does nothing when the end reason does not allow starting the next track`() {
        player.repeatTrack = true
        val ending = track("Ending")

        player.onTrackEnd(audioPlayer, ending, AudioTrackEndReason.REPLACED)

        verify(ending, never()).makeClone()
        verify(audioPlayer, never()).playTrack(any())
    }

    @Test
    fun `destroy clears the queue and destroys the underlying player`() {
        val current = track("Current")
        `when`(audioPlayer.playingTrack).thenReturn(current)
        player.add(track("Queued"))

        player.destroy()

        verify(audioPlayer, times(1)).destroy()
        assertTrue(player.upcomingTracks.isEmpty())
    }
}
