package dev.arbjerg.ukulele.command

import dev.arbjerg.ukulele.audio.TrackTimeParser
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import org.springframework.stereotype.Component

@Component
class SeekCommand : Command("seek") {
    override suspend fun CommandContext.invoke() {
        val track = player.currentTrack ?: return reply("Not playing anything.")

        if (!track.isSeekable) {
            return reply("This track is not seekable")
        }

        if (argumentText.isBlank()) {
            return replyHelp()
        }

        val newPosition = parseTimeString(argumentText) ?: return replyHelp()
        if (newPosition > track.info.length) {
            player.skip(0..0)
            reply("Skipped `${track.info.title}`")
        } else {
            player.seek(newPosition)
            reply("Seeking `${track.info.title}` to ${formatTime(newPosition)}")
        }
    }

    fun parseTimeString(str: String): Long? = TrackTimeParser.parseTimeOffsetMs(str)

    fun formatTime(millis: Long): String? {
        if (millis == Long.MAX_VALUE) {
            return "LIVE"
        }
        val t = millis / 1000L
        val sec = (t % 60L).toInt()
        val min = (t % 3600L / 60L).toInt()
        val hrs = (t / 3600L).toInt()
        val timestamp: String =
            if (hrs != 0) {
                forceTwoDigits(hrs) + ":" + forceTwoDigits(min) + ":" + forceTwoDigits(sec)
            } else {
                forceTwoDigits(min) + ":" + forceTwoDigits(sec)
            }
        return timestamp
    }

    private fun forceTwoDigits(i: Int): String = if (i < 10) "0$i" else i.toString()

    override fun HelpContext.provideHelp() {
        addUsage("[[hh:]mm:]ss")
        addDescription("Seeks the current track to the selected position")
    }
}
