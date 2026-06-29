package dev.arbjerg.ukulele.jda

import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.StatusChangeEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.message.MessageReceivedEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

@Service
class EventHandler(
    @param:Lazy private val commandManager: CommandManager,
) : ListenerAdapter() {
    private val log: Logger = LoggerFactory.getLogger(EventHandler::class.java)

    override fun onReady(event: ReadyEvent) {
        // Register slash commands globally (propagation can take up to ~1h on first publish).
        val commands = commandManager.getCommands().map { it.buildSlashData() }
        event.jda
            .updateCommands()
            .addCommands(commands)
            .queue(
                { log.info("Registered ${commands.size} slash commands") },
                { log.error("Failed to register slash commands", it) },
            )
    }

    override fun onMessageReceived(event: MessageReceivedEvent) {
        if (event.isWebhookMessage || event.author.isBot) return
        if (event.channelType != ChannelType.TEXT) return

        commandManager.onMessage(event.guild, event.channel.asTextChannel(), event.member!!, event.message)
    }

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        if (event.guild == null || event.member == null) {
            event.reply("Commands can only be used in a server.").setEphemeral(true).queue()
            return
        }

        // Acknowledge within Discord's 3s window; replies then go through the interaction hook.
        // /play loads tracks asynchronously, so a deferred reply is required.
        event.deferReply().queue()
        commandManager.onSlash(event)
    }

    override fun onStatusChange(event: StatusChangeEvent) {
        log.info("{}: {} -> {}", event.entity.shardInfo, event.oldStatus, event.newStatus)
    }
}
