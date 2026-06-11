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
}
