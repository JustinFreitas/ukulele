package dev.arbjerg.ukulele.jda

import dev.arbjerg.ukulele.audio.Player
import dev.arbjerg.ukulele.audio.PlayerRegistry
import dev.arbjerg.ukulele.data.GuildProperties
import dev.arbjerg.ukulele.features.HelpContext
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import net.dv8tion.jda.api.utils.messages.MessageEditData
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Invocation context for a command. Commands are written against this abstraction (argumentText +
 * the reply* methods) serving Discord slash commands ([SlashCommandContext]).
 */
abstract class CommandContext(
    val beans: Beans,
    val guildProperties: GuildProperties,
    val guild: Guild,
    val channel: TextChannel,
    val invoker: Member,
    val command: Command,
    val prefix: String,
    /** Prefix + command name */
    val trigger: String,
) {
    @Component
    class Beans(
        val players: PlayerRegistry,
    ) {
        lateinit var commandManager: CommandManager
    }

    val player: Player by lazy { beans.players.get(guild, guildProperties) }

    val selfMember: Member get() = guild.selfMember

    /** The command argument text (after the trigger for messages, or the `args` option for slash). */
    abstract val argumentText: String

    abstract fun reply(msg: String)

    abstract fun replyMsg(msg: MessageCreateData)

    abstract fun replyEmbed(embed: MessageEmbed)

    fun replyHelp(forCommand: Command = command) {
        replyMsg(getHelp(forCommand))
    }

    private fun getHelp(forCommand: Command): MessageCreateData {
        val help = HelpContext(this, forCommand)
        forCommand.provideHelp0(help)
        return help.buildMessage()
    }

    fun handleException(t: Throwable) {
        command.log.error("Handled exception occurred", t)
        reply("An exception occurred!\n`${t.message}`")
    }
}


/**
 * Context for Discord slash commands. The dispatcher calls [SlashCommandInteractionEvent.deferReply]
 * first, so replies go through the interaction hook. The first reply edits the deferred ("thinking")
 * response and any further replies are sent as follow-ups, which keeps multi-reply commands such as
 * /play working cleanly.
 */
class SlashCommandContext(
    beans: Beans,
    guildProperties: GuildProperties,
    guild: Guild,
    channel: TextChannel,
    invoker: Member,
    val event: SlashCommandInteractionEvent,
    command: Command,
    prefix: String,
    trigger: String,
) : CommandContext(beans, guildProperties, guild, channel, invoker, command, prefix, trigger) {
    private val firstReply = AtomicBoolean(true)

    override val argumentText: String = event.getOption("args")?.asString?.trim() ?: ""

    override fun reply(msg: String) {
        if (firstReply.getAndSet(false)) {
            event.hook.editOriginal(msg).queue()
        } else {
            event.hook.sendMessage(msg).queue()
        }
    }

    override fun replyMsg(msg: MessageCreateData) {
        if (firstReply.getAndSet(false)) {
            event.hook.editOriginal(MessageEditData.fromCreateData(msg)).queue()
        } else {
            event.hook.sendMessage(msg).queue()
        }
    }

    override fun replyEmbed(embed: MessageEmbed) {
        if (firstReply.getAndSet(false)) {
            event.hook.editOriginalEmbeds(embed).queue()
        } else {
            event.hook.sendMessageEmbeds(embed).queue()
        }
    }
}
