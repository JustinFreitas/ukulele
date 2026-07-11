package dev.arbjerg.ukulele.command

import dev.arbjerg.ukulele.audio.Player
import dev.arbjerg.ukulele.audio.PlayerRegistry
import dev.arbjerg.ukulele.data.GuildProperties
import dev.arbjerg.ukulele.jda.Command
import dev.arbjerg.ukulele.jda.CommandContext
import kotlinx.coroutines.runBlocking
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.MessageEmbed
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel
import net.dv8tion.jda.api.utils.messages.MessageCreateData
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * A [CommandContext] that records replies instead of talking to JDA, so command logic can be
 * exercised without a live Discord connection.
 */
class RecordingCommandContext(
    beans: Beans,
    guildProperties: GuildProperties,
    guild: Guild,
    channel: TextChannel,
    invoker: Member,
    command: Command,
    override val argumentText: String,
) : CommandContext(beans, guildProperties, guild, channel, invoker, command, "!", "!" + command.name) {
    val replies = mutableListOf<String>()
    val replyMessages = mutableListOf<MessageCreateData>()
    val replyEmbeds = mutableListOf<MessageEmbed>()

    override fun reply(msg: String) {
        replies.add(msg)
    }

    override fun replyMsg(msg: MessageCreateData) {
        replyMessages.add(msg)
    }

    override fun replyEmbed(embed: MessageEmbed) {
        replyEmbeds.add(embed)
    }
}

/** Builds a [RecordingCommandContext] backed by [player] (a fresh Mockito mock by default). */
fun fakeContext(
    command: Command,
    argumentText: String = "",
    player: Player = mock(Player::class.java),
): RecordingCommandContext {
    val guild = mock(Guild::class.java)
    val guildProperties = GuildProperties(1L)

    val registry = mock(PlayerRegistry::class.java)
    `when`(registry.get(guild, guildProperties)).thenReturn(player)

    val beans = CommandContext.Beans(registry)

    return RecordingCommandContext(
        beans,
        guildProperties,
        guild,
        mock(TextChannel::class.java),
        mock(Member::class.java),
        command,
        argumentText,
    )
}

/** Runs a command's suspend [Command.invoke0] synchronously for tests. */
fun Command.invokeBlocking(ctx: CommandContext) = runBlocking { invoke0(ctx) }
