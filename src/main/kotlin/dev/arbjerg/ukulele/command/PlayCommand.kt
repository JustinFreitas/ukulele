package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist
import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.audio.Player
import dev.arbjerg.ukulele.audio.PlayerRegistry
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import net.dv8tion.jda.api.Permission
import org.springframework.stereotype.Component
import java.util.regex.Pattern

@Component
class PlayCommand(
    val players: PlayerRegistry,
    val apm: AudioPlayerManager,
    val botProps: BotProps
) : Command("play", "p") {
    val pattern: Pattern = Pattern.compile("^\\s*(\\[.*])?\\s*(\\S+.*)$")

    override suspend fun CommandContext.invoke() {
        if (!ensureVoiceChannel()) return

        players.get(guild, guildProperties).lastChannel = channel
        var identifiers = argumentText.split(PIPE_CHAR)
        if (identifiers.isNotEmpty() && identifiers.first().isEmpty()) {
            identifiers = botProps.playlist.split(PIPE_CHAR)
        }

        identifiers.forEach { identifier ->
            // identifier format is:  [Some Label] Source URL or local drive file path
            val matcher = pattern.matcher(identifier)
            if (matcher.find() && matcher.groupCount() == 2) {
                val queueLabel = if (matcher.group(1) != null) matcher.group(1) else ""
                val source = if (matcher.group(2) != null) matcher.group(2) else ""
                if (source.isNotEmpty()) {
                    apm.loadItemOrdered(this, source, Loader(this, player, identifier, queueLabel))
                }
            }
        }
    }

    fun CommandContext.ensureVoiceChannel(): Boolean {
        val ourVc = guild.selfMember.voiceState?.channel
        val theirVc = invoker.voiceState?.channel

        if (ourVc == null && theirVc == null) {
            reply("You need to be in a voice channel")
            return false
        }

        if (ourVc != theirVc && theirVc != null)  {
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

    inner class Loader(
            private val ctx: CommandContext,
            private val player: Player,
            private val identifier: String,
            private val queueLabel: String
    ) : AudioLoadResultHandler {
        override fun trackLoaded(track: AudioTrack) {
            if (track.isOverDurationLimit) {
                ctx.reply("Refusing to play `${track.info.title}` because it is over ${botProps.trackDurationLimit} minutes long")
                return
            }
            track.info.title = if (botProps.prependQueueLabelToTitle && queueLabel.isNotEmpty())
                                    "${queueLabel} - ${track.info.title}" else track.info.title
            val started = player.add(track)
            if (started) {
                ctx.reply("Started playing `${track.info.title}`")
            } else {
                ctx.reply("Added `${track.info.title}`")
            }
        }

        override fun playlistLoaded(playlist: AudioPlaylist) {
            val accepted = playlist.tracks.filter { !it.isOverDurationLimit }
            val filteredCount = playlist.tracks.size - accepted.size
            if (accepted.isEmpty()) {
                ctx.reply("Refusing to play $filteredCount tracks because because they are all over ${botProps.trackDurationLimit} minutes long")
                return
            }

            if (identifier.startsWith("ytsearch") || identifier.startsWith("ytmsearch") || identifier.startsWith("scsearch:")) {
                this.trackLoaded(accepted.component1())
                return
            }

            player.add(*accepted.toTypedArray())
            ctx.reply(buildString {
                append("Added `${accepted.size}` tracks from `${playlist.name}`.")
                if (filteredCount != 0) append(" `$filteredCount` tracks have been ignored because they are over ${botProps.trackDurationLimit} minutes long")
            })
        }

        override fun noMatches() {
            ctx.reply("Nothing found for “$identifier”")
        }

        override fun loadFailed(exception: FriendlyException) {
            ctx.handleException(exception)
        }

        private val AudioTrack.isOverDurationLimit: Boolean
            get() = botProps.trackDurationLimit > 0 && botProps.trackDurationLimit <= (duration / 60000)
    }

    override fun HelpContext.provideHelp() {
        addUsage("<url>[|<url>]")
        addDescription("Add the given track to the queue.  Multiple tracks can be provided by separating the URLs with a pipe character |.")
    }

    companion object {
        const val PIPE_CHAR = "|"
    }
}
