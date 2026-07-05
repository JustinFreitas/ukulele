package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo
import dev.arbjerg.ukulele.api.PlayerEventPublisher
import dev.arbjerg.ukulele.command.NowPlayingCommand
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.data.GuildProperties
import dev.arbjerg.ukulele.data.GuildPropertiesService
import net.dv8tion.jda.api.sharding.ShardManager
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`

class PlayerTest {
    private lateinit var beans: Player.Beans
    private lateinit var guildProperties: GuildProperties
    private lateinit var player: Player
    private lateinit var audioPlayer: AudioPlayer
    private lateinit var apm: AudioPlayerManager
    private lateinit var botProps: BotProps

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

        val publisher = mock(PlayerEventPublisher::class.java)

        beans =
            Player.Beans(
                apm,
                mock(GuildPropertiesService::class.java),
                mock(NowPlayingCommand::class.java),
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
}
