package dev.arbjerg.ukulele.api

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import dev.arbjerg.ukulele.audio.Player
import dev.arbjerg.ukulele.audio.PlayerRegistry
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.data.GuildProperties
import dev.arbjerg.ukulele.data.GuildPropertiesRepository
import dev.arbjerg.ukulele.data.GuildPropertiesService
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel
import net.dv8tion.jda.api.managers.AudioManager
import net.dv8tion.jda.api.sharding.ShardManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import reactor.core.publisher.Mono

class PlayerControllerTest {
    private lateinit var playerRegistry: PlayerRegistry
    private lateinit var guildPropertiesRepo: GuildPropertiesRepository
    private lateinit var guildPropertiesService: GuildPropertiesService
    private lateinit var shardManager: ShardManager
    private lateinit var apm: AudioPlayerManager
    private lateinit var botProps: BotProps
    private lateinit var controller: PlayerController

    private val guildId = 12345L

    @BeforeEach
    fun setUp() {
        playerRegistry = mock(PlayerRegistry::class.java)
        guildPropertiesRepo = mock(GuildPropertiesRepository::class.java)
        `when`(guildPropertiesRepo.findById(guildId)).thenReturn(Mono.just(GuildProperties(guildId, 100)))
        guildPropertiesService = GuildPropertiesService(guildPropertiesRepo)
        shardManager = mock(ShardManager::class.java)
        apm = mock(AudioPlayerManager::class.java)
        botProps = BotProps()
        controller = PlayerController(playerRegistry, guildPropertiesService, shardManager, apm, botProps)
    }

    @Test
    fun `getGuilds maps active and idle guilds correctly`() {
        val guild1 = mock(Guild::class.java)
        `when`(guild1.id).thenReturn("1")
        `when`(guild1.idLong).thenReturn(1L)
        `when`(guild1.name).thenReturn("Active Guild")

        val guild2 = mock(Guild::class.java)
        `when`(guild2.id).thenReturn("2")
        `when`(guild2.idLong).thenReturn(2L)
        `when`(guild2.name).thenReturn("Idle Guild")

        val player1 = mock(Player::class.java)
        val track = mock(com.sedmelluq.discord.lavaplayer.track.AudioTrack::class.java)
        `when`(player1.currentTrack).thenReturn(track)
        `when`(player1.isPaused).thenReturn(false)

        `when`(shardManager.guilds).thenReturn(listOf(guild1, guild2))
        `when`(playerRegistry.getExisting(1L)).thenReturn(player1)
        `when`(playerRegistry.getExisting(2L)).thenReturn(null)

        val guilds = controller.getGuilds()
        assertEquals(2, guilds.size)
        assertEquals("Active Guild", guilds[0].name)
        assertTrue(guilds[0].isPlaying)
        assertEquals("Idle Guild", guilds[1].name)
        assertFalse(guilds[1].isPlaying)
    }

    @Test
    fun `getPlayer returns status dto when guild exists`() =
        runBlocking {
            val guild = mock(Guild::class.java)
            val audioManager = mock(AudioManager::class.java)
            val player = mock(Player::class.java)

            `when`(shardManager.getGuildById(guildId)).thenReturn(guild)
            `when`(guild.audioManager).thenReturn(audioManager)
            `when`(audioManager.connectedChannel).thenReturn(null)
            `when`(player.isPaused).thenReturn(false)
            `when`(player.volume).thenReturn(80)
            `when`(player.repeatTrack).thenReturn(false)
            `when`(player.queueLooping).thenReturn(false)
            `when`(player.currentTrack).thenReturn(null)
            `when`(player.remainingDuration).thenReturn(0L)
            `when`(player.upcomingTracks).thenReturn(emptyList())

            val props = guildPropertiesService.getAwait(guildId)
            `when`(playerRegistry.get(guild, props)).thenReturn(player)

            val status = controller.getPlayer(guildId)
            assertEquals(guildId.toString(), status.guildId)
            assertFalse(status.isPaused)
            assertEquals(80, status.volume)
            assertEquals(0, status.queueSize)
        }

    @Test
    fun `getPlayer throws when guild is not found`() {
        `when`(shardManager.getGuildById(999L)).thenReturn(null)

        assertThrows(RuntimeException::class.java) {
            runBlocking { controller.getPlayer(999L) }
        }
    }

    @Test
    fun `getChannels returns list of voice channels`() {
        val guild = mock(Guild::class.java)
        val vc1 = mock(VoiceChannel::class.java)
        `when`(vc1.id).thenReturn("101")
        `when`(vc1.name).thenReturn("General Voice")

        val vc2 = mock(VoiceChannel::class.java)
        `when`(vc2.id).thenReturn("102")
        `when`(vc2.name).thenReturn("Music Room")

        `when`(guild.voiceChannels).thenReturn(listOf(vc1, vc2))
        `when`(shardManager.getGuildById(guildId)).thenReturn(guild)

        val channels = controller.getChannels(guildId)
        assertEquals(2, channels.size)
        assertEquals("General Voice", channels[0].name)
        assertEquals("Music Room", channels[1].name)
    }

    @Test
    fun `pause resumes stops and volume mutate player state`() =
        runBlocking {
            val guild = mock(Guild::class.java)
            val audioManager = mock(AudioManager::class.java)
            val player = mock(Player::class.java)

            `when`(shardManager.getGuildById(guildId)).thenReturn(guild)
            `when`(guild.audioManager).thenReturn(audioManager)

            val props = guildPropertiesService.getAwait(guildId)
            `when`(playerRegistry.get(guild, props)).thenReturn(player)

            controller.pause(guildId)
            verify(player).pause()

            controller.resume(guildId)
            verify(player).resume()

            controller.setVolume(guildId, VolumeRequest(120))
            verify(player).volume = 120

            controller.shuffle(guildId)
            verify(player).shuffle()

            controller.stop(guildId)
            verify(player).stop()
            verify(audioManager).closeAudioConnection()
        }
}
