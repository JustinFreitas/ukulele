package dev.arbjerg.ukulele.jda

import com.sedmelluq.discord.lavaplayer.track.AudioTrack
import dev.arbjerg.ukulele.features.HelpContext
import dev.arbjerg.ukulele.utils.TextUtils
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import org.slf4j.Logger
import org.slf4j.LoggerFactory

abstract class Command(
    val name: String,
    vararg val aliases: String,
) {
    val log: Logger = LoggerFactory.getLogger(javaClass)

    suspend fun invoke0(ctx: CommandContext) {
        ctx.apply { invoke() }
    }

    fun provideHelp0(ctx: HelpContext) {
        ctx.apply { provideHelp() }
    }

    /**
     * Builds the Discord slash command registration for this command. Every command gets a single
     * optional STRING option `args` which maps to [CommandContext.argumentText], so command bodies
     * stay unchanged. The description is reused from [provideHelp]. Aliases are not registered as
     * separate slash commands (Discord has no alias concept); they remain for the prefix path.
     */
    open fun buildSlashData(): SlashCommandData =
        Commands
            .slash(name, shortDescription())
            .addOption(OptionType.STRING, "args", "Optional command arguments", false)

    /** A <=100 char description for Discord, derived from this command's provideHelp text. */
    private fun shortDescription(): String {
        val help = HelpContext(null, this)
        provideHelp0(help)
        return (help.firstDescription() ?: "$name command").take(100)
    }

    fun StringBuilder.listQueueDurationAndLength(
        tracks: List<AudioTrack>,
        totalDuration: Long,
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
