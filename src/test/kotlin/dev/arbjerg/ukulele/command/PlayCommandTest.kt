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

    @Test
    fun `invoke stores queueLabel in userData when prependQueueLabelToTitle is false`() {
        botProps.prependQueueLabelToTitle = false
        val player = mock(Player::class.java)
        val guild = mock(Guild::class.java)
        val selfMember = mock(SelfMember::class.java)
        val botVoiceState = mock(GuildVoiceState::class.java)
        val sharedChannel = mock(AudioChannelUnion::class.java)
        val invoker = mock(Member::class.java)
        val userVoiceState = mock(GuildVoiceState::class.java)
        val guildProps = GuildProperties(1L)

        `when`(guild.selfMember).thenReturn(selfMember)
        `when`(selfMember.voiceState).thenReturn(botVoiceState)
        `when`(botVoiceState.channel).thenReturn(sharedChannel)
        `when`(invoker.voiceState).thenReturn(userVoiceState)
        `when`(userVoiceState.channel).thenReturn(sharedChannel)
        `when`(players.get(guild, guildProps)).thenReturn(player)

        val loadedTrack = mock(com.sedmelluq.discord.lavaplayer.track.AudioTrack::class.java)
        val info =
            com.sedmelluq.discord.lavaplayer.track
                .AudioTrackInfo("Clean Title", "Author", 60_000L, "id", false, "uri")
        var storedUserData: Any? = null
        `when`(loadedTrack.info).thenReturn(info)
        `when`(loadedTrack.duration).thenReturn(60_000L)
        org.mockito.Mockito
            .doAnswer { inv ->
                storedUserData = inv.getArgument(0)
                null
            }.`when`(loadedTrack)
            .userData = org.mockito.ArgumentMatchers.any()

        `when`(
            apm.loadItemOrdered(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq("https://example.com/song.mp3"),
                org.mockito.ArgumentMatchers.any(),
            ),
        ).thenAnswer { inv ->
            val handler = inv.getArgument<com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler>(2)
            handler.trackLoaded(loadedTrack)
            java.util.concurrent.CompletableFuture
                .completedFuture(null)
        }

        val beans = CommandContext.Beans(players)
        val ctx =
            RecordingCommandContext(
                beans,
                guildProps,
                guild,
                mock(GuildMessageChannel::class.java),
                invoker,
                command,
                "[Dungeon, v:42] https://example.com/song.mp3#t=30s",
            )

        command.invokeBlocking(ctx)

        assertEquals("Clean Title", loadedTrack.info.title)
        val meta = storedUserData as dev.arbjerg.ukulele.audio.TrackStartOffset
        assertEquals(30_000L, meta.offsetMs)
        assertEquals("[Dungeon, v:42]", meta.queueLabel)
    }
}
