package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.audio.PlayerRegistry
import dev.arbjerg.ukulele.audio.TrackStartOffset
import dev.arbjerg.ukulele.audio.local.LocalPathInputUtils
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import dev.arbjerg.ukulele.utils.TextUtils
import kotlinx.coroutines.suspendCancellableCoroutine
import net.dv8tion.jda.api.Permission
import org.springframework.stereotype.Component
import kotlin.coroutines.resume

@Component
class PlayCommand(
    val players: PlayerRegistry,
    val apm: AudioPlayerManager,
    val botProps: BotProps,
    val queueCommand: QueueCommand,
) : Command("play", "p") {
    override suspend fun CommandContext.invoke() {
        if (!ensureVoiceChannel()) return

        players.get(guild, guildProperties).lastChannel = channel
        val usingConfiguredDefaultPlaylist = argumentText.trim().isEmpty()
        val identifiers =
            if (usingConfiguredDefaultPlaylist) {
                botProps.playlist.split(PIPE_CHAR)
            } else {
                argumentText.split(PIPE_CHAR)
            }

        // Resolve every identifier first, accumulating the tracks, then add them all to the queue in
        // a single bulk operation (one queue insert, one state publish, one summary message).
        val accepted = mutableListOf<AudioTrack>()
        var filteredCount = 0
        val failures = mutableListOf<String>()

        for (rawIdentifier in identifiers) {
            val identifier = rawIdentifier.trim()
            if (identifier.isEmpty()) continue
            val parsed = LocalPathInputUtils.parsePlayIdentifier(identifier) ?: continue

            val queueLabel = parsed.queueLabel
            val source = parsed.source
            val startOffsetMs = parsed.startOffsetMs

            // Configured default playlist comes from trusted ukulele.yml; direct Discord args enforce invoker check.
            val denial =
                LocalPathInputUtils.checkPathAccess(
                    source = source,
                    botProps = botProps,
                    isDiscordInvoker = !usingConfiguredDefaultPlaylist,
                    invokerId = invoker.id,
                )
            if (denial != null) {
                failures.add(denial)
                continue
            }

            awaitLoad(source) { item ->
                when (item) {
                    is AudioTrack -> {
                        collect(item, queueLabel, startOffsetMs, accepted) { filteredCount++ }
                    }

                    is AudioPlaylist -> {
                        if (item.isSearchResult) {
                            item.tracks.firstOrNull()?.let { collect(it, queueLabel, startOffsetMs, accepted) { filteredCount++ } }
                        } else {
                            item.tracks.forEach { collect(it, queueLabel, null, accepted) { filteredCount++ } }
                        }
                    }

                    LoadOutcome.NoMatch -> {
                        failures.add("Nothing found for “${TextUtils.truncate(identifier, 120)}”")
                    }

                    is LoadOutcome.Failed -> {
                        failures.add(item.message)
                    }
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
    ) {
        val loaded =
            kotlinx.coroutines.withTimeoutOrNull(45_000L) {
                suspendCancellableCoroutine { cont ->
                    apm.loadItemOrdered(
                        player,
                        source,
                        object : AudioLoadResultHandler {
                            override fun trackLoaded(track: AudioTrack) {
                                if (cont.isActive) {
                                    onResult(track)
                                    cont.resume(Unit)
                                }
                            }

                            override fun playlistLoaded(playlist: AudioPlaylist) {
                                if (cont.isActive) {
                                    onResult(playlist)
                                    cont.resume(Unit)
                                }
                            }

                            override fun noMatches() {
                                if (cont.isActive) {
                                    onResult(LoadOutcome.NoMatch)
                                    cont.resume(Unit)
                                }
                            }

                            override fun loadFailed(exception: FriendlyException) {
                                if (cont.isActive) {
                                    command.log.error("Handled exception occurred", exception)
                                    onResult(
                                        LoadOutcome.Failed(
                                            "An exception occurred!\n`${TextUtils.truncate(exception.message ?: "Unknown error", 200)}`",
                                        ),
                                    )
                                    cont.resume(Unit)
                                }
                            }
                        },
                    )
                }
            }
        if (loaded == null) {
            onResult(LoadOutcome.Failed("Timed out loading `${TextUtils.truncate(source, 120)}`"))
        }
    }

    /** Applies the optional queue label, then either collects the track or counts it as duration-filtered. */
    private fun collect(
        track: AudioTrack,
        queueLabel: String,
        startOffsetMs: Long?,
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
        val normalizedOffsetMs = startOffsetMs?.takeIf { it > 0L } ?: 0L
        val normalizedLabel = queueLabel.takeIf { it.isNotEmpty() }
        if (normalizedOffsetMs > 0L || normalizedLabel != null) {
            track.userData = TrackStartOffset(offsetMs = normalizedOffsetMs, queueLabel = normalizedLabel)
        }
        accepted.add(track)
    }

    private fun formatFailures(failures: List<String>): String {
        if (failures.isEmpty()) return ""
        val shown = failures.take(MAX_FAILURES_SHOWN)
        val extra = failures.size - shown.size
        return buildString {
            append(shown.joinToString("\n"))
            if (extra > 0) {
                append("\n…and $extra more error(s).")
            }
        }
    }

    private fun CommandContext.replySummary(
        accepted: List<AudioTrack>,
        filteredCount: Int,
        failures: List<String>,
    ) {
        if (accepted.isEmpty()) {
            val msg =
                when {
                    failures.isNotEmpty() -> {
                        formatFailures(failures)
                    }

                    filteredCount > 0 -> {
                        "Refusing to play $filteredCount tracks because they are all over ${botProps.trackDurationLimit} minutes long"
                    }

                    else -> {
                        "Nothing found."
                    }
                }
            reply(TextUtils.truncate(msg, QueueCommand.MAX_DISCORD_MESSAGE_LEN))
            return
        }

        val started = player.add(*accepted.toTypedArray())

        val header =
            buildString {
                if (accepted.size == 1) {
                    val title = TextUtils.truncate(accepted.first().info.title, 150)
                    append(if (started) "Started playing `$title`" else "Added `$title`")
                } else {
                    val firstTitle = TextUtils.truncate(accepted.first().info.title, 120)
                    append("Added `${accepted.size}` tracks to the queue.")
                    if (started) append(" Now playing `$firstTitle`.")
                }
                if (filteredCount > 0) {
                    append(" `$filteredCount` tracks were ignored because they are over ${botProps.trackDurationLimit} minutes long.")
                }
                if (failures.isNotEmpty()) {
                    append("\n").append(formatFailures(failures))
                }
            }

        val message =
            if (accepted.size >= 2) {
                val remainingBudget = (QueueCommand.MAX_DISCORD_MESSAGE_LEN - header.length - 2).coerceAtLeast(0)
                val queueBlock = queueCommand.printQueue(player, 1)
                if (remainingBudget > 80) {
                    "$header\n\n${TextUtils.truncate(queueBlock, remainingBudget)}"
                } else {
                    TextUtils.truncate(header, QueueCommand.MAX_DISCORD_MESSAGE_LEN)
                }
            } else {
                TextUtils.truncate(header, QueueCommand.MAX_DISCORD_MESSAGE_LEN)
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
            val canTalk = selfMember.hasPermission(theirVc, Permission.VOICE_CONNECT, Permission.VOICE_SPEAK)
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
        addUsage("<url_or_path>[|<url_or_path>]")
        addDescription("Add the given track or folder to the queue. Multiple entries can be separated with a pipe character |.")
    }

    companion object {
        const val PIPE_CHAR = "|"
        private const val MAX_FAILURES_SHOWN = 5
    }
}
