package dev.arbjerg.ukulele.api

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.audio.PlayerRegistry
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.data.GuildPropertiesService
import kotlinx.coroutines.suspendCancellableCoroutine
import net.dv8tion.jda.api.sharding.ShardManager
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.CrossOrigin
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import kotlin.coroutines.resume

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = ["*"])
class PlayerController(
    val playerRegistry: PlayerRegistry,
    val guildPropertiesService: GuildPropertiesService,
    val shardManager: ShardManager,
    val apm: AudioPlayerManager,
    val botProps: BotProps,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(PlayerController::class.java)

    @GetMapping("/guilds")
    fun getGuilds(): List<GuildDto> {
        return shardManager.guilds.map { guild ->
            val player = playerRegistry.getExisting(guild.idLong)
            val isPlaying = player?.currentTrack != null && !player.isPaused
            GuildDto(guild.id, guild.name, isPlaying)
        }
    }

    @GetMapping("/player/{guildId}")
    suspend fun getPlayer(
        @PathVariable guildId: Long,
    ): PlayerStatusDto {
        val guild = shardManager.getGuildById(guildId) ?: throw RuntimeException("Guild not found")
        val properties = guildPropertiesService.getAwait(guildId)
        val player = playerRegistry.get(guild, properties)

        val dto =
            PlayerStatusDto(
                guildId = guildId.toString(),
                isPaused = player.isPaused,
                volume = player.volume,
                repeatTrack = player.repeatTrack,
                queueLooping = player.queueLooping,
                currentTrack = player.currentTrack?.toDto(),
                remainingDuration = player.remainingDuration,
                minVolume = botProps.minVolume,
                maxVolume = botProps.maxVolume,
                isReplayGainEnabled = botProps.normalization,
                queueSize = player.upcomingTracks.size,
                channelId = guild.audioManager.connectedChannel?.id,
            )
        if (dto.currentTrack != null) {
            // Logging removed
        }
        return dto
    }

    @GetMapping("/player/{guildId}/queue")
    suspend fun getQueue(
        @PathVariable guildId: Long,
    ): List<TrackDto> {
        val guild = shardManager.getGuildById(guildId) ?: throw RuntimeException("Guild not found")
        val properties = guildPropertiesService.getAwait(guildId)
        val player = playerRegistry.get(guild, properties)
        return player.upcomingTracks.map { it.toDto() }
    }

    @GetMapping("/player/{guildId}/channels")
    fun getChannels(
        @PathVariable guildId: Long,
    ): List<VoiceChannelDto> {
        val guild = shardManager.getGuildById(guildId) ?: throw RuntimeException("Guild not found")
        return guild.voiceChannels.map { VoiceChannelDto(it.id, it.name) }
    }

    private fun getChannel(
        guild: net.dv8tion.jda.api.entities.Guild,
        channelId: String?,
    ): net.dv8tion.jda.api.entities.channel.middleman.AudioChannel? {
        if (channelId == "owner" && botProps.ownerId.isNotEmpty()) {
            val member = guild.getMemberById(botProps.ownerId)
            return member?.voiceState?.channel
        }
        return if (!channelId.isNullOrEmpty()) {
            guild.getVoiceChannelById(channelId)
        } else {
            guild.voiceChannels.firstOrNull()
        }
    }

    @PostMapping("/player/{guildId}/play")
    suspend fun play(
        @PathVariable guildId: Long,
        @RequestBody body: Map<String, String>,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: throw RuntimeException("Guild not found")
        val properties = guildPropertiesService.getAwait(guildId)
        val player = playerRegistry.get(guild, properties)

        // Ensure connected to voice
        val channelId = body["channelId"]
        if (!guild.audioManager.isConnected) {
            val channel = getChannel(guild, channelId)
            if (channel != null) {
                guild.audioManager.openAudioConnection(channel)
                guild.audioManager.sendingHandler = player
            }
        }

        if (body["fadeIn"] == "true") {
            player.isFadeInArmed = true
        }

        var identifiers = (body["url"] ?: "").split("|")
        // ... (rest of play method logic)
        if (identifiers.isNotEmpty() && identifiers.first().isEmpty()) {
            identifiers = botProps.playlist.split("|")
        }

        val pattern = java.util.regex.Pattern.compile("^\\s*(\\[.*])?\\s*(\\S+.*)$")
        val errors = mutableListOf<String>()

        identifiers.forEach { identifier ->
            val matcher = pattern.matcher(identifier)
            var source = identifier
            var queueLabel = ""
            if (matcher.find() && matcher.groupCount() == 2) {
                if (matcher.group(1) != null) queueLabel = matcher.group(1)
                if (matcher.group(2) != null) source = matcher.group(2)
            } else {
                log.warn("Pattern match failed for identifier: '{}', attempting to load as raw URL", identifier)
            }

            source = source.trim().removePrefix("<").removeSuffix(">")

            if (source.isNotEmpty()) {
                suspendCancellableCoroutine<Unit> { cont ->
                    apm.loadItemOrdered(
                        player,
                        source,
                        object : AudioLoadResultHandler {
                            override fun trackLoaded(track: AudioTrack) {
                                if (botProps.prependQueueLabelToTitle && queueLabel.isNotEmpty()) {
                                    track.info.title = "$queueLabel - ${track.info.title}"
                                }
                                player.add(track)
                                cont.resume(Unit)
                            }

                            override fun playlistLoaded(playlist: AudioPlaylist) {
                                if (playlist.isSearchResult) {
                                    val track = playlist.tracks.first()
                                    if (botProps.prependQueueLabelToTitle && queueLabel.isNotEmpty()) {
                                        track.info.title = "$queueLabel - ${track.info.title}"
                                    }
                                    player.add(track)
                                } else {
                                    player.add(*playlist.tracks.toTypedArray())
                                }
                                cont.resume(Unit)
                            }

                            override fun noMatches() {
                                log.warn("No matches found for source: {}", source)
                                errors.add("Nothing found for '$source'")
                                cont.resume(Unit)
                            }

                            override fun loadFailed(exception: FriendlyException) {
                                log.error("Load failed for source: {}", source, exception)
                                errors.add("Failed to load '$source': ${exception.message}")
                                cont.resume(Unit)
                            }
                        },
                    )
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, errors.joinToString("\n"))
        }
    }

    @PostMapping("/player/{guildId}/pause")
    suspend fun pause(
        @PathVariable guildId: Long,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        playerRegistry.get(guild, properties).pause()
    }

    @PostMapping("/player/{guildId}/resume")
    suspend fun resume(
        @PathVariable guildId: Long,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        playerRegistry.get(guild, properties).resume()
    }

    @PostMapping("/player/{guildId}/skip")
    suspend fun skip(
        @PathVariable guildId: Long,
        @RequestBody(required = false) body: Map<String, Int>?,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        val index = body?.get("index")
        val range = if (index != null && index >= 0) 0..index else 0..0
        playerRegistry.get(guild, properties).skip(range)
    }

    @PostMapping("/player/{guildId}/volume")
    suspend fun setVolume(
        @PathVariable guildId: Long,
        @RequestBody body: Map<String, Int>,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        val volume = body["volume"] ?: return
        playerRegistry.get(guild, properties).volume = volume
    }

    @PostMapping("/player/{guildId}/stop")
    suspend fun stop(
        @PathVariable guildId: Long,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        playerRegistry.get(guild, properties).stop()
        guild.audioManager.closeAudioConnection()
    }

    @PostMapping("/player/{guildId}/shuffle")
    suspend fun shuffle(
        @PathVariable guildId: Long,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        playerRegistry.get(guild, properties).shuffle()
    }

    @PostMapping("/player/{guildId}/repeat")
    suspend fun repeat(
        @PathVariable guildId: Long,
        @RequestBody body: Map<String, Boolean>,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        val isRepeat = body["repeat"] ?: false
        playerRegistry.get(guild, properties).repeatTrack = isRepeat
    }

    @PostMapping("/player/{guildId}/loop")
    suspend fun loop(
        @PathVariable guildId: Long,
        @RequestBody body: Map<String, Boolean>,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        val isLoop = body["loop"] ?: false
        playerRegistry.get(guild, properties).queueLooping = isLoop
    }

    @PostMapping("/player/{guildId}/seek")
    suspend fun seek(
        @PathVariable guildId: Long,
        @RequestBody body: Map<String, Long>,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        val position = body["position"] ?: return
        playerRegistry.get(guild, properties).seek(position)
    }

    @PostMapping("/player/{guildId}/say")
    suspend fun say(
        @PathVariable guildId: Long,
        @RequestBody body: Map<String, String>,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val properties = guildPropertiesService.getAwait(guildId)
        val text = body["text"] ?: return
        val player = playerRegistry.get(guild, properties)
        // Only say if there is a text channel the bot last spoke in
        player.lastChannel?.sendMessage(text)?.queue()
    }

    @PostMapping("/player/{guildId}/move")
    suspend fun move(
        @PathVariable guildId: Long,
        @RequestBody body: Map<String, String>,
    ) {
        val guild = shardManager.getGuildById(guildId) ?: return
        val channelId = body["channelId"] ?: return
        val channel = getChannel(guild, channelId)
        if (channel != null) {
            guild.audioManager.openAudioConnection(channel)
        }
    }
}
