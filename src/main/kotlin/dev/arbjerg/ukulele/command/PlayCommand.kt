package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.audio.PlayerRegistry
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import kotlinx.coroutines.suspendCancellableCoroutine
import net.dv8tion.jda.api.Permission
import org.springframework.stereotype.Component
import java.util.regex.Pattern
import kotlin.coroutines.resume

@Component
class PlayCommand(
    val players: PlayerRegistry,
    val apm: AudioPlayerManager,
    val botProps: BotProps,
) : Command("play", "p") {
    val pattern: Pattern = Pattern.compile("^\\s*(\\[.*])?\\s*(\\S+.*)$")

    override suspend fun CommandContext.invoke() {
        if (!ensureVoiceChannel()) return

        players.get(guild, guildProperties).lastChannel = channel
        var identifiers = argumentText.split(PIPE_CHAR)
        if (identifiers.isNotEmpty() && identifiers.first().isEmpty()) {
            identifiers = botProps.playlist.split(PIPE_CHAR)
        }

        // Resolve every identifier first, accumulating the tracks, then add them all to the queue in
        // a single bulk operation (one queue insert, one state publish, one summary message).
        val accepted = mutableListOf<AudioTrack>()
        var filteredCount = 0
        val failures = mutableListOf<String>()

        for (rawIdentifier in identifiers) {
            val identifier = rawIdentifier.trim()
            // identifier format is: [Some Label] Source URL or local drive file path
            val matcher = pattern.matcher(identifier)
            if (!(matcher.find() && matcher.groupCount() == 2)) continue

            val queueLabel = if (matcher.group(1) != null) matcher.group(1).trim() else ""
            val source =
                if (matcher.group(2) != null) {
                    matcher
                        .group(2)
                        .trim()
                        .removePrefix("<")
                        .removeSuffix(">")
                } else {
                    ""
                }
            if (source.isEmpty()) continue

            awaitLoad(source) { item ->
                when (item) {
                    is AudioTrack -> collect(item, queueLabel, accepted) { filteredCount++ }
                    is AudioPlaylist -> {
                        if (item.isSearchResult) {
                            item.tracks.firstOrNull()?.let { collect(it, queueLabel, accepted) { filteredCount++ } }
                        } else {
                            item.tracks.forEach { collect(it, queueLabel, accepted) { filteredCount++ } }
                        }
                    }
                    LoadOutcome.NoMatch -> failures.add("Nothing found for “$identifier”")
                    is LoadOutcome.Failed -> failures.add(item.message)
                }
            }
        }

        replySummary(accepted, filteredCount, failures)
    }

    /** Marker outcomes for [awaitLoad] alongside the lavaplayer AudioTrack / AudioPlaylist results. */
    private sealed interface LoadOutcome {
        object NoMatch : LoadOutcome

        data class Failed(
            val message: String,
        ) : LoadOutcome
    }

    /** Suspends until the given source resolves, invoking [onResult] with the AudioTrack, AudioPlaylist, or a LoadOutcome. */
    private suspend fun CommandContext.awaitLoad(
        source: String,
        onResult: (Any) -> Unit,
    ) = suspendCancellableCoroutine { cont ->
        apm.loadItemOrdered(
            this,
            source,
            object : AudioLoadResultHandler {
                override fun trackLoaded(track: AudioTrack) {
                    onResult(track)
                    cont.resume(Unit)
                }

                override fun playlistLoaded(playlist: AudioPlaylist) {
                    onResult(playlist)
                    cont.resume(Unit)
                }

                override fun noMatches() {
                    onResult(LoadOutcome.NoMatch)
                    cont.resume(Unit)
                }

                override fun loadFailed(exception: FriendlyException) {
                    command.log.error("Handled exception occurred", exception)
                    onResult(LoadOutcome.Failed("An exception occurred!\n`${exception.message}`"))
                    cont.resume(Unit)
                }
            },
        )
    }

    /** Applies the optional queue label, then either collects the track or counts it as duration-filtered. */
    private fun collect(
        track: AudioTrack,
        queueLabel: String,
        accepted: MutableList<AudioTrack>,
        onFiltered: () -> Unit,
    ) {
        if (track.isOverDurationLimit) {
            onFiltered()
            return
        }
        if (botProps.prependQueueLabelToTitle && queueLabel.isNotEmpty()) {
            track.info.title = "$queueLabel - ${track.info.title}"
        }
        accepted.add(track)
    }

    private fun CommandContext.replySummary(
        accepted: List<AudioTrack>,
        filteredCount: Int,
        failures: List<String>,
    ) {
        if (accepted.isEmpty()) {
            when {
                failures.isNotEmpty() -> reply(failures.joinToString("\n"))
                filteredCount > 0 ->
                    reply("Refusing to play $filteredCount tracks because they are all over ${botProps.trackDurationLimit} minutes long")
                else -> reply("Nothing found.")
            }
            return
        }

        val started = player.add(*accepted.toTypedArray())

        val message =
            buildString {
                if (accepted.size == 1) {
                    val title = accepted.first().info.title
                    append(if (started) "Started playing `$title`" else "Added `$title`")
                } else {
                    append("Added `${accepted.size}` tracks to the queue.")
                    if (started) append(" Now playing `${accepted.first().info.title}`.")
                }
                if (filteredCount > 0) {
                    append(" `$filteredCount` tracks were ignored because they are over ${botProps.trackDurationLimit} minutes long.")
                }
                if (failures.isNotEmpty()) {
                    append("\n").append(failures.joinToString("\n"))
                }
            }
        reply(message)
    }

    private val AudioTrack.isOverDurationLimit: Boolean
        get() = botProps.trackDurationLimit > 0 && botProps.trackDurationLimit <= (duration / 60000)

    fun CommandContext.ensureVoiceChannel(): Boolean {
        val ourVc = guild.selfMember.voiceState?.channel
        val theirVc = invoker.voiceState?.channel

        if (ourVc == null && theirVc == null) {
            reply("You need to be in a voice channel")
            return false
        }

        if (ourVc != theirVc && theirVc != null) {
            val canTalk = selfMember.hasPermission(Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)
            if (!canTalk) {
                reply("I need permission to connect and speak in ${theirVc.name}")
                return false
            }

            guild.audioManager.openAudioConnection(theirVc)
            guild.audioManager.sendingHandler = player
            return true
        }

        return true
    }

    override fun HelpContext.provideHelp() {
        addUsage("<url>[|<url>]")
        addDescription("Add the given track to the queue.  Multiple tracks can be provided by separating the URLs with a pipe character |.")
    }

    companion object {
        const val PIPE_CHAR = "|"
    }
}
