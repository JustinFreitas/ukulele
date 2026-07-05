package dev.arbjerg.ukulele.jda

import dev.arbjerg.ukulele.audio.PlayerRegistry
import net.dv8tion.jda.api.entities.channel.ChannelType
import net.dv8tion.jda.api.events.StatusChangeEvent
import net.dv8tion.jda.api.events.guild.GuildLeaveEvent
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.events.session.ReadyEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service

@Service
class EventHandler(
    @param:Lazy private val commandManager: CommandManager,
    private val playerRegistry: PlayerRegistry,
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

    override fun onGuildLeave(event: GuildLeaveEvent) {
        log.info("Left guild {}, cleaning up player resources", event.guild.name)
        playerRegistry.remove(event.guild.idLong)
    }

    override fun onGuildVoiceUpdate(event: GuildVoiceUpdateEvent) {
        val self = event.guild.selfMember

        // If the bot itself was disconnected
        if (event.member == self && event.channelJoined == null) {
            log.info("Bot was disconnected from voice channel in guild {}", event.guild.name)
            playerRegistry.getExisting(event.guild.idLong)?.pause()
            return
        }

        // If a voice channel the bot is connected to became empty of human listeners
        val connectedChannel = event.guild.audioManager.connectedChannel
        if (connectedChannel != null) {
            val hasHumans = connectedChannel.members.any { !it.user.isBot }
            if (!hasHumans) {
                log.info("Voice channel {} became empty in guild {}, pausing and disconnecting", connectedChannel.name, event.guild.name)
                playerRegistry.getExisting(event.guild.idLong)?.pause()
                event.guild.audioManager.closeAudioConnection()
            }
        }
    }
}
