package dev.arbjerg.ukulele.command

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import dev.arbjerg.ukulele.utils.TextUtils
import org.springframework.stereotype.Component

@Suppress("SameParameterValue")
@Component
class SkipCommand : Command("skip", "s") {
    override suspend fun CommandContext.invoke() {
        val args = argumentText.split("\\s+".toRegex())
        when {
            args.isEmpty() || args[0].isEmpty() -> skipNext()
            args[0] == "toggleshowqueue" -> toggleShowQueueOnSkip()
            args.size == 1 -> skipIndex(args[0].toInt())
            else -> skipRange()
        }
    }

    private fun CommandContext.skipNext() {
        printSkipped(player.skip(0..0))
    }

    private fun CommandContext.toggleShowQueueOnSkip() {
        player.showQueueOnSkip = !player.showQueueOnSkip
        val showQueueOnSkipMessage = when(player.showQueueOnSkip) {
            true -> "Show Queue On Skip is on.\r"
            false -> "Show Queue On Skip is off.\r"
        }

        reply(showQueueOnSkipMessage)
    }

    private fun CommandContext.skipIndex(i: Int) {
        val ind = (i - 1).coerceAtLeast(0)
        if (ind == 0) {
            player.seek(0)
            reply("Skipping to current track (restarting)")
        } else if (ind > player.tracks.size - 1) {
            player.stop()
            reply("Skipping past end of queue, player stopped.")
        } else {
            val endRange = (ind - 1).coerceAtLeast(0)
            printSkipped(player.skip(0..endRange))
        }
    }

    private fun CommandContext.skipRange() {
        val args = argumentText.split("\\s+".toRegex())

        val n1 = (args[0].toInt() - 1).coerceAtLeast(0)
        var n2 = args[1].toInt()
        if (n2 > player.tracks.size) {
            player.stop()
            reply("Skipping past end of queue, player stopped.")
        } else {
            n2 = (n2 - 1).coerceAtLeast(0)
            printSkipped(player.skip(n1..n2))
        }
    }

    private fun listTracksInQueue(tracks: List<AudioTrack>) = buildString {
        tracks.forEachIndexed { i, t ->
            appendLine("`[${i + 1}]` **${t.info.title}** `[${if (t.info.isStream) "Live" else TextUtils.humanReadableTime(t.duration)}]`")
        }
    }

    private fun CommandContext.listQueueIfLoopingEnabled(): String {
        val totalDuration = player.remainingDuration
        val tracks = player.tracks
        return when(player.tracks.isNotEmpty() && player.queueLooping) {
            true -> buildString {
                append("Queue:\n")
                append(listTracksInQueue(tracks))
                listQueueDurationAndLength(tracks, totalDuration)
            }
            false -> ""
        }
    }

    private fun CommandContext.printSkipped(skipped: List<AudioTrack>) {
        val playing = when (player.tracks.isEmpty()) {
            true -> "The queue is empty and the player is stopped."
            false -> "Playing " + player.tracks.first().info.title
        }

        val queue = listQueueIfLoopingEnabled()
        var skippedMessage = when (skipped.size) {
            0 -> playing
            1 -> """
            Skipped `${skipped.first().info.title}`
            
            `${playing}`
            """.trimIndent()
            else -> """
                Skipped `${skipped.size} tracks`
                
            `${playing}`
            """.trimIndent()
        }

        if (player.showQueueOnSkip) {
            skippedMessage = skippedMessage + "\n\n" + queue
        }

        reply(skippedMessage)
    }

    override fun HelpContext.provideHelp() {
        addUsage("[index]")
        addDescription("Skips to queue position.")
        addDescription("Skips to next track if no number is given.")
    }
}