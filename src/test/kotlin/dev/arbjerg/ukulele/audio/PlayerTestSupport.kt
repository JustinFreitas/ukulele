package dev.arbjerg.ukulele.audio

import com.sedmelluq.discord.lavaplayer.player.AudioPlayer
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import dev.arbjerg.ukulele.api.PlayerEventPublisher
import dev.arbjerg.ukulele.command.NowPlayingCommand
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.data.GuildProperties
import dev.arbjerg.ukulele.data.GuildPropertiesService
import net.dv8tion.jda.api.sharding.ShardManager
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono

/**
 * Matches any value for a Kotlin non-null parameter (e.g. the `(GuildProperties) -> Unit` lambda on
 * [GuildPropertiesService.transform]). A bare `any()` returns null, which trips Kotlin's compiler-inserted
 * null-check when it flows into a statically non-null Kotlin parameter; routing it through an explicit
 * unchecked cast avoids that.
 */
fun <T> anyMatcher(): T {
    any<T>()
    @Suppress("UNCHECKED_CAST")
    return null as T
}

/**
 * A real [Player] backed by mocked Lavaplayer/Spring beans, for tests (in `audio` or `command`)
 * that need genuine getter/setter state (volume, showQueueOnSkip, queue) rather than a stateless mock.
 */
class TestPlayerHarness(
    val player: Player,
    val audioPlayer: AudioPlayer,
    val apm: AudioPlayerManager,
    val botProps: BotProps,
    val guildProperties: GuildProperties,
)

fun newTestPlayer(
    guildProperties: GuildProperties = GuildProperties(1L, 500),
    minVolume: Int = 0,
    maxVolume: Int = 100,
): TestPlayerHarness {
    val apm = mock(AudioPlayerManager::class.java)
    val audioPlayer = mock(AudioPlayer::class.java)
    `when`(apm.createPlayer()).thenReturn(audioPlayer)

    val botProps = mock(BotProps::class.java)
    `when`(botProps.minVolume).thenReturn(minVolume)
    `when`(botProps.maxVolume).thenReturn(maxVolume)
    `when`(botProps.repeatTrack).thenReturn(false)
    `when`(botProps.queueLooping).thenReturn(false)
    `when`(botProps.showQueueOnSkip).thenReturn(false)

    val guildPropertiesService = mock(GuildPropertiesService::class.java)
    `when`(guildPropertiesService.transform(anyLong(), anyMatcher())).thenReturn(Mono.just(guildProperties))

    val beans =
        Player.Beans(
            apm,
            guildPropertiesService,
            mock(NowPlayingCommand::class.java),
            botProps,
            mock(PlayerEventPublisher::class.java),
            mock(ShardManager::class.java),
        )

    return TestPlayerHarness(Player(beans, guildProperties), audioPlayer, apm, botProps, guildProperties)
}
