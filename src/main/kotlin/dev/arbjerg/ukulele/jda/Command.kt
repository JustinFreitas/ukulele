package dev.arbjerg.ukulele.jda

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.utils.TextUtils
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class Command(val name: String, vararg val aliases: String) {

    val log: Logger = LoggerFactory.getLogger(javaClass)

    suspend fun invoke0(ctx: CommandContext) {
        ctx.apply { invoke() }
    }

    fun provideHelp0(ctx: HelpContext) {
        ctx.apply { provideHelp() }
    }

    fun StringBuilder.listQueueDurationAndLength(
        tracks: List<AudioTrack>,
        totalDuration: Long
    ) {
        append("\nThere are **${tracks.size}** tracks with a remaining length of ")

        if (tracks.any { it.info.isStream }) {
            append("**${TextUtils.humanReadableTime(totalDuration)}** in the queue excluding streams.")
        } else {
            append("**${TextUtils.humanReadableTime(totalDuration)}** in the queue.")
        }
    }

    abstract suspend fun CommandContext.invoke()
    abstract fun HelpContext.provideHelp()

}