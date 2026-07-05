package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.audio.Player
import dev.arbjerg.ukulele.config.BotProps
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import dev.arbjerg.ukulele.utils.TextUtils
import org.springframework.stereotype.Component

@Component
class QueueCommand(
    private val botProps: BotProps,
) : Command("queue", "q", "list") {
    private val pageSize = 10

    override suspend fun CommandContext.invoke() {
        reply(printQueue(player, argumentText.toIntOrNull() ?: 1))
    }

    fun printQueue(
        player: Player,
        pageIndex: Int,
    ): String {
        val repeatTrackMessage =
            when (player.repeatTrack) {
                true -> "Repeat Track is on.\n"
                false -> "Repeat Track is off.\n"
            }

        val queueLoopingMessage =
            when (player.queueLooping) {
                true -> "Queue Looping is on.\n"
                false -> "Queue Looping is off.\n"
            }

        val replayGainMessage =
            when (botProps.normalization) {
                true -> "ReplayGain normalization is on.\n"
                false -> "ReplayGain normalization is off.\n"
            }

        val totalDuration = player.remainingDuration
        val tracks = player.tracks
        if (tracks.isEmpty()) {
            return repeatTrackMessage + queueLoopingMessage + replayGainMessage + "The queue is empty."
        }

        return buildString {
            append(repeatTrackMessage)
            append(queueLoopingMessage)
            append(replayGainMessage)
            append(paginateQueue(tracks, player.currentTrack, pageIndex))
            listQueueDurationAndLength(tracks, totalDuration)
        }
    }

    private fun paginateQueue(
        tracks: List<AudioTrack>,
        currentTrack: AudioTrack?,
        index: Int,
    ) = buildString {
        val pageCount: Int = (tracks.size + pageSize - 1) / pageSize
        val pageIndex = index.coerceIn(1..pageCount)

        // Add header
        append("Page **$pageIndex** of **$pageCount**\n\n")

        val offset = pageSize * (pageIndex - 1)
        val pageEnd = (offset + pageSize).coerceAtMost(tracks.size)

        tracks.subList(offset, pageEnd).forEachIndexed { i, t ->
            // Show the applied gain value when known (the playing track); otherwise, when normalization
            // is enabled, show a bare badge meaning "ReplayGain will be applied if this track is tagged".
            val rg =
                TextUtils.replayGainLabel(t)?.let { " `🔊 RG $it`" }
                    ?: if (botProps.normalization && t != currentTrack) " `🔊 RG`" else ""
            appendLine(
                "`[${offset + i + 1}]` **${t.info.title}** `[${if (t.info.isStream) {
                    "Live"
                } else {
                    TextUtils.humanReadableTime(
                        t.duration,
                    )
                }}]`$rg",
            )
        }
    }

    override fun HelpContext.provideHelp() {
        addUsage("[page]")
        addDescription("Displays the queue, by default for page 1")
    }
}
