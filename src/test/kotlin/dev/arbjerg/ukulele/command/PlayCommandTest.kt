package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import dev.arbjerg.ukulele.audio.Player
import dev.arbjerg.ukulele.audio.PlayerRegistry
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.data.GuildProperties
import dev.arbjerg.ukulele.jda.CommandContext
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.GuildVoiceState
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.SelfMember
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel
import net.dv8tion.jda.api.entities.channel.unions.AudioChannelUnion
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class PlayCommandTest {
    private lateinit var players: PlayerRegistry
    private lateinit var apm: AudioPlayerManager
    private lateinit var botProps: BotProps
    private lateinit var queueCommand: QueueCommand
    private lateinit var command: PlayCommand

    @BeforeEach
    fun setUp() {
        players = mock(PlayerRegistry::class.java)
        apm = mock(AudioPlayerManager::class.java)
        botProps = BotProps()
        queueCommand = QueueCommand(botProps)
        command = PlayCommand(players, apm, botProps, queueCommand)
    }

    @Test
    fun `invoke fails when neither user nor bot is in voice channel`() {
        val player = mock(Player::class.java)
        val guild = mock(Guild::class.java)
        val selfMember = mock(SelfMember::class.java)
        val invoker = mock(Member::class.java)
        val guildProps = GuildProperties(1L)

        `when`(guild.selfMember).thenReturn(selfMember)
        `when`(selfMember.voiceState).thenReturn(null)
        `when`(invoker.voiceState).thenReturn(null)
        `when`(players.get(guild, guildProps)).thenReturn(player)

        val beans = CommandContext.Beans(players)
        val ctx =
            RecordingCommandContext(
                beans,
                guildProps,
                guild,
                mock(GuildMessageChannel::class.java),
                invoker,
                command,
                "https://example.com/song.mp3",
            )

        command.invokeBlocking(ctx)

        assertEquals(1, ctx.replies.size)
        assertEquals("You need to be in a voice channel", ctx.replies[0])
    }

    @Test
    fun `invoke fails when bot lacks permission in user voice channel`() {
        val player = mock(Player::class.java)
        val guild = mock(Guild::class.java)
        val selfMember = mock(SelfMember::class.java)
        val invoker = mock(Member::class.java)
        val userVoiceState = mock(GuildVoiceState::class.java)
        val userChannel = mock(AudioChannelUnion::class.java)
        val guildProps = GuildProperties(1L)

        `when`(guild.selfMember).thenReturn(selfMember)
        `when`(selfMember.voiceState).thenReturn(null)
        `when`(invoker.voiceState).thenReturn(userVoiceState)
        `when`(userVoiceState.channel).thenReturn(userChannel)
        `when`(userChannel.name).thenReturn("Party Channel")
        `when`(selfMember.hasPermission(userChannel, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)).thenReturn(false)
        `when`(players.get(guild, guildProps)).thenReturn(player)

        val beans = CommandContext.Beans(players)
        val ctx =
            RecordingCommandContext(
                beans,
                guildProps,
                guild,
                mock(GuildMessageChannel::class.java),
                invoker,
                command,
                "https://example.com/song.mp3",
            )

        command.invokeBlocking(ctx)

        assertEquals(1, ctx.replies.size)
        assertEquals("I need permission to connect and speak in Party Channel", ctx.replies[0])
    }
}
